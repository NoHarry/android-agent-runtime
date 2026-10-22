package cc.noharry.agent.runtime.probe

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Observe-only / completeness checks for SPEC-RPB-002.
 * Moves goal-contract counter: Probe observe-only/completeness checks.
 */
class ProbeBusCompletenessTest {

    @Test
    fun disabledPacksReportIdleCompleteWithoutAcceptingEvents() = runBlocking {
        val store = InMemoryCompletenessStore()
        val bus = NoopProbeBus(runId = "run-disabled", completenessStore = store)
        val factory = factory("run-disabled")

        val accepted = bus.accept(
            factory.next(stageId = "run.start", probeId = "lifecycle.run"),
        )
        val summary = bus.onRunFinished()

        assertFalse(accepted)
        assertTrue(summary.probesDisabled)
        assertTrue(summary.complete)
        assertEquals(0L, summary.enqueued)
        assertEquals(0L, summary.written)
        assertEquals(0L, summary.dropped)
    }

    @Test
    fun acceptNeverThrowsWhenSinkOrStoreMisbehaves_failOpen() = runBlocking {
        val throwingSink = object : ProbeEventSink {
            override fun tryEnqueue(event: ProbeEvent): Boolean {
                throw IllegalStateException("sink boom")
            }

            override suspend fun drain(timeoutMillis: Long): DrainResult =
                DrainResult(queueEmpty = true, timedOut = false, ioFailed = true, writtenDelta = 0L)

            override fun stats(): EventSinkStats =
                EventSinkStats(0, 0, 0, ioFailed = true)
        }
        val store = InMemoryCompletenessStore()
        val factory = factory("run-fail-open")
        val bus = DefaultProbeBus(
            runId = "run-fail-open",
            enabledProducers = setOf("lifecycle.run"),
            eventSink = throwingSink,
            completenessStore = store,
            factoryCounters = factory,
        )

        val accepted = bus.accept(
            factory.next(stageId = "run.start", probeId = "lifecycle.run"),
        )
        val summary = bus.onRunFinished()

        assertFalse(accepted)
        assertFalse(summary.complete)
        assertTrue(summary.error >= 1L)
    }

    @Test
    fun queueOverflowIncrementsDroppedAndKeepsEnqueuedSeparate() = runBlocking {
        val sink = InMemoryProbeEventSink(capacity = 2)
        val store = InMemoryCompletenessStore()
        val factory = factory("run-overflow")
        val bus = DefaultProbeBus(
            runId = "run-overflow",
            enabledProducers = setOf("lifecycle.run"),
            eventSink = sink,
            completenessStore = store,
            factoryCounters = factory,
        )

        val a1 = bus.accept(factory.next("run.start", "lifecycle.run"))
        val a2 = bus.accept(factory.next("run.finish", "lifecycle.run"))
        val a3 = bus.accept(factory.next("run.terminal", "lifecycle.run", mapOf("status" to "ok")))
        val summary = bus.onRunFinished(drainTimeoutMillis = 1_000L)

        assertTrue(a1)
        assertTrue(a2)
        assertFalse(a3)
        assertEquals(2L, summary.enqueued)
        assertEquals(1L, summary.dropped)
        assertEquals(2L, summary.written)
        // overflow → not complete
        assertFalse(summary.complete)
        assertEquals(2, sink.lines.size)
    }

    @Test
    fun duplicatePackProducersEnableOnce() = runBlocking {
        val packs = listOf(
            ProbePack("pack.a", producers = listOf("lifecycle.run", "model.round")),
            ProbePack("pack.b", producers = listOf("lifecycle.run", "tool.exec")),
        )
        assertEquals(
            setOf("lifecycle.run", "model.round", "tool.exec"),
            uniqueProducers(packs),
        )

        val sink = InMemoryProbeEventSink(capacity = 16)
        val store = InMemoryCompletenessStore()
        val factory = factory("run-dedupe")
        val bus = DefaultProbeBus.fromPacks(
            runId = "run-dedupe",
            packs = packs,
            eventSink = sink,
            completenessStore = store,
            factoryCounters = factory,
        )

        assertTrue(bus.accept(factory.next("run.start", "lifecycle.run")))
        assertTrue(bus.accept(factory.next("model.round.start", "model.round", mapOf("roundIndex" to 0))))
        assertFalse(bus.accept(factory.next("run.start", "unknown.producer")))

        val summary = bus.onRunFinished(drainTimeoutMillis = 1_000L)
        assertEquals(2L, summary.enqueued)
        assertEquals(2L, summary.written)
        assertEquals(0L, summary.dropped)
        assertTrue(summary.complete)
    }

    @Test
    fun drainTimeoutMarksIncompleteWithWrittenLessThanEnqueued() = runBlocking {
        val sink = InMemoryProbeEventSink(capacity = 16)
        val store = InMemoryCompletenessStore()
        val factory = factory("run-drain")
        val bus = DefaultProbeBus(
            runId = "run-drain",
            enabledProducers = setOf("lifecycle.run"),
            eventSink = sink,
            completenessStore = store,
            factoryCounters = factory,
        )

        repeat(5) {
            assertTrue(bus.accept(factory.next("run.start", "lifecycle.run")))
        }

        // Partial drain: only 2 written, remainder still queued → timedOut.
        val partial = sink.drainPartial(maxWrites = 2)
        store.mergeDrain(partial)
        store.mergeFactory(
            truncated = factory.truncatedCount(),
            error = factory.errorCount(),
            sequenceMax = factory.sequenceMax(),
        )
        val summary = store.writeFinal("run-drain")

        assertEquals(5L, summary.enqueued)
        assertEquals(2L, summary.written)
        assertTrue(summary.drainTimedOut)
        assertFalse(summary.complete)
        assertTrue(summary.written < summary.enqueued)
    }

    @Test
    fun completenessSideCarSurvivesEventSinkIoFailure() = runBlocking {
        val dir = Files.createTempDirectory("probe-completeness").toFile()
        try {
            val eventFile = File(dir, "probes.jsonl")
            val completenessFile = File(dir, "probe-completeness.json")
            val forceIo = AtomicBoolean(true)
            val sink = FileProbeEventSink(eventFile = eventFile, capacity = 8, forceIoFailure = forceIo)
            val store = FileCompletenessStore(completenessFile)
            val factory = factory("run-sidecar")
            val bus = DefaultProbeBus(
                runId = "run-sidecar",
                enabledProducers = setOf("lifecycle.run"),
                eventSink = sink,
                completenessStore = store,
                factoryCounters = factory,
            )

            assertTrue(bus.accept(factory.next("run.start", "lifecycle.run")))
            val summary = bus.onRunFinished(drainTimeoutMillis = 500L)

            assertTrue(summary.sinkIoFailed)
            assertFalse(summary.complete)
            assertEquals(1L, summary.enqueued)
            assertEquals(0L, summary.written)
            assertTrue(completenessFile.isFile)
            val body = completenessFile.readText()
            assertTrue(body.contains("\"sinkIoFailed\":true") || body.contains("\"sinkIoFailed\": true"))
            assertTrue(body.contains("\"complete\":false") || body.contains("\"complete\": false"))
            assertFalse(eventFile.exists() && eventFile.length() > 0L)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun factoryAllocatesMonotonicSequencesUnderConcurrentEmit() = runBlocking {
        val factory = factory("run-seq")
        val jobs = (0 until 32).map {
            async {
                factory.next(stageId = "run.start", probeId = "lifecycle.run")
            }
        }
        val events = jobs.awaitAll()
        val sequences = events.map { it.sequence }.sorted()
        assertEquals((0L until 32L).toList(), sequences)
        assertEquals(31L, factory.sequenceMax())
    }

    @Test
    fun attrsBudgetTruncationSetsFlagAndCounter() {
        val factory = ProbeEventFactory(
            runId = "run-trunc",
            sessionId = null,
            requestId = null,
            clock = FixedProbeClock(),
            catalog = ProbeStageCatalog.p0Minimal(),
            attrsMaxBytes = 64,
        )
        val huge = "x".repeat(200)
        val event = factory.next(
            stageId = "probe.error",
            probeId = "lifecycle.run",
            attrs = mapOf("code" to "BIG", "message" to huge),
        )
        assertTrue(event.truncated)
        assertEquals(1L, factory.truncatedCount())
    }

    private fun factory(runId: String): ProbeEventFactory =
        ProbeEventFactory(
            runId = runId,
            sessionId = "sess",
            requestId = "req",
            clock = FixedProbeClock(),
            catalog = ProbeStageCatalog.p0Minimal(),
        )
}

private class FixedProbeClock : ProbeClock {
    override fun elapsedNanos(): Long = 1_000L
    override fun epochMillis(): Long = 1_700_000_000_000L
}

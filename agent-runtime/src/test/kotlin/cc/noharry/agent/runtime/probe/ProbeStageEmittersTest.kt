package cc.noharry.agent.runtime.probe

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mandatory P0 stage family emitters (goal-contract stages 0→7).
 */
class ProbeStageEmittersTest {

    @Test
    fun allSevenMandatoryFamiliesEmitWhenBenchCoreEnabled() = runBlocking {
        val sink = InMemoryProbeEventSink(capacity = 256)
        val store = InMemoryCompletenessStore()
        val session = ProbeSession.create(
            runId = "run-stages",
            sessionId = "s1",
            packs = listOf(benchCorePack()),
            eventSink = sink,
            completenessStore = store,
            clock = FixedClock(),
        )

        val results = session.emitAllMandatoryFamiliesSynthetic()
        assertEquals(7, results.size)
        assertTrue(results.values.all { it })
        assertEquals(ProbeStageFamily.entries.toSet(), results.keys)

        val summary = session.finish(drainTimeoutMillis = 1_000L)
        assertTrue(summary.enqueued > 0)
        assertEquals(summary.enqueued, summary.written)
        assertTrue(summary.complete)

        val stages = sink.lines.map { it.stageId }.toSet()
        assertTrue(stages.contains("run.start"))
        assertTrue(stages.contains("request.config"))
        assertTrue(stages.contains("model.round.start"))
        assertTrue(stages.contains("tool.execute.end"))
        assertTrue(stages.contains("search.hosted") || stages.contains("network.fetch"))
        assertTrue(stages.contains("run.terminal") || stages.contains("empty_completion"))
        // Production finish always appends probe.completeness after primary drain;
        // synthetic helpers also cover case.bound / probe.error for catalog parity.
        assertTrue(stages.contains("case.bound"))
        assertTrue(stages.contains("probe.completeness"))
        assertTrue(stages.contains("probe.error") || stages.contains("case.bound"))
    }

    @Test
    fun disabledPacksDoNotEmitMandatoryFamilies() = runBlocking {
        val sink = InMemoryProbeEventSink()
        val session = ProbeSession.create(
            runId = "run-off",
            packs = emptyList(),
            eventSink = sink,
            clock = FixedClock(),
        )
        val results = session.emitAllMandatoryFamiliesSynthetic()
        assertTrue(results.values.all { !it })
        assertTrue(sink.lines.isEmpty())
        val summary = session.finish()
        assertTrue(summary.probesDisabled)
        assertTrue(summary.complete)
    }

    @Test
    fun catalogStageIdsCoverMandatoryFamiliesWithoutVerticalNames() {
        val catalog = ProbeStageCatalog.p0Minimal()
        val required = listOf(
            "run.start", "run.finish",
            "request.config", "session.isolate",
            "model.round.start", "model.round.end",
            "tool.prepare", "tool.execute.start", "tool.execute.end",
            "search.hosted", "network.fetch",
            "empty_completion", "run.terminal",
            "case.bound", "probe.error", "probe.completeness",
        )
        for (id in required) {
            assertTrue("missing $id", catalog.isKnown(id))
        }
        val banned = listOf("navi", "vendor", "climate", "music_play")
        // Catalog only exposes known stage ids via isKnown; ensure banned strings are not stages.
        for (b in banned) {
            assertFalse(catalog.isKnown(b))
            assertFalse(catalog.isKnown("tool.$b"))
        }
    }

    private class FixedClock : ProbeClock {
        override fun elapsedNanos(): Long = 42L
        override fun epochMillis(): Long = 1_700_000_000_000L
    }
}

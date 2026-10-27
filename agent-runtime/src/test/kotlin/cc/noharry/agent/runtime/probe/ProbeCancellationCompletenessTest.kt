package cc.noharry.agent.runtime.probe

import cc.noharry.agent.kernel.core.context.*
import cc.noharry.agent.kernel.core.loop.*
import cc.noharry.agent.kernel.core.memory.*
import cc.noharry.agent.kernel.core.plugin.*
import cc.noharry.agent.kernel.core.state.*
import cc.noharry.agent.kernel.core.tools.*
import cc.noharry.agent.kernel.core.trace.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §2 terminal checklist: cancellation must not crash the runtime path and must leave
 * visible probe evidence (run.terminal status + completeness side-car), never silent success.
 *
 * Production entry: [ProbeRunAttachment.finishAndPersist] with status CANCELLED
 * (same path NativeToolLoop uses for terminal finishAndPersist).
 */
class ProbeCancellationCompletenessTest {

    @Test
    fun cancelledTerminalPersistsVisibleStatusAndCompletenessSidecar() = runBlocking {
        val root = Files.createTempDirectory("probe-cancel-ok").toFile()
        try {
            val runId = "cancel-run-1"
            val attachment = openBenchAttachment(root, runId)
            assertTrue(attachment.session.emitRunStart())
            assertTrue(attachment.session.emitModelRoundStart(0))
            val completeness = attachment.finishAndPersist("CANCELLED")
            assertCancelledArtifactsVisible(root, runId, completeness)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellationExceptionDuringFinishDoesNotEscapeAndLeavesIncompleteEvidence() = runBlocking {
        val root = Files.createTempDirectory("probe-cancel-throw").toFile()
        try {
            val runId = "cancel-run-throw"
            probeRunDirectory(root, runId).mkdirs()
            val completenessFile = File(probeRunDirectory(root, runId), PROBE_COMPLETENESS_JSON)
            val attachment = attachmentWithCancellingDrain(root, runId, completenessFile)
            assertTrue(attachment.session.emitRunStart())
            val summary = finishCancelledWithoutEscaping(attachment)
            assertIncompleteCancelEvidence(summary, completenessFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertCancelledArtifactsVisible(
        root: File,
        runId: String,
        completeness: ProbeCompleteness,
    ) {
        assertTrue(File(probeRunDirectory(root, runId), PROBES_JSONL).isFile)
        assertTrue(File(probeRunDirectory(root, runId), PROBE_COMPLETENESS_JSON).isFile)
        assertTrue(File(probeRunDirectory(root, runId), RUN_META_JSON).isFile)
        val probes = File(probeRunDirectory(root, runId), PROBES_JSONL).readText()
        assertTrue(
            "run.terminal CANCELLED must be visible in probes: $probes",
            probes.contains("\"stageId\":\"run.terminal\"") && probes.contains("CANCELLED"),
        )
        val completeBody = File(probeRunDirectory(root, runId), PROBE_COMPLETENESS_JSON).readText()
        assertTrue(completeBody.contains("\"runId\""))
        assertTrue(completeness.enqueued >= 1L || completeBody.contains("enqueued"))
    }

    private fun attachmentWithCancellingDrain(
        root: File,
        runId: String,
        completenessFile: File,
    ): ProbeRunAttachment {
        val sink = CancellingProbeEventSink()
        val store = FileCompletenessStore(completenessFile)
        val factory = ProbeEventFactory(
            runId = runId,
            sessionId = "s",
            requestId = runId,
            clock = FixedCancelClock,
            catalog = ProbeStageCatalog.p0Minimal(),
        )
        val bus = DefaultProbeBus(
            runId = runId,
            enabledProducers = setOf(ProbeProducerIds.LIFECYCLE_RUN, ProbeProducerIds.TERMINAL),
            eventSink = sink,
            completenessStore = store,
            factoryCounters = factory,
        )
        val session = ProbeSession(
            runId = runId,
            sessionId = "s",
            requestId = runId,
            bus = bus,
            factory = factory,
            packs = listOf(benchCorePack()),
            casePackId = "none",
        )
        return ProbeRunAttachment(
            session = session,
            reliabilityRoot = root,
            runMetaBuilder = { cancelRunMeta(runId, "s") },
        )
    }

    private suspend fun finishCancelledWithoutEscaping(
        attachment: ProbeRunAttachment,
    ): ProbeCompleteness =
        try {
            attachment.finishAndPersist("CANCELLED")
        } catch (error: CancellationException) {
            throw AssertionError("cancellation must not escape finishAndPersist", error)
        }

    private fun assertIncompleteCancelEvidence(
        summary: ProbeCompleteness,
        completenessFile: File,
    ) {
        assertFalse(
            "cancelled drain fault must not report complete success: $summary",
            summary.complete && summary.error == 0L && !summary.drainTimedOut && !summary.sinkIoFailed,
        )
        assertTrue(completenessFile.isFile)
        val body = completenessFile.readText()
        assertTrue(
            body.contains("\"complete\":false") ||
                body.contains("\"complete\": false") ||
                body.contains("error"),
        )
    }

    private fun openBenchAttachment(root: File, runId: String): ProbeRunAttachment =
        ProbeRunAttachment.open(
            reliabilityRoot = root,
            runId = runId,
            sessionId = "sess-cancel",
            requestId = runId,
            packs = listOf(benchCorePack()),
            runMetaBuilder = { cancelRunMeta(runId, "sess-cancel") },
        )

    private fun cancelRunMeta(runId: String, sessionId: String): RunMeta =
        buildRunMeta(
            runId = runId,
            sessionId = sessionId,
            armId = "default",
            buildId = "build-1",
            startedAtEpochMillis = 1L,
            provider = "p",
            protocol = "native",
            model = "m",
            baseUrlHost = "local",
            isolation = IsolationDeclaration("fresh", "cleared", "cold"),
            caseBinding = CaseBinding("none"),
            enabledProbePacks = listOf("bench_core"),
            toolset = emptyList(),
        )
}

private object FixedCancelClock : ProbeClock {
    override fun elapsedNanos(): Long = 1L
    override fun epochMillis(): Long = 1L
}

/** Drain throws CancellationException to simulate cancel during probe flush. */
private class CancellingProbeEventSink : ProbeEventSink {
    private var accepted = 0L
    override fun tryEnqueue(event: ProbeEvent): Boolean {
        accepted += 1
        return true
    }

    override suspend fun drain(timeoutMillis: Long): DrainResult {
        throw CancellationException("simulated cancel during probe drain")
    }

    override fun stats(): EventSinkStats =
        EventSinkStats(accepted, 0, 0, ioFailed = false)
}

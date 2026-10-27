package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunPhase
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.state.RunStateController
import cc.noharry.agent.kernel.core.state.RuntimeStateRetentionPolicy
import cc.noharry.agent.runtime.reliability.FileBackedReliabilityStorage
import cc.noharry.agent.runtime.reliability.ReliabilityJsonCodecs
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RunStateQueuedPersistenceTest {
    @Test
    fun queuedRunPersistsReloadsAndStaysNonterminal() {
        val storage = FileBackedReliabilityStorage(
            Files.createTempDirectory("runtime-queued-persist-").toFile(),
        )
        val clock = AtomicLong()
        val controller = RunStateController(
            nowEpochMillis = clock::incrementAndGet,
            storage = storage,
        )

        val queued = controller.queueRun(request("queued-run"), "queued-run")
        assertEquals(RuntimeRunStatus.QUEUED, queued.status)
        assertEquals(RuntimeRunStatus.QUEUED, controller.snapshot("queued-run")?.status)
        assertEquals(RuntimeRunStatus.QUEUED, controller.envelope("queued-run")?.status)
        assertEquals(RuntimeRunPhase.QUEUED, controller.envelope("queued-run")?.phase)
        assertNotNull(controller.workpad("queued-run"))
        assertTrue(controller.traceEvents("queued-run").any { it.eventName == "RUN_CREATED" })

        val encoded = ReliabilityJsonCodecs.encodeRun(
            requireNotNull(controller.snapshot("queued-run")),
            requireNotNull(controller.envelope("queued-run")),
        )
        assertEquals(RuntimeRunStatus.QUEUED, ReliabilityJsonCodecs.decodeSnapshot(encoded).status)
        assertEquals(RuntimeRunPhase.QUEUED, ReliabilityJsonCodecs.decodeEnvelope(encoded).phase)

        val restored = RunStateController(clock::incrementAndGet, storage = storage)
        assertEquals(RuntimeRunStatus.QUEUED, restored.snapshot("queued-run")?.status)
        assertEquals(RuntimeRunStatus.QUEUED, restored.envelope("queued-run")?.status)
        assertEquals(RuntimeRunPhase.QUEUED, restored.envelope("queued-run")?.phase)
        assertNotNull(restored.workpad("queued-run"))
        assertEquals(0, restored.cachedTerminalRunCount())
    }

    @Test
    fun queuedRunIsRetainedWhileTerminalCacheEvicts() {
        val storage = FileBackedReliabilityStorage(
            Files.createTempDirectory("runtime-queued-retain-").toFile(),
        )
        val clock = AtomicLong()
        val controller = RunStateController(
            nowEpochMillis = clock::incrementAndGet,
            storage = storage,
            retentionPolicy = RuntimeStateRetentionPolicy(maxTerminalRuns = 1),
        )

        controller.queueRun(request("still-queued"), "still-queued")
        completeRun(controller, "old-terminal")
        completeRun(controller, "new-terminal")

        assertEquals(1, controller.cachedTerminalRunCount())
        assertEquals(RuntimeRunStatus.QUEUED, controller.snapshot("still-queued")?.status)
        assertEquals(RuntimeRunPhase.QUEUED, controller.envelope("still-queued")?.phase)
        assertEquals(RuntimeRunStatus.COMPLETED, controller.snapshot("old-terminal")?.status)
    }

    @Test
    fun markRunAdmittedTransitionsQueuedToRunningExactlyOnce() {
        val storage = FileBackedReliabilityStorage(
            Files.createTempDirectory("runtime-queued-admit-").toFile(),
        )
        val clock = AtomicLong()
        val controller = RunStateController(
            nowEpochMillis = clock::incrementAndGet,
            storage = storage,
        )

        controller.queueRun(request("admit-run"), "admit-run")
        val admitted = controller.markRunAdmitted("admit-run")
        val again = controller.markRunAdmitted("admit-run")

        assertEquals(RuntimeRunStatus.RUNNING, admitted.status)
        assertEquals(RuntimeRunStatus.RUNNING, again.status)
        assertEquals(RuntimeRunStatus.RUNNING, controller.snapshot("admit-run")?.status)
        assertEquals(RuntimeRunPhase.MODEL_RUNNING, controller.envelope("admit-run")?.phase)

        val restored = RunStateController(clock::incrementAndGet, storage = storage)
        assertEquals(RuntimeRunStatus.RUNNING, restored.snapshot("admit-run")?.status)
        assertEquals(RuntimeRunPhase.MODEL_RUNNING, restored.envelope("admit-run")?.phase)
        assertEquals(RuntimeRunStatus.RUNNING, restored.markRunAdmitted("admit-run").status)
    }

    @Test
    fun startRunCreatesRunningOnceAndDuplicateLiveRunIdFailsClosed() {
        val storage = FileBackedReliabilityStorage(
            Files.createTempDirectory("runtime-queued-dup-").toFile(),
        )
        val clock = AtomicLong()
        val controller = RunStateController(
            nowEpochMillis = clock::incrementAndGet,
            storage = storage,
        )

        val started = controller.startRun(request("live-run"), "live-run")
        assertEquals(RuntimeRunStatus.RUNNING, started.status)
        assertEquals(RuntimeRunPhase.MODEL_RUNNING, controller.envelope("live-run")?.phase)

        val duplicateStart = assertThrows(IllegalStateException::class.java) {
            controller.startRun(request("live-run"), "live-run")
        }
        val duplicateQueue = assertThrows(IllegalStateException::class.java) {
            controller.queueRun(request("live-run"), "live-run")
        }
        assertTrue(duplicateStart.message.orEmpty().contains("Duplicate live runId live-run"))
        assertTrue(duplicateQueue.message.orEmpty().contains("Duplicate live runId live-run"))
        assertEquals(RuntimeRunStatus.RUNNING, controller.snapshot("live-run")?.status)

        controller.queueRun(request("queued-dup"), "queued-dup")
        val duplicateQueuedStart = assertThrows(IllegalStateException::class.java) {
            controller.startRun(request("queued-dup"), "queued-dup")
        }
        val duplicateQueuedQueue = assertThrows(IllegalStateException::class.java) {
            controller.queueRun(request("queued-dup"), "queued-dup")
        }
        assertTrue(duplicateQueuedStart.message.orEmpty().contains("Duplicate live runId queued-dup"))
        assertTrue(duplicateQueuedQueue.message.orEmpty().contains("Duplicate live runId queued-dup"))
        assertEquals(RuntimeRunStatus.QUEUED, controller.snapshot("queued-dup")?.status)

        val restored = RunStateController(clock::incrementAndGet, storage = storage)
        assertThrows(IllegalStateException::class.java) {
            restored.startRun(request("live-run"), "live-run")
        }
        assertEquals(RuntimeRunStatus.RUNNING, restored.snapshot("live-run")?.status)
        assertThrows(IllegalStateException::class.java) {
            restored.startRun(request("queued-dup"), "queued-dup")
        }
        assertThrows(IllegalStateException::class.java) {
            restored.queueRun(request("queued-dup"), "queued-dup")
        }
        assertEquals(RuntimeRunStatus.QUEUED, restored.snapshot("queued-dup")?.status)
        assertEquals(RuntimeRunPhase.QUEUED, restored.envelope("queued-dup")?.phase)
    }

    @Test
    fun firstPartyPassIsFalseForQueuedAndSchedulerSafeCodesMatchWireValues() {
        assertFalse(
            RuntimeCompletionSummary.firstPartyPass(
                RuntimeRunStatus.QUEUED,
                RuntimeCompletionSummary(outcome = RuntimeCompletionOutcome.SUCCEEDED),
            ),
        )
        assertFalse(
            RuntimeCompletionSummary.firstPartyPass(
                RuntimeRunStatus.QUEUED,
                RuntimeCompletionSummary.unknown(),
            ),
        )
        assertEquals("SESSION_QUEUE_FULL", RuntimeErrorCode.SESSION_QUEUE_FULL.wireCode)
        assertEquals("RUNTIME_BUSY", RuntimeErrorCode.RUNTIME_BUSY.wireCode)
        assertEquals("RUNTIME_CLOSED", RuntimeErrorCode.RUNTIME_CLOSED.wireCode)
        assertEquals("ADMISSION_POLICY_REQUIRED", RuntimeErrorCode.ADMISSION_POLICY_REQUIRED.wireCode)
        assertEquals("RUN_REPLACED", RuntimeErrorCode.RUN_REPLACED.wireCode)
        assertEquals("DUPLICATE_RUN_ID", RuntimeErrorCode.DUPLICATE_RUN_ID.wireCode)
        assertEquals(
            "PROCESS_RESTART_ADMISSION_UNSUPPORTED",
            RuntimeErrorCode.PROCESS_RESTART_ADMISSION_UNSUPPORTED.wireCode,
        )
    }

    @Test
    fun markExecutionResumedTransitionsWaitingToRunning() {
        val controller = RunStateController(nowEpochMillis = AtomicLong()::incrementAndGet)
        val started = controller.startRun(request("resume-run"), "resume-run")
        controller.markWaitingConfirmation(started, "Approve?", "pending-1")

        val resumed = controller.markExecutionResumed("resume-run")
        val again = controller.markExecutionResumed("resume-run")

        assertEquals(RuntimeRunStatus.RUNNING, resumed.status)
        assertEquals(RuntimeRunStatus.RUNNING, again.status)
        assertEquals(RuntimeRunPhase.MODEL_RUNNING, controller.envelope("resume-run")?.phase)
    }

    @Test
    fun markRunAdmittedFailsClosedForUnknownAndNonQueuedStates() {
        val controller = RunStateController(nowEpochMillis = AtomicLong()::incrementAndGet)
        assertThrows(IllegalStateException::class.java) {
            controller.markRunAdmitted("missing-run")
        }
        completeRun(controller, "done-run")
        val rejectTerminal = assertThrows(IllegalStateException::class.java) {
            controller.markRunAdmitted("done-run")
        }
        assertTrue(rejectTerminal.message.orEmpty().contains("Cannot admit run done-run"))
        assertNull(controller.tryMarkRunAdmitted("missing-run"))
        assertNull(controller.tryMarkRunAdmitted("done-run"))
        assertEquals(RuntimeRunStatus.COMPLETED, controller.snapshot("done-run")?.status)
    }

    @Test
    fun tryMarkExecutionResumedDoesNotReviveTerminal() {
        val controller = RunStateController(nowEpochMillis = AtomicLong()::incrementAndGet)
        completeRun(controller, "done-resume")
        assertNull(controller.tryMarkExecutionResumed("done-resume"))
        assertEquals(RuntimeRunStatus.COMPLETED, controller.snapshot("done-resume")?.status)
        assertNull(controller.tryMarkExecutionResumed("missing-resume"))
    }

    private fun completeRun(controller: RunStateController, runId: String) {
        controller.startRun(request(runId), runId)
        controller.applyRuntimeResult(
            RuntimeResult(
                runId = runId,
                status = RuntimeRunStatus.COMPLETED,
                response = "done-$runId",
            ),
        )
    }

    private fun request(runId: String): AgentRequest = AgentRequest(
        requestId = runId,
        sessionId = "session-$runId",
        input = "run",
    )
}

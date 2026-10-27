@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.ConfirmationChoice
import cc.noharry.agent.kernel.api.ConfirmationDecision
import cc.noharry.agent.kernel.api.ConfirmationResumeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SessionSchedulingRestartIntegrationTest {
    @Test
    fun processRestartStaleWaitingAndQueuedAreTerminalizedWithoutQueueRecovery() = runSchedulingTest {
        assertStaleWaitingTerminalizedWithoutQueueRecovery()
        assertStaleQueuedTerminalizedWithoutQueueRecovery()
    }
}

private suspend fun assertStaleWaitingTerminalizedWithoutQueueRecovery() {
    val storageRoot = Files.createTempDirectory("runtime-restart-honesty-").toFile()
    val first = AgentRuntimeBuilder.create()
        .reliabilityStorageRoot(storageRoot)
        .confirmationOnRun()
        .sessionConcurrency(maxActiveSessions = 1)
        .build() as DefaultAgentRuntime
    first.run(request(RUN_A1, SESSION_A)).toList()
    assertEquals(RuntimeRunStatus.WAITING_CONFIRMATION, first.getRunState(RUN_A1)?.status)

    val restored = AgentRuntimeBuilder.create()
        .reliabilityStorageRoot(storageRoot)
        .confirmationOnRun()
        .sessionConcurrency(maxActiveSessions = 1)
        .build() as DefaultAgentRuntime
    try {
        val resumed = restored.resumeConfirmation(
            ConfirmationDecision("$RUN_A1:confirmation", "resume-1", ConfirmationChoice.APPROVE),
        )
        assertTrue(resumed is ConfirmationResumeResult.Rejected || resumed is ConfirmationResumeResult.NotFound)

        val replacement = restored.run(request(RUN_A2, SESSION_A)).toList()
        assertTrue(replacement.any { it is AgentEvent.ConfirmationRequired })
        assertEquals(RuntimeRunStatus.CANCELLED, restored.getRunState(RUN_A1)?.status)
        assertEquals(
            RuntimeErrorCode.PROCESS_RESTART_ADMISSION_UNSUPPORTED.wireCode,
            restored.getRuntimeResult(RUN_A1)?.errorCode,
        )
        assertEquals(
            RuntimeCompletionOutcome.UNKNOWN,
            restored.getRuntimeResult(RUN_A1)?.completion?.outcome,
        )
        assertEquals(RuntimeRunStatus.WAITING_CONFIRMATION, restored.getRunState(RUN_A2)?.status)
    } finally {
        restored.close()
    }
}

private suspend fun CoroutineScope.assertStaleQueuedTerminalizedWithoutQueueRecovery() {
    val hold = CompletableDeferred<Unit>()
    val entered = CompletableDeferred<Unit>()
    val queuedRoot = Files.createTempDirectory("runtime-restart-queued-").toFile()
    val parked = AgentRuntimeBuilder.create()
        .reliabilityStorageRoot(queuedRoot)
        .modelClient(SingleGateFinishModel(hold, entered))
        .sessionConcurrency(maxActiveSessions = 1)
        .build() as DefaultAgentRuntime
    val active = launch { parked.run(request(RUN_A1, SESSION_A)).toList() }
    entered.await()
    val queued = launch { parked.run(request(RUN_A2, SESSION_A)).toList() }
    awaitQueued(parked, RUN_A2)

    val afterCrash = AgentRuntimeBuilder.create()
        .reliabilityStorageRoot(queuedRoot)
        .modelClient(SingleGateFinishModel(CompletableDeferred(), CompletableDeferred()))
        .sessionConcurrency(maxActiveSessions = 1)
        .confirmationOnRun()
        .build() as DefaultAgentRuntime
    try {
        afterCrash.run(request(RUN_A3, SESSION_A)).toList()
        assertEquals(RuntimeRunStatus.CANCELLED, afterCrash.getRunState(RUN_A2)?.status)
        assertEquals(
            RuntimeErrorCode.PROCESS_RESTART_ADMISSION_UNSUPPORTED.wireCode,
            afterCrash.getRuntimeResult(RUN_A2)?.errorCode,
        )
        assertEquals(RuntimeRunStatus.WAITING_CONFIRMATION, afterCrash.getRunState(RUN_A3)?.status)
    } finally {
        afterCrash.close()
        hold.complete(Unit)
        active.cancel()
        queued.cancel()
    }
}

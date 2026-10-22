@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.CancelPendingConfirmationRequest
import cc.noharry.agent.kernel.api.ConfirmationChoice
import cc.noharry.agent.kernel.api.ConfirmationDecision
import cc.noharry.agent.kernel.api.ConfirmationResumeResult
import cc.noharry.agent.kernel.api.SessionRunConflictPolicy
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunPhase
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSchedulingResumeIntegrationTest {
    @Test
    fun waitingReleasesExecutionAndKeepsSameSessionQueued() = runSchedulingTest {
        val runtime = confirmationRuntime()
        try {
            runtime.run(request(RUN_A1, SESSION_A)).toList()
            assertEquals(RuntimeRunStatus.WAITING_CONFIRMATION, runtime.getRunState(RUN_A1)?.status)
            assertEquals(0, runtime.sessionSchedulerSnapshotForTest().activeExecutionCount)
            assertEquals(RUN_A1, runtime.sessionSchedulerSnapshotForTest().owners[SESSION_A])

            runtime.run(request(RUN_B1, SESSION_B)).toList()
            assertEquals(RuntimeRunStatus.WAITING_CONFIRMATION, runtime.getRunState(RUN_B1)?.status)
            assertEquals(setOf(SESSION_A, SESSION_B), runtime.sessionSchedulerSnapshotForTest().owners.keys)
            assertEquals(0, runtime.sessionSchedulerSnapshotForTest().activeExecutionCount)

            val a2 = launch {
                runtime.run(request(RUN_A2, SESSION_A, SessionRunConflictPolicy.QUEUE_NEW)).toList()
            }
            awaitQueued(runtime, RUN_A2)
            assertEquals(RUN_A1, runtime.sessionSchedulerSnapshotForTest().owners[SESSION_A])

            runtime.resumeConfirmation(
                ConfirmationDecision("$RUN_A1:confirmation", "resume-1", ConfirmationChoice.APPROVE),
            )
            a2.join()
            assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRunState(RUN_A1)?.status)
            assertEquals(RuntimeRunStatus.WAITING_CONFIRMATION, runtime.getRunState(RUN_A2)?.status)
            assertIdleOwnersExcept(runtime, SESSION_A to RUN_A2, SESSION_B to RUN_B1)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun resumeUsesExactLeaseAndDoesNotCreateSecondOwner() = runSchedulingTest {
        val runtime = confirmationRuntime()
        try {
            runtime.run(request(RUN_A1, SESSION_A)).toList()
            assertEquals(RUN_A1, runtime.sessionSchedulerSnapshotForTest().owners[SESSION_A])
            val accepted = runtime.resumeConfirmation(
                ConfirmationDecision("$RUN_A1:confirmation", "resume-1", ConfirmationChoice.APPROVE),
            )
            assertEquals(ConfirmationResumeResult.Accepted, accepted)
            assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRunState(RUN_A1)?.status)
            assertIdle(runtime)
            val duplicate = runtime.resumeConfirmation(
                ConfirmationDecision("$RUN_A1:confirmation", "resume-1", ConfirmationChoice.APPROVE),
            )
            assertEquals(ConfirmationResumeResult.AlreadyConsumed, duplicate)
            assertIdle(runtime)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun confirmationResumePersistsRunningBeforeResumedLoopWork() = runSchedulingTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val runtime = planConfirmationRuntime(GatedMcpProvider(gate, entered))
        try {
            runtime.run(request(RUN_A1, SESSION_A)).toList()
            assertEquals(RuntimeRunStatus.WAITING_CONFIRMATION, runtime.getRunState(RUN_A1)?.status)

            val resume = launch {
                runtime.resumeConfirmation(
                    ConfirmationDecision("$RUN_A1:confirmation", "resume-1", ConfirmationChoice.APPROVE),
                )
            }
            entered.await()
            assertEquals(RuntimeRunStatus.RUNNING, runtime.getRunState(RUN_A1)?.status)
            assertEquals(RuntimeRunPhase.MODEL_RUNNING, runtime.getRunEnvelope(RUN_A1)?.phase)
            assertEquals(1, runtime.sessionSchedulerSnapshotForTest().activeExecutionCount)
            assertEquals(RUN_A1, runtime.sessionSchedulerSnapshotForTest().owners[SESSION_A])

            gate.complete(Unit)
            resume.join()
            assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRunState(RUN_A1)?.status)
            assertIdle(runtime)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun confirmationResumeVsCancelAndReplaceDoesNotUseStaleWaiting() = runSchedulingTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val runtime = planConfirmationRuntime(GatedMcpProvider(gate, entered))
        try {
            runtime.run(request(RUN_A1, SESSION_A)).toList()
            val resume = launch {
                runtime.resumeConfirmation(
                    ConfirmationDecision("$RUN_A1:confirmation", "resume-1", ConfirmationChoice.APPROVE),
                )
            }
            entered.await()
            awaitState(runtime, RUN_A1, RuntimeRunStatus.RUNNING)

            val replaced = launch {
                runtime.run(
                    request(RUN_A5, SESSION_A, SessionRunConflictPolicy.CANCEL_AND_REPLACE),
                ).toList()
            }
            awaitQueued(runtime, RUN_A5)
            assertEquals(RuntimeRunStatus.RUNNING, runtime.getRunState(RUN_A1)?.status)
            assertTrue(runtime.getRuntimeResult(RUN_A1)?.errorCode != RuntimeErrorCode.RUN_REPLACED.wireCode)
            assertFalse(replaced.isCompleted)
            gate.complete(Unit)
            resume.join()
            replaced.join()
            assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRunState(RUN_A1)?.status)
            assertTrue(runtime.getRuntimeResult(RUN_A1)?.errorCode != RuntimeErrorCode.RUN_REPLACED.wireCode)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun duplicateConcurrentResumeFailClosedWithoutSecondExecution() = runSchedulingTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val provider = GatedMcpProvider(gate, entered)
        val runtime = planConfirmationRuntime(provider)
        try {
            runtime.run(request(RUN_A1, SESSION_A)).toList()
            val first = CompletableDeferred<ConfirmationResumeResult>()
            val second = CompletableDeferred<ConfirmationResumeResult>()
            launch {
                first.complete(
                    runtime.resumeConfirmation(
                        ConfirmationDecision("$RUN_A1:confirmation", "resume-1", ConfirmationChoice.APPROVE),
                    ),
                )
            }
            entered.await()
            launch {
                second.complete(
                    runtime.resumeConfirmation(
                        ConfirmationDecision("$RUN_A1:confirmation", "resume-2", ConfirmationChoice.APPROVE),
                    ),
                )
            }
            val late = second.await()
            assertTrue(late is ConfirmationResumeResult.Rejected || late is ConfirmationResumeResult.AlreadyConsumed)
            assertEquals(1, provider.callCount)
            assertEquals(RuntimeRunStatus.RUNNING, runtime.getRunState(RUN_A1)?.status)
            gate.complete(Unit)
            assertEquals(ConfirmationResumeResult.Accepted, first.await())
            assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRunState(RUN_A1)?.status)
            assertEquals(1, provider.callCount)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun resumeGrantBeforePersistLosesToCancelWithoutRevivingRunning() = runSchedulingTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val provider = GatedMcpProvider(gate, entered)
        val runtime = planConfirmationRuntime(provider)
        val attached = CompletableDeferred<Unit>()
        val releasePersist = CompletableDeferred<Unit>()
        try {
            runtime.run(request(RUN_A1, SESSION_A)).toList()
            assertEquals(RuntimeRunStatus.WAITING_CONFIRMATION, runtime.getRunState(RUN_A1)?.status)
            runtime.sessionRunSchedulerForTest().afterAttachJobForTest = {
                attached.complete(Unit)
                releasePersist.await()
            }
            val resume = CompletableDeferred<ConfirmationResumeResult>()
            launch(Dispatchers.Default) {
                resume.complete(
                    runtime.resumeConfirmation(
                        ConfirmationDecision("$RUN_A1:confirmation", "resume-1", ConfirmationChoice.APPROVE),
                    ),
                )
            }
            attached.await()
            val cancelled = runtime.cancelPendingConfirmation(
                CancelPendingConfirmationRequest("$RUN_A1:confirmation", "cancel-race"),
            )
            assertTrue(cancelled is cc.noharry.agent.kernel.api.CancelPendingResult.Cancelled)
            assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRunState(RUN_A1)?.status)
            releasePersist.complete(Unit)
            val resumeResult = resume.await()
            assertTrue(
                resumeResult is ConfirmationResumeResult.Rejected ||
                    resumeResult is ConfirmationResumeResult.AlreadyConsumed,
            )
            assertEquals(0, provider.callCount)
            assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRunState(RUN_A1)?.status)
            assertTrue(runtime.getRunState(RUN_A1)?.status != RuntimeRunStatus.RUNNING)
            assertIdle(runtime)
        } finally {
            runtime.sessionRunSchedulerForTest().afterAttachJobForTest = null
            gate.complete(Unit)
            runtime.close()
        }
    }

    @Test
    fun userInputThenConfirmationReleasesExecutionPermit() = runSchedulingTest {
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(java.nio.file.Files.createTempDirectory("sched-resume-").toFile())
            .toolProvider(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .policyRule(confirmUnlessApprovedPolicy())
            .modelClient(askThenConfirmModel())
            .sessionConcurrency(maxActiveSessions = 1)
            .build() as DefaultAgentRuntime
        try {
            runtime.run(request(RUN_A1, SESSION_A)).toList()
            assertEquals(RuntimeRunStatus.WAITING_CONFIRMATION, runtime.getRunState(RUN_A1)?.status)
            assertEquals(0, runtime.sessionSchedulerSnapshotForTest().activeExecutionCount)
            assertEquals(RUN_A1, runtime.sessionSchedulerSnapshotForTest().owners[SESSION_A])
            assertTrue(runtime.sessionSchedulerSnapshotForTest().waitingOwnerRunIds.contains(RUN_A1))
        } finally {
            runtime.close()
        }
    }
}

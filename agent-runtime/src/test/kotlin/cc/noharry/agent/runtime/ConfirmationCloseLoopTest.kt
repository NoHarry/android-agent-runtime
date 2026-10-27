@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.CancelPendingConfirmationRequest
import cc.noharry.agent.kernel.api.CancelPendingResult
import cc.noharry.agent.kernel.api.ConfirmationChoice
import cc.noharry.agent.kernel.api.ConfirmationDecision
import cc.noharry.agent.kernel.api.ConfirmationResumeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.api.reliability.SuggestedNextAction
import cc.noharry.agent.kernel.core.state.InMemoryConfirmationStore
import cc.noharry.agent.kernel.core.state.RunStateController
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationCloseLoopTest {
    @Test
    fun runEmitsPendingConfirmationWhenConfigured() = runBlocking {
        val runtime = AgentRuntimeBuilder.create().confirmationOnRun().build()

        val events = runtime.run(agentRequest()).toList()
        val confirmationEvent = events.filterIsInstance<AgentEvent.ConfirmationRequired>()
            .single()

        assertEquals("session-1", confirmationEvent.pending.sessionId)
        assertEquals(RuntimeRunStatus.WAITING_CONFIRMATION, runtime.getRunState("request-1")?.status)
        assertEquals(confirmationEvent.pending.pendingId, runtime.getRunState("request-1")?.pendingConfirmationId)
        assertEquals(confirmationEvent.pending, runtime.getPendingConfirmation(confirmationEvent.pending.pendingId))
    }

    @Test
    fun confirmationStoreAllocatesUniquePendingIdsWithinRun() {
        val store = InMemoryConfirmationStore(nowEpochMillis = { 1_000L })

        val first = store.create("request-1", "session-1", expiresInMillis = 10_000)
        val firstResume = store.resume(
            ConfirmationDecision(first.pendingId, "resume-1", ConfirmationChoice.APPROVE),
        )
        val second = store.create("request-1", "session-1", expiresInMillis = 10_000)
        val duplicateFirst = store.resume(
            ConfirmationDecision(first.pendingId, "resume-duplicate", ConfirmationChoice.APPROVE),
        )

        assertEquals("request-1:confirmation", first.pendingId)
        assertEquals("request-1:confirmation:2", second.pendingId)
        assertEquals(ConfirmationResumeResult.Accepted, firstResume.apiResult)
        assertEquals(ConfirmationResumeResult.AlreadyConsumed, duplicateFirst.apiResult)
        assertNull(store.getPending(first.pendingId))
        assertEquals(second, store.getPending(second.pendingId))
    }

    @Test
    fun waitingConfirmationSnapshotCarriesCurrentPendingId() {
        val controller = RunStateController(nowEpochMillis = { 1_000L })
        val started = controller.startRun(agentRequest(), runId = "request-1")

        controller.markWaitingConfirmation(
            started = started,
            prompt = "Approve example.org?",
            pendingConfirmationId = "request-1:confirmation:2",
        )

        val snapshot = controller.snapshot("request-1")
        assertEquals(RuntimeRunStatus.WAITING_CONFIRMATION, snapshot?.status)
        assertEquals(SuggestedNextAction.RESUME_CONFIRMATION, snapshot?.suggestedNextAction)
        assertEquals("request-1:confirmation:2", snapshot?.pendingConfirmationId)
    }

    @Test
    fun approveConfirmationCompletesRunAndIsIdempotent() = runBlocking {
        val runtime = runtimeWithPendingConfirmation()
        val pending = pendingConfirmation(runtime)

        val accepted = runtime.resumeConfirmation(
            ConfirmationDecision(
                pendingId = pending.pendingId,
                requestId = "resume-1",
                choice = ConfirmationChoice.APPROVE,
            ),
        )
        val duplicate = runtime.resumeConfirmation(
            ConfirmationDecision(
                pendingId = pending.pendingId,
                requestId = "resume-1",
                choice = ConfirmationChoice.APPROVE,
            ),
        )

        assertEquals(ConfirmationResumeResult.Accepted, accepted)
        assertEquals(ConfirmationResumeResult.AlreadyConsumed, duplicate)
        assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRunState("request-1")?.status)
        assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRuntimeResult("request-1")?.status)
        assertNull(runtime.getPendingConfirmation(pending.pendingId))
    }

    @Test
    fun denyConfirmationCancelsRunWithoutApproval() = runBlocking {
        val runtime = runtimeWithPendingConfirmation()
        val pending = pendingConfirmation(runtime)

        val denied = runtime.resumeConfirmation(
            ConfirmationDecision(
                pendingId = pending.pendingId,
                requestId = "resume-1",
                choice = ConfirmationChoice.DENY,
            ),
        )

        assertTrue(denied is ConfirmationResumeResult.Rejected)
        assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRunState("request-1")?.status)
        assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRuntimeResult("request-1")?.status)
    }

    @Test
    fun cancelPendingConfirmationCancelsRun() = runBlocking {
        val runtime = runtimeWithPendingConfirmation()
        val pending = pendingConfirmation(runtime)

        val cancelled = runtime.cancelPendingConfirmation(
            CancelPendingConfirmationRequest(
                pendingId = pending.pendingId,
                requestId = "cancel-1",
                reason = "host cancelled",
            ),
        )

        assertTrue(cancelled is CancelPendingResult.Cancelled)
        assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRunState("request-1")?.status)
        assertNull(runtime.getPendingConfirmation(pending.pendingId))
    }

    @Test
    fun resumeExpiredConfirmationReturnsExpired() = runBlocking {
        var now = 1_000L
        val runtime = AgentRuntimeBuilder.create()
            .nowEpochMillis { now }
            .confirmationOnRun(expiresInMillis = 5)
            .build()
        runtime.run(agentRequest()).toList()
        val pending = pendingConfirmation(runtime)

        now = 1_006L
        val expired = runtime.resumeConfirmation(
            ConfirmationDecision(
                pendingId = pending.pendingId,
                requestId = "resume-1",
                choice = ConfirmationChoice.APPROVE,
            ),
        )

        assertEquals(ConfirmationResumeResult.Expired, expired)
        assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRunState("request-1")?.status)
        assertNull(runtime.getPendingConfirmation(pending.pendingId))
    }

    @Test
    fun missingConfirmationReturnsNotFound() = runBlocking {
        val runtime = AgentRuntimeBuilder.create().build()

        val result = runtime.resumeConfirmation(
            ConfirmationDecision(
                pendingId = "missing",
                requestId = "resume-1",
                choice = ConfirmationChoice.APPROVE,
            ),
        )

        assertEquals(ConfirmationResumeResult.NotFound, result)
    }

    private suspend fun runtimeWithPendingConfirmation(): AgentRuntime {
        val runtime = AgentRuntimeBuilder.create().confirmationOnRun().build()
        runtime.run(agentRequest()).toList()
        return runtime
    }

    private suspend fun pendingConfirmation(runtime: AgentRuntime) =
        runtime.getPendingConfirmation("request-1:confirmation").also {
            assertNotNull(it)
        } ?: error("Expected pending confirmation.")

    private fun agentRequest(): AgentRequest = AgentRequest(
        requestId = "request-1",
        sessionId = "session-1",
        input = "trigger confirmation",
    )
}

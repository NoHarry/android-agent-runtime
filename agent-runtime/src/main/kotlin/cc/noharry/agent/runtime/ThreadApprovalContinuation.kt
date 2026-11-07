@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.ApprovalState
import cc.noharry.agent.spi.AgentEvent
import cc.noharry.agent.spi.AgentRequest
import cc.noharry.agent.spi.PendingConfirmation
import cc.noharry.agent.spi.reliability.RuntimeResult
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.loop.NativeLoopOutcome
import cc.noharry.agent.kernel.core.loop.RuntimeSteeringQueue
import cc.noharry.agent.runtime.run.NativeLoopFactory
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Same-stream approval never executes a denied tool; its result becomes the next model round's input. */
internal suspend fun continueThreadApproval(
    request: AgentRequest,
    outcome: NativeLoopOutcome.RequiresConfirmation,
    approve: suspend (PendingConfirmation) -> ApprovalState,
    loops: NativeLoopFactory,
    steering: RuntimeSteeringQueue,
    now: Long,
    emit: suspend (AgentEvent) -> Unit,
): NativeLoopOutcome {
    val paused = outcome.pendingToolUse
    val pending = PendingConfirmation(
        pendingId = UUID.randomUUID().toString(), sessionId = request.sessionId,
        runId = request.executionId, expiresAtEpochMillis = now + 60_000L,
        idempotencyKey = paused?.toolCallId.orEmpty(), prompt = outcome.prompt,
        toolName = paused?.toolUse?.toolName, argumentsJson = paused?.toolUse?.argumentsJson,
    )
    steering.resume(request.executionId)
    val decision = try { approve(pending) } catch (cancelled: CancellationException) {
        withContext(NonCancellable) { paused?.let { loops.recordUnconfirmed(it, "CONFIRMATION_CANCELLED") } }
        throw cancelled
    }
    if (paused != null) {
        if (decision == ApprovalState.APPROVED) return loops.resumeConfirmedToolUse(paused, emit)
        if (decision == ApprovalState.DENIED) return loops.resumeDeniedToolUse(paused, emit)
    }
    val code = when (decision) {
        ApprovalState.EXPIRED -> "CONFIRMATION_EXPIRED"
        ApprovalState.CANCELLED -> "CONFIRMATION_CANCELLED"
        else -> "CONFIRMATION_DENIED"
    }
    paused?.let {
        loops.recordUnconfirmed(it, code)
        emit(AgentEvent.NativeToolCallStarted(request.executionId, it.toolUse.toolName, it.toolUse.argumentsJson, it.toolCallId))
        emit(AgentEvent.NativeToolCallFinished(request.executionId, it.toolUse.toolName, "{\"ok\":false,\"code\":\"$code\"}", it.toolCallId))
    }
    val status = if (decision == ApprovalState.CANCELLED) RuntimeRunStatus.CANCELLED else RuntimeRunStatus.FAILED
    return NativeLoopOutcome.Terminal(RuntimeResult(request.executionId, status,
        "Tool approval was ${decision.name.lowercase()}.", errorCode = code), outcome.summary)
}

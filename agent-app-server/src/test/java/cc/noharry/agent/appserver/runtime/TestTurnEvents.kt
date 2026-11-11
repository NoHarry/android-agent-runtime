@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)
package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.ToolSource
import cc.noharry.agent.spi.reliability.RuntimeResult
import cc.noharry.agent.spi.reliability.RuntimeRunStatus

internal fun testTurnStarted(runId: String) = ThreadEvent.TurnStarted("session-1", runId)
internal fun testTurnCompleted(result: RuntimeResult) = ThreadEvent.TurnCompleted(TurnResult(
    result.runId, when (result.status) {
        RuntimeRunStatus.COMPLETED -> TurnStatus.COMPLETED
        RuntimeRunStatus.WAITING_USER_INPUT -> TurnStatus.WAITING_FOR_ANSWER
        RuntimeRunStatus.CANCELLED -> TurnStatus.INTERRUPTED
        else -> TurnStatus.FAILED
    }, result.response, emptyList(), error = result.errorCode?.let { TurnError(it, result.response) },
))
internal fun testToolStarted(runId: String, toolName: String, arguments: String, toolCallId: String = toolName) =
    ThreadEvent.ItemStarted(ThreadItem.ToolCall(toolCallId, runId, toolName, ToolSource.BACKEND, arguments = arguments))
internal fun testToolCompleted(runId: String, toolName: String, result: String, toolCallId: String = toolName, success: Boolean = true) =
    ThreadEvent.ItemCompleted(ThreadItem.ToolCall(toolCallId, runId, toolName, ToolSource.BACKEND, output = result, status = if (success) ItemStatus.COMPLETED else ItemStatus.FAILED))
internal fun testTurnFailed(runId: String, code: String, message: String, recoverable: Boolean = false) =
    ThreadEvent.TurnFailed(runId, TurnError(code, message, recoverable))
internal fun testAssistantDelta(runId: String, text: String) = ThreadEvent.ItemDelta("$runId-message", text)
internal fun testReasoningDelta(runId: String, text: String) = ThreadEvent.ItemDelta("$runId-thinking", text)
internal fun testSearch(runId: String, provider: String, queryPreview: String, resultCount: Int?) =
    ThreadEvent.ItemCompleted(ThreadItem.WebSearch("search", runId, provider, queryPreview, resultCount))

internal class TestApproval(override val state: ApprovalState = ApprovalState.PENDING) : Approval() {
    override val id = "confirm-1"
    override val turnId = "run-1"
    override val tool = "web_fetch"
    override val arguments = "{}"
    override val domain = "example.com"
    override val reason = "Approval required"
    override val expiresAt = Long.MAX_VALUE
    override val status = if (state == ApprovalState.PENDING) ItemStatus.IN_PROGRESS else ItemStatus.COMPLETED
    override suspend fun approve(scope: ApprovalScope) = RespondResult.ACCEPTED
    override suspend fun deny(reason: String?) = RespondResult.ACCEPTED
}

/** [request] run on this runtime as the server runs a turn: in its session's thread, with its options. */
internal fun cc.noharry.agent.AgentRuntime.runRequest(request: RuntimeTurnRequest) =
    request.thread(this).runStreamed(TurnInput.text(request.input), request.options())

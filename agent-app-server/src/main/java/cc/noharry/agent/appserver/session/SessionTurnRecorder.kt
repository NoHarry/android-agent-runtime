@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.session
import cc.noharry.agent.appserver.evidence.RAW_STATUS_FAILED
import cc.noharry.agent.appserver.evidence.RAW_STATUS_OK
import cc.noharry.agent.appserver.evidence.RAW_STATUS_UNKNOWN
import cc.noharry.agent.appserver.evidence.RunReplayResult
import cc.noharry.agent.appserver.protocol.SessionToolCall
import cc.noharry.agent.appserver.protocol.SessionTurn
import cc.noharry.agent.appserver.protocol.TokenUsageState
import cc.noharry.agent.appserver.runtime.followUpQuestion
import cc.noharry.agent.appserver.runtime.hostCompletion
import cc.noharry.agent.appserver.runtime.redactToolPayload
import cc.noharry.agent.appserver.usage.plus
import cc.noharry.agent.appserver.usage.toUsageDelta
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest
import cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.spi.reliability.RuntimeRunStatus

internal class SessionTurnRecorder(
    private val request: RuntimeTurnRequest,
    private val userText: String,
    private val startedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    private val tools = mutableListOf<MutableSessionToolCall>()
    private val toolsByCallId = mutableMapOf<String, Int>()
    private val pending = mutableMapOf<String, ArrayDeque<Int>>()
    private var runId: String = request.requestId
    private var finalStatus: String = RuntimeRunStatus.RUNNING.name
    private var finalResponse: String = ""
    private var completionOutcome: String = RuntimeCompletionOutcome.UNKNOWN.name
    private var replayStatus: String = DEBUG_REPLAY_STATUS_NOT_RUN
    private var replaySummary: String = ""
    private var usage = TokenUsageState()

    internal fun recordEvent(event: ThreadEvent) {
        when (event) {
            is ThreadEvent.TurnStarted -> runId = event.turnId
            is ThreadEvent.UsageUpdated -> usage = TokenUsageState().plus(event.usage.toUsageDelta()).copy(requestCount = usage.requestCount + 1)
            is ThreadEvent.ItemStarted -> (event.item as? cc.noharry.agent.ThreadItem.ToolCall)?.let {
                recordToolStarted(it.tool, it.arguments, it.id)
            }
            is ThreadEvent.ItemCompleted -> (event.item as? cc.noharry.agent.ThreadItem.ToolCall)?.let {
                recordToolFinished(it.tool, it.output.orEmpty(), if (it.status == cc.noharry.agent.ItemStatus.COMPLETED) RAW_STATUS_OK else RAW_STATUS_FAILED, it.id)
            }
            is ThreadEvent.TurnCompleted -> {
                runId = event.result.turnId
                finalStatus = when (event.result.status) {
                    cc.noharry.agent.TurnStatus.WAITING_FOR_ANSWER -> "WAITING_USER_INPUT"
                    cc.noharry.agent.TurnStatus.INTERRUPTED -> "CANCELLED"
                    else -> event.result.status.name
                }
                finalResponse = event.result.finalResponse
                completionOutcome = event.result.hostCompletion().name
            }
            is ThreadEvent.TurnFailed -> {
                runId = event.turnId
                finalStatus = RuntimeRunStatus.FAILED.name
                finalResponse = event.error.message
            }
            else -> Unit
        }
    }

    internal fun recordDiagnostics(result: cc.noharry.agent.spi.reliability.RuntimeResult) {
        completionOutcome = result.completion.outcome.name
        if (finalStatus != "WAITING_USER_INPUT") finalStatus = result.status.name
        if (finalResponse.isBlank()) finalResponse = result.response
    }

    internal fun recordCancelled() {
        if (finalStatus == RuntimeRunStatus.RUNNING.name) {
            finalStatus = RuntimeRunStatus.CANCELLED.name
        }
    }

    internal fun recordRuntimeFailure(message: String?) {
        if (finalStatus == RuntimeRunStatus.RUNNING.name) {
            finalStatus = RuntimeRunStatus.FAILED.name
            finalResponse = message.orEmpty()
        }
    }

    internal fun recordReplay(replay: RunReplayResult) {
        replayStatus = if (replay.passed) DEBUG_REPLAY_STATUS_PASSED else DEBUG_REPLAY_STATUS_FAILED
        replaySummary = replay.summary
    }

    fun snapshot(): SessionTurn =
        SessionTurn(
            turnId = request.requestId,
            requestId = request.requestId,
            runId = runId.ifBlank { request.requestId },
            sessionId = request.sessionId,
            scene = request.scene.orEmpty(),
            userText = userText,
            startedAtEpochMillis = startedAtEpochMillis,
            completedAtEpochMillis = System.currentTimeMillis(),
            finalStatus = finalStatus,
            finalResponse = finalResponse,
            tools = tools.map { it.toImmutable() },
            completionOutcome = completionOutcome,
            replayStatus = replayStatus,
            replaySummary = replaySummary,
            usage = usage,
        )

    private fun recordToolStarted(toolName: String, argumentsPreview: String, toolCallId: String = "") {
        val call = toolByCallId(toolCallId) ?: tools.asReversed().firstOrNull {
            it.toolName == toolName && it.argumentsPreview.isBlank()
        } ?: newTool(toolName, toolCallId)
        rememberToolCallId(call, toolCallId)
        call.argumentsPreview = redactToolPayload(argumentsPreview).take(MAX_TOOL_PREVIEW_CHARS)
        pending.getOrPut(toolName) { ArrayDeque() }.addLast(call.sequence)
    }

    private fun recordToolFinished(
        toolName: String,
        resultPreview: String,
        status: String,
        toolCallId: String = "",
        removePendingOnMatch: Boolean = true,
    ) {
        val sequence = toolByCallId(toolCallId)?.sequence ?: pending[toolName]?.removeLastOrNull()
        val call = sequence?.let { seq -> tools.firstOrNull { it.sequence == seq } }
            ?: tools.asReversed().firstOrNull { it.toolName == toolName && it.resultPreview.isBlank() }
            ?: newTool(toolName, toolCallId)
        rememberToolCallId(call, toolCallId)
        if (removePendingOnMatch) removePending(toolName, call.sequence)
        call.resultPreview = redactToolPayload(resultPreview).take(MAX_TOOL_PREVIEW_CHARS)
        call.status = status
    }

    private fun newTool(toolName: String, toolCallId: String = ""): MutableSessionToolCall =
        MutableSessionToolCall(tools.size + 1, toolName, toolCallId).also {
            tools += it
            rememberToolCallId(it, toolCallId)
        }

    private fun toolByCallId(toolCallId: String): MutableSessionToolCall? =
        toolCallId.takeIf { it.isNotBlank() }?.let(toolsByCallId::get)?.let { sequence ->
            tools.firstOrNull { it.sequence == sequence }
        }

    private fun rememberToolCallId(call: MutableSessionToolCall, toolCallId: String) {
        if (toolCallId.isBlank()) return
        call.toolCallId = call.toolCallId.ifBlank { toolCallId }
        toolsByCallId[toolCallId] = call.sequence
    }

    private fun removePending(toolName: String, sequence: Int) {
        val stack = pending[toolName] ?: return
        val remaining = stack.filterNot { it == sequence }
        stack.clear()
        stack.addAll(remaining)
        if (stack.isEmpty()) pending.remove(toolName)
    }
}

private data class MutableSessionToolCall(
    val sequence: Int,
    val toolName: String,
    var toolCallId: String = "",
    var argumentsPreview: String = "",
    var resultPreview: String = "",
    var status: String = RAW_STATUS_UNKNOWN,
) {
    fun toImmutable(): SessionToolCall =
        SessionToolCall(sequence, toolName, argumentsPreview, resultPreview, status, toolCallId)
}

private fun String.rawLineValue(name: String): String =
    lineSequence().firstOrNull { it.startsWith("$name=") }?.substringAfter("=").orEmpty().trim()

private fun rawStatus(body: String): String =
    when (body.rawLineValue("ok").lowercase()) {
        "true" -> RAW_STATUS_OK
        "false" -> RAW_STATUS_FAILED
        else -> RAW_STATUS_UNKNOWN
    }

// Kept large enough to hold full write_file payloads: persisted sessions are the source
// for HTML artifact persistence and for restored-timeline page previews.
private const val MAX_TOOL_PREVIEW_CHARS = 64 * 1024

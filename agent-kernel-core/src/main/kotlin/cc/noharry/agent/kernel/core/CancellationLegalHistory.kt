package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelMessage
import kotlinx.coroutines.CancellationException

class CancellationLegalHistoryTracker {
    private val observedCalls = linkedMapOf<String, ObservedToolCall>()
    private var reasoningConsumed = false

    fun observe(
        event: ModelEvent.ToolCall,
        reasoningContent: String?,
        thinkingSignature: String?,
    ) {
        val key = event.historyKey()
        if (key in observedCalls) return
        observedCalls[key] = ObservedToolCall(
            event = event,
            reasoningContent = reasoningContent.forHistory(thinkingSignature),
            thinkingSignature = thinkingSignature,
        )
    }

    fun roundMessagesForCancellation(committedMessages: List<ModelMessage>): List<ModelMessage> {
        val committedToolResultKeys = committedMessages
            .filter { it.role == TOOL_RESULT_ROLE && !it.toolCallId.isNullOrBlank() }
            .mapTo(linkedSetOf()) { it.toolCallId.orEmpty() }
        return observedCalls.values
            .filterNot { it.event.historyKey() in committedToolResultKeys }
            .flatMap { call ->
                listOf(
                    call.event.toAssistantToolCallMessage(call.reasoningContent, call.thinkingSignature),
                    call.event.toToolResultMessage(cancelledToolResult(call.event.name)),
                )
            }
    }

    private fun String?.forHistory(thinkingSignature: String?): String? {
        val content = this?.takeIf { it.isNotBlank() } ?: return null
        if (!thinkingSignature.isNullOrBlank()) return content
        if (reasoningConsumed) return DEDUPED_REASONING_MARKER
        reasoningConsumed = true
        return content
    }
}

class LegalHistoryCancellationException(
    val roundMessages: List<ModelMessage>,
    val original: CancellationException,
) : CancellationException(original.message) {
    init {
        initCause(original)
    }
}

private data class ObservedToolCall(
    val event: ModelEvent.ToolCall,
    val reasoningContent: String?,
    val thinkingSignature: String?,
)

private fun ModelEvent.ToolCall.historyKey(): String =
    id.ifBlank { "$name:$argumentsJson" }

private const val TOOL_RESULT_ROLE = "tool"

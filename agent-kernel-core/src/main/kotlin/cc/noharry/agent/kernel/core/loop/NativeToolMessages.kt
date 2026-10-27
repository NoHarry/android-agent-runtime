@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelImage
import cc.noharry.agent.kernel.api.model.ModelMessage

/** [text], what the model told the user this round, on the round's first call unless one already carries it. */
internal fun List<ModelMessage>.withAssistantText(text: String): List<ModelMessage> {
    val said = text.trim()
    if (said.isEmpty() || any { it.assistantText.isNotEmpty() }) return this
    val first = indexOfFirst { it.role == "assistant" && it.toolCallId != null }
    if (first < 0) return this
    return toMutableList().also { it[first] = it[first].copy(assistantText = said) }
}

internal fun RoundOutcome.withAssistantText(text: String): RoundOutcome = when (this) {
    is RoundOutcome.Finished -> copy(messages = messages.withAssistantText(text))
    is RoundOutcome.NeedsUserInput -> copy(messages = messages.withAssistantText(text))
    is RoundOutcome.RequiresConfirmation -> copy(messages = messages.withAssistantText(text))
    is RoundOutcome.ToolResults -> copy(messages = messages.withAssistantText(text))
    is RoundOutcome.Failed -> copy(messages = messages.withAssistantText(text))
}

fun ModelEvent.ToolCall.toAssistantToolCallMessage(
    reasoningContent: String? = null,
    thinkingSignature: String? = null,
): ModelMessage =
    ModelMessage(
        role = "assistant",
        content = reasoningContent?.takeIf { it.isNotBlank() }.orEmpty(),
        toolCallId = id,
        toolName = name,
        toolArgumentsJson = argumentsJson,
        thinkingSignature = thinkingSignature,
    )

fun ModelEvent.ToolCall.toToolResultMessage(
    result: String,
    images: List<ModelImage> = emptyList(),
): ModelMessage =
    ModelMessage(
        role = "tool",
        content = result,
        images = images,
        toolCallId = id,
        toolName = name,
    )

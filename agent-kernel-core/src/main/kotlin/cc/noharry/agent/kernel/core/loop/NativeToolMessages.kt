package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelImage
import cc.noharry.agent.kernel.api.model.ModelMessage

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

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
    is RoundOutcome.RequiresConfirmation -> {
        val said = messages.withAssistantText(text)
        // The paused call is not a message yet; it takes the text when no earlier call of the round did.
        val paused = pendingToolUse?.takeIf { text.isNotBlank() && said.none { it.assistantText.isNotEmpty() } }
        copy(messages = said, pendingToolUse = paused?.copy(assistantText = text.trim()) ?: pendingToolUse)
    }
    is RoundOutcome.ToolResults -> copy(messages = messages.withAssistantText(text))
    is RoundOutcome.Failed -> copy(messages = messages.withAssistantText(text))
}

/** The paused call keeps the round's thinking signature for the request that resumes it. */
internal fun RoundOutcome.withPausedCallSignature(signature: String?): RoundOutcome =
    if (this is RoundOutcome.RequiresConfirmation && pendingToolUse != null && !signature.isNullOrBlank()) {
        copy(pendingToolUse = pendingToolUse.copy(thinkingSignature = signature))
    } else this

/** The paused call as the model made it: its thinking, signature and what it said before it. */
fun PendingNativeToolUse.toCallMessage(): ModelMessage =
    ModelEvent.ToolCall(toolCallId, toolUse.toolName, toolUse.argumentsJson)
        .toAssistantToolCallMessage(reasoningContent, thinkingSignature)
        .copy(assistantText = assistantText)

/**
 * The paused call and why it never ran, for the session's history: the run ended at the confirmation ([code] is
 * USER_DECLINED, CONFIRMATION_CANCELLED or CONFIRMATION_EXPIRED), and the next run should know.
 */
fun PendingNativeToolUse.unconfirmedMessages(code: String): List<ModelMessage> {
    val why = when (code) {
        USER_DECLINED_CODE -> "The user declined this action"
        CONFIRMATION_EXPIRED_CODE -> "The user did not confirm this action in time"
        else -> "The confirmation was withdrawn before the user answered"
    }
    return listOf(
        toCallMessage(),
        ModelMessage(
            role = "tool",
            content = """{"ok":false,"code":"$code","message":"$why, so it was not run and the run ended there."}""",
            toolCallId = toolCallId,
            toolName = toolUse.toolName,
        ),
    )
}

/** While the user has not answered, the session's history shows the call as waiting rather than lost. */
internal fun PendingNativeToolUse.awaitingConfirmationMessages(): List<ModelMessage> = listOf(
    toCallMessage(),
    ModelMessage(role = "tool", content = AWAITING_CONFIRMATION_RESULT, toolCallId = toolCallId, toolName = toolUse.toolName),
)

const val USER_DECLINED_CODE = "USER_DECLINED"
const val CONFIRMATION_CANCELLED_CODE = "CONFIRMATION_CANCELLED"
const val CONFIRMATION_EXPIRED_CODE = "CONFIRMATION_EXPIRED"
private const val AWAITING_CONFIRMATION_RESULT =
    """{"ok":false,"status":"awaiting_confirmation","note":"Waiting for the user to confirm; this action has not run."}"""

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

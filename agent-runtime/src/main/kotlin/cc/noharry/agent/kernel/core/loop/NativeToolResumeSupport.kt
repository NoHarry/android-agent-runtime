@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.loop

import cc.noharry.agent.spi.ToolUseResult
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.spi.reliability.RuntimeHandoff
import cc.noharry.agent.kernel.core.trace.NativePublicEventBuffer
import cc.noharry.agent.kernel.core.trace.LoopSessionRecorder

internal suspend fun declinedToolResult(
    pending: PendingNativeToolUse,
    code: String,
    events: NativePublicEventBuffer,
    recorder: LoopSessionRecorder,
): ConfirmedToolUseResult {
    val why = if (code == CONFIRMATION_EXPIRED_CODE) "The user did not confirm this action in time" else "The user declined this action"
    val result = """{"ok":false,"code":"$code","message":"$why; the tool was not run."}"""
    events.recordStarted(pending.toolCallId, pending.toolUse.toolName, pending.toolUse.argumentsJson)
    events.recordFinished(pending.toolCallId, pending.toolUse.toolName, result)
    recorder.recordToolFinished(pending.context, pending.toToolCallEvent(), pending.toolUse.source.toDynamicToolSource(), result)
    return ConfirmedToolUseResult(result, ToolUseResult(false, result, code = code))
}

internal fun RuntimeHandoff.toResumeContextMessage(): ModelMessage = ModelMessage(
        role = "system",
        content = buildList {
            task?.let { add("Task: $it") }
            if (done.isNotEmpty()) add("Done: ${done.joinToString("; ")}")
            if (nextActions.isNotEmpty()) add("Next: ${nextActions.joinToString("; ")}")
            if (decisions.isNotEmpty()) add("Decisions: ${decisions.joinToString("; ")}")
            if (doNotRepeat.isNotEmpty()) add("Do not repeat: ${doNotRepeat.joinToString("; ")}")
        }.joinToString("\n"),
    )

internal fun PendingNativeToolUse.toToolCallEvent(): ModelEvent.ToolCall =
        ModelEvent.ToolCall(
            id = toolCallId,
            name = toolUse.toolName,
            argumentsJson = toolUse.argumentsJson,
        )

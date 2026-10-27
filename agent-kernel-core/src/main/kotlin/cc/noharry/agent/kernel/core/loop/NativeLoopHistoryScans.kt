package cc.noharry.agent.kernel.core.loop

import cc.noharry.agent.kernel.api.model.ModelMessage

// What a run's messages so far show: a todo update, a tool that moved the task on, a retry the loop already sent.

internal fun List<ModelMessage>.hasTodoUpdate(): Boolean =
    any { it.role == "tool" && it.toolName == "update_todo" }

internal fun List<ModelMessage>.hasStateAdvancingTool(): Boolean =
    any { it.isStateAdvancingToolResult() }

internal fun List<ModelMessage>.hasInvalidFinishResponseRetry(): Boolean =
    any {
        it.role == "tool" &&
            it.toolName == "finish" &&
            it.content.contains(INVALID_FINISH_RESPONSE_CODE)
    }

internal fun List<ModelMessage>.hasUnresolvedObligationRetry(): Boolean =
    any {
        it.role == "tool" &&
            it.toolName == "finish" &&
            it.content.contains(REQUIRED_ACTION_UNRESOLVED)
    }

internal fun List<ModelMessage>.hasEmptyCompletionContinue(): Boolean =
    any {
        it.role == "user" && it.content.contains(EMPTY_COMPLETION_CONTINUE_MARKER)
    }

internal fun hasStateAdvancingToolAfterLastTodoUpdate(messages: List<ModelMessage>): Boolean {
    val lastIndex = messages.indexOfLast { it.role == "tool" && it.toolName == "update_todo" }
    if (lastIndex < 0) return false
    return messages.drop(lastIndex + 1).any { it.isStateAdvancingToolResult() }
}

internal fun ModelMessage.isStateAdvancingToolResult(): Boolean =
    role == "tool" && toolName != null && toolName !in TODO_NEUTRAL_TOOL_NAMES

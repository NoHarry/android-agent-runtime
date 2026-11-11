package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.*

internal fun ThreadEvent.statusText(): String? = when (this) {
    is ThreadEvent.TurnStarted -> "Running"
    is ThreadEvent.TurnCompleted -> result.status.name
    is ThreadEvent.TurnFailed -> "Failed ${error.code}"
    is ThreadEvent.ItemStarted -> when (val value = item) {
        is Approval -> "Waiting confirmation"
        is ThreadItem.ToolCall -> "Tool running: ${value.tool}"
        else -> null
    }
    else -> null
}

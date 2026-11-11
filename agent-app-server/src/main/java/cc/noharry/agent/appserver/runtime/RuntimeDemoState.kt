package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.*
import cc.noharry.agent.appserver.protocol.RuntimeDemoState
import cc.noharry.agent.appserver.protocol.RuntimeFileChange
import cc.noharry.agent.appserver.protocol.RuntimeTokenUsage

internal fun RuntimeDemoState.reduce(event: ThreadEvent): RuntimeDemoState = when (event) {
    is ThreadEvent.TurnStarted -> copy(runId = event.turnId, status = "Running", finalStatus = null, errorCode = "")
    is ThreadEvent.UsageUpdated -> copy(tokenUsage = event.usage.let { RuntimeTokenUsage(it.inputTokens, it.outputTokens, it.reasoningTokens, it.cacheReadTokens, it.cacheWriteTokens) })
    is ThreadEvent.ItemStarted -> reduceItem(event.item, false)
    is ThreadEvent.ItemUpdated -> reduceItem(event.item, false)
    is ThreadEvent.ItemCompleted -> reduceItem(event.item, true)
    is ThreadEvent.TurnCompleted -> copy(runId = event.result.turnId, finalStatus = event.result.status.name, status = event.result.status.name,
        assistantPreview = event.result.finalResponse.takeLast(240), errorCode = event.result.error?.code.orEmpty())
    is ThreadEvent.TurnFailed -> copy(runId = event.turnId, status = "Failed", finalStatus = "FAILED", errorCode = event.error.code)
    else -> this
}

private fun RuntimeDemoState.reduceItem(item: ThreadItem, completed: Boolean): RuntimeDemoState = when (item) {
    is ThreadItem.AgentMessage -> copy(assistantPreview = item.text.takeLast(240))
    is ThreadItem.Reasoning -> copy(thinkingPreview = item.text.takeLast(240))
    is Approval -> copy(pendingConfirmationIds = if (completed) pendingConfirmationIds - item.id else (pendingConfirmationIds + item.id).distinct(), status = if (completed) "Running" else "Waiting confirmation")
    is ThreadItem.ToolCall -> copy(
        runningTools = if (completed) runningTools - item.tool else (runningTools + item.tool).distinct(),
        finishedTools = if (completed) (finishedTools + item.tool).takeLast(8) else finishedTools,
        status = item.network?.let { "Network ${it.decision}" } ?: status,
    )
    is ThreadItem.WebSearch -> copy(status = "Search activity")
    is ThreadItem.FileChange -> copy(changedFiles = (changedFiles + RuntimeFileChange(item.path, item.added, item.removed, false)).takeLast(8))
    is ThreadItem.Notice -> if (!completed) this else when (item.kind) {
        NoticeKind.RETRY -> copy(status = "Retrying", retryCount = retryCount + 1)
        NoticeKind.BUDGET_LOW -> copy(status = "Budget warning")
        NoticeKind.COMPACTION -> copy(status = "Compacted")
        NoticeKind.WORKSPACE_CHANGED -> copy(status = "Workspace selected", workspaceId = item.details.workspaceId ?: workspaceId)
    }
    is ThreadItem.UserMessage -> if (item.steer) copy(steeringPreview = item.input.filterIsInstance<InputPart.Text>().joinToString("\n") { it.text }) else this
    else -> this
}

internal fun RuntimeDemoState.visibleSignals(): List<String> = buildList {
    if (status.isNotBlank()) add("status")
    if (thinkingPreview.isNotBlank()) add("thinking")
    if (assistantPreview.isNotBlank()) add("assistant")
    if (pendingConfirmationIds.isNotEmpty()) add("confirmation")
    if (steeringPreview.isNotBlank()) add("steering")
    if (execOutputPreview.isNotBlank()) add("exec")
    if (workspaceId.isNotBlank()) add("workspace")
    if (retryCount > 0) add("retry")
    if (runningTools.isNotEmpty() || finishedTools.isNotEmpty()) add("tools")
    if (changedFiles.isNotEmpty()) add("diff")
    if (tokenUsage.inputTokens + tokenUsage.outputTokens > 0) add("usage")
    if (finalStatus != null) add("result")
    if (errorCode.isNotBlank()) add("error")
}

internal fun RuntimeDemoState.resolvePendingConfirmation(pendingId: String): RuntimeDemoState =
    copy(pendingConfirmationIds = pendingConfirmationIds - pendingId)

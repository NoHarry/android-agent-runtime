package cc.noharry.agent.appserver.session

import cc.noharry.agent.*
import cc.noharry.agent.appserver.protocol.*
import cc.noharry.agent.appserver.runtime.approvalSite
import cc.noharry.agent.appserver.runtime.redactToolPayload
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** [steerText] is what a steering bubble shows: the person's own words, which can differ from what the model read. */
internal fun projectSdkItem(
    item: ThreadItem,
    id: Long,
    mode: ConfirmationMode,
    previous: ConversationItem?,
    steerText: String? = null,
): ConversationItem? = when (item) {
    is ThreadItem.AgentMessage -> ConversationItem.MessageItem(id, TranscriptRole.ASSISTANT, item.text, final = item.phase == MessagePhase.FINAL)
    is ThreadItem.Reasoning -> ConversationItem.MessageItem(id, TranscriptRole.THINKING, item.text)
    is ThreadItem.UserMessage -> (steerText ?: item.input.filterIsInstance<InputPart.Text>().joinToString("\n") { it.text }).let { text ->
        // Said while the turn ran: a bubble inside that turn, not the start of a new one.
        if (item.steer) ConversationItem.TurnHeader(id, USER_STEERING_LABEL, text.take(60), text)
        else ConversationItem.TurnHeader(id, turnLabel(text), text.take(60), text)
    }
    is ThreadItem.ToolCall -> projectTool(item, id, previous as? ConversationItem.ToolCallItem)
    is ThreadItem.WebSearch -> ConversationItem.ToolCallItem(id, item.id, "web_search", buildJsonObject { put("query", item.query) }.toString(), buildJsonObject { put("provider", item.provider); item.resultCount?.let { put("resultCount", it) } }.toString(), ToolCallStatus.DONE, System.currentTimeMillis(), System.currentTimeMillis())
    is ThreadItem.FileChange -> ConversationItem.ToolCallItem(id, item.id, item.tool, item.path, "+${item.added} -${item.removed}", ToolCallStatus.DONE, System.currentTimeMillis(), System.currentTimeMillis())
    is ThreadItem.Notice -> ConversationItem.MessageItem(id, TranscriptRole.STATUS, item.message)
    is ThreadItem.Error -> ConversationItem.MessageItem(id, TranscriptRole.ERROR, "${item.code}: ${item.message}")
    is Question -> ConversationItem.MessageItem(id, TranscriptRole.ASSISTANT, item.text)
    is Approval -> ConversationItem.ConfirmationItem(
        id, item.id, approvalSite(item.domain), mode, approvalStatus(item.state),
        (previous as? ConversationItem.ConfirmationItem)?.createdAt ?: System.currentTimeMillis(),
        if (item.state == ApprovalState.PENDING) null else System.currentTimeMillis(),
        alwaysAsk = item.alwaysAsk,
    )
}

private fun projectTool(item: ThreadItem.ToolCall, id: Long, previous: ConversationItem.ToolCallItem?): ConversationItem.ToolCallItem =
    ConversationItem.ToolCallItem(
        id = id, toolCallId = item.id, toolName = item.tool,
        argumentsJson = redactToolPayload(item.arguments), resultJson = item.output?.let(::redactToolPayload),
        status = when (item.status) {
            ItemStatus.IN_PROGRESS -> ToolCallStatus.RUNNING
            ItemStatus.COMPLETED -> ToolCallStatus.DONE
            ItemStatus.FAILED, ItemStatus.DECLINED -> ToolCallStatus.FAILED
        },
        startedAt = previous?.startedAt ?: System.currentTimeMillis(),
        finishedAt = if (item.status == ItemStatus.IN_PROGRESS) null else System.currentTimeMillis(),
    )

private fun approvalStatus(state: ApprovalState): ConfirmationStatus = when (state) {
    ApprovalState.PENDING -> ConfirmationStatus.PENDING
    ApprovalState.APPROVED -> ConfirmationStatus.APPROVED
    ApprovalState.DENIED -> ConfirmationStatus.DENIED
    ApprovalState.EXPIRED -> ConfirmationStatus.EXPIRED
    ApprovalState.CANCELLED -> ConfirmationStatus.FAILED
}

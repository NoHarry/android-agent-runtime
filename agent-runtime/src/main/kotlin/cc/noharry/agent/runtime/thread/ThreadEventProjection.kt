@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.thread

import cc.noharry.agent.*
import cc.noharry.agent.spi.AgentEvent
import cc.noharry.agent.spi.PendingConfirmation
import cc.noharry.agent.spi.PostUserInputRequest
import cc.noharry.agent.spi.SessionEventPayload
import cc.noharry.agent.spi.reliability.RuntimeResult
import cc.noharry.agent.spi.reliability.RuntimeRunStatus

/** The single writer of a turn's item lifecycle, including terminal cleanup. */
internal class ThreadEventProjection(
    private val threadId: String,
    val turnId: String,
    private val input: TurnInput,
    private val approval: (PendingConfirmation) -> Approval,
    private val emit: suspend (ThreadEvent) -> Unit,
) {
    private val records = linkedMapOf<String, ThreadItem>()
    private val open = linkedSetOf<String>()
    private var sequence = 0
    private var started = false
    private var message: String? = null
    private var reasoning: String? = null
    private val tools = ThreadToolProjection(turnId, ::nextId, ::start, ::update, ::complete, emit)
    var result: TurnResult? = null
        private set
    private var usage: Usage? = null

    fun items(): List<ThreadItem> = records.values.toList()
    private fun nextId(): String = "$turnId:item:${++sequence}"

    suspend fun begin() {
        if (started) return
        started = true
        emit(ThreadEvent.TurnStarted(threadId, turnId))
        val item = ThreadItem.UserMessage(nextId(), turnId, input.parts)
        start(item)
        complete(item)
    }

    suspend fun accept(event: AgentEvent) {
        if (result != null) return
        begin()
        when (event) {
            is AgentEvent.Started, is AgentEvent.EmptyCompletionObserved -> Unit
            is AgentEvent.TextDelta -> text(event.text, false)
            is AgentEvent.ThinkingDelta -> text(event.text, true)
            is AgentEvent.TokenUsageDelta -> addUsage(event)
            is AgentEvent.NativeToolCallStarted -> { finishText(); tools.started(event) }
            is AgentEvent.NativeToolCallFinished -> tools.finished(event)
            is AgentEvent.NetworkToolActivity -> tools.network(event)
            is AgentEvent.ExecOutputDelta -> tools.output(event.text)
            is AgentEvent.SearchActivity -> instant(ThreadItem.WebSearch(nextId(), turnId, event.provider, event.queryPreview, event.resultCount))
            is AgentEvent.WorkspaceFileChanged -> instant(ThreadItem.FileChange(nextId(), turnId, event.path, event.addedLines, event.removedLines, event.toolName))
            is AgentEvent.WorkspaceChanged -> notice(NoticeKind.WORKSPACE_CHANGED, event.workspaceId)
            is AgentEvent.RetryAttempted -> notice(NoticeKind.RETRY, "Retry ${event.attempt} in ${event.delayMillis} ms")
            is AgentEvent.BudgetWarning -> notice(NoticeKind.BUDGET_LOW, "${event.roundsRemaining} rounds, ${event.toolCallsRemaining} tool calls remaining")
            is AgentEvent.CompactionApplied -> notice(NoticeKind.COMPACTION, "History compacted from ${event.beforeMessageCount} to ${event.afterMessageCount} messages")
            is AgentEvent.UserInteraction -> interaction(event.interaction)
            is AgentEvent.SteeringAccepted -> Unit // The full canonical INPUT fact carries the message.
            is AgentEvent.ConfirmationRequired -> { finishText(); start(approval(event.pending)) }
            is AgentEvent.RuntimeBlocked -> blocked(event)
            is AgentEvent.ResultAvailable -> finish(event.result)
            is AgentEvent.Failed -> fail(TurnError(event.code, event.message, event.recoverable))
            is AgentEvent.Finished -> finish(RuntimeResult(turnId, RuntimeRunStatus.COMPLETED, ""))
        }
    }

    suspend fun beginApproval(item: Approval) { finishText(); start(item) }

    suspend fun toolDecision(callId: String, policy: ApprovalPolicy) { tools.decision(callId, policy) }

    suspend fun resolveApproval(item: Approval) { complete(item) }

    /** Inputs accepted before cancellation or an approval failure still belong to this turn's transcript. */
    suspend fun retainSteeringInputs(inputs: List<PostUserInputRequest>) {
        for (input in inputs) interaction(SessionEventPayload.UserInteraction(
            "INPUT", "RECEIVED", inputRequestId = input.requestId, text = input.text,
        ))
        result = result?.copy(items = items())
    }

    private suspend fun text(value: String, thinking: Boolean) {
        if (thinking) {
            finishMessage()
            val id = reasoning ?: nextId().also {
                reasoning = it
                start(ThreadItem.Reasoning(it, turnId, ""))
            }
            val current = records.getValue(id) as ThreadItem.Reasoning
            records[id] = current.copy(text = current.text + value)
            emit(ThreadEvent.ItemDelta(id, value))
        } else {
            finishReasoning()
            val id = message ?: nextId().also {
                message = it
                start(ThreadItem.AgentMessage(it, turnId, ""))
            }
            val current = records.getValue(id) as ThreadItem.AgentMessage
            records[id] = current.copy(text = current.text + value)
            emit(ThreadEvent.ItemDelta(id, value))
        }
    }

    private suspend fun finishMessage(final: String? = null, status: ItemStatus = ItemStatus.COMPLETED) {
        val current = message?.let { records[it] as? ThreadItem.AgentMessage }
        if (current != null) {
            val isFinal = final != null && (current.text.trim() == final.trim() || final.isEmpty())
            complete(current.copy(
                text = if (isFinal && !final.isNullOrEmpty()) final else current.text,
                phase = if (isFinal) MessagePhase.FINAL else MessagePhase.COMMENTARY, status = status,
            ))
            message = null
            if (isFinal) return
        }
        if (!final.isNullOrEmpty()) {
            instant(ThreadItem.AgentMessage(nextId(), turnId, final, MessagePhase.FINAL, status))
        }
    }

    private suspend fun finishReasoning(status: ItemStatus = ItemStatus.COMPLETED) {
        reasoning?.let { complete((records.getValue(it) as ThreadItem.Reasoning).copy(status = status)) }
        reasoning = null
    }

    private suspend fun finishText() { finishMessage(); finishReasoning() }

    private suspend fun interaction(fact: SessionEventPayload.UserInteraction) {
        if (fact.kind == "INPUT") {
            val id = "$turnId:input:${fact.inputRequestId}"
            if (fact.state == "RECEIVED" && id !in records) {
                instant(ThreadItem.UserMessage(id, turnId, listOf(InputPart.Text(fact.text)), steer = true, requestId = fact.inputRequestId))
            }
            return
        }
        if (fact.kind != "QUESTION") return
        val id = "$turnId:question:${fact.questionId}"
        if (fact.state == "PENDING") {
            if (records.containsKey(id)) return
            start(Question(id, turnId, fact.text, fact.options, async = true, status = ItemStatus.IN_PROGRESS))
        } else if (fact.state == "ANSWER_RECEIVED") {
            // Receipt precedes consumption by the next model request; keep the request open.
            (records[id] as? Question)?.let { update(it) }
        } else if (fact.state == "ANSWERED" || fact.state == "EXPIRED") {
            val item = records[id] as? Question ?: return
            complete(item.copy(status = if (fact.state == "ANSWERED") ItemStatus.COMPLETED else ItemStatus.DECLINED))
        }
    }

    private suspend fun blocked(event: AgentEvent.RuntimeBlocked) {
        if (event.blocker.code != "WAITING_USER_INPUT") {
            fail(TurnError(event.blocker.code, event.blocker.message, true))
            return
        }
        finishText()
        val question = Question(nextId(), turnId, event.blocker.message)
        instant(question)
        closeItems(ItemStatus.COMPLETED)
        publish(TurnResult(turnId, TurnStatus.WAITING_FOR_ANSWER, question.text, items(), usage, question))
    }

    private suspend fun addUsage(event: AgentEvent.TokenUsageDelta) {
        val previous = usage ?: Usage()
        usage = Usage(
            total(previous.inputTokens, event.inputTokens), total(previous.outputTokens, event.outputTokens),
            total(previous.reasoningTokens, event.reasoningTokens), total(previous.cacheReadTokens, event.cacheReadTokens),
            total(previous.cacheWriteTokens, event.cacheWriteTokens),
        )
        emit(ThreadEvent.UsageUpdated(turnId, usage!!))
    }

    suspend fun finish(value: RuntimeResult) {
        if (result != null) return
        if (value.status != RuntimeRunStatus.COMPLETED) {
            fail(TurnError(value.errorCode ?: "MODEL_LOOP_FAILED", value.response, value.recoverable))
            return
        }
        finishReasoning()
        finishMessage(value.response)
        closeItems(ItemStatus.COMPLETED)
        publish(TurnResult(turnId, TurnStatus.COMPLETED, value.response, items(), usage))
    }

    suspend fun fail(error: TurnError, interrupted: Boolean = false) {
        if (result != null) return
        begin()
        finishReasoning(ItemStatus.FAILED)
        finishMessage(status = ItemStatus.FAILED)
        closeItems(ItemStatus.FAILED)
        val status = if (interrupted) TurnStatus.INTERRUPTED else TurnStatus.FAILED
        publish(TurnResult(turnId, status, "", items(), usage, error = error))
    }

    private suspend fun publish(value: TurnResult) {
        result = value
        if (value.status == TurnStatus.FAILED) emit(ThreadEvent.TurnFailed(turnId, value.error!!))
        else emit(ThreadEvent.TurnCompleted(value))
    }

    private suspend fun closeItems(status: ItemStatus) {
        for (id in open.toList()) {
            val item = records.getValue(id)
            val terminal = when (item) {
                is ThreadItem.ToolCall -> item.copy(status = if (status == ItemStatus.COMPLETED) ItemStatus.FAILED else status)
                is Question -> item.copy(status = ItemStatus.DECLINED)
                is Approval -> ApprovalSnapshot(item, ItemStatus.DECLINED)
                else -> item
            }
            complete(terminal)
        }
    }

    private suspend fun start(item: ThreadItem) {
        check(!records.containsKey(item.id)) { "Duplicate item id: ${item.id}" }
        records[item.id] = item
        open += item.id
        emit(ThreadEvent.ItemStarted(item))
    }

    private suspend fun update(item: ThreadItem) {
        if (item.id !in open) return
        records[item.id] = item
        emit(ThreadEvent.ItemUpdated(item))
    }

    private suspend fun complete(item: ThreadItem) {
        if (!open.remove(item.id)) return
        records[item.id] = item
        emit(ThreadEvent.ItemCompleted(item))
    }

    private suspend fun instant(item: ThreadItem) { start(item); complete(item) }
    private suspend fun notice(kind: NoticeKind, message: String) = instant(ThreadItem.Notice(nextId(), turnId, kind, message))
}

private fun total(a: Int, b: Int): Int = (a.toLong() + b.coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

internal class ApprovalSnapshot(private val original: Approval, override val status: ItemStatus) : Approval() {
    override val state get() = if (status == ItemStatus.DECLINED && original.state == ApprovalState.PENDING) ApprovalState.CANCELLED else original.state
    override val id get() = original.id
    override val turnId get() = original.turnId
    override val expiresAt get() = original.expiresAt
    override val tool get() = original.tool
    override val arguments get() = original.arguments
    override val domain get() = original.domain
    override val reason get() = original.reason
    override suspend fun approve(scope: ApprovalScope) = original.approve(scope)
    override suspend fun deny(reason: String?) = original.deny(reason)
}

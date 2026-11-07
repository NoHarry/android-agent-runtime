@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.thread

import cc.noharry.agent.*
import cc.noharry.agent.spi.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

internal class DefaultAgentThread(
    private val runtime: DefaultThreadRuntime,
    private val initial: ThreadRecord,
) : AgentThread {
    override val id = initial.id
    private val execution = runtime.store.executionSlot(id)
    private val generation = initial.generation
    private var active: ActiveThreadTurn?
        get() = execution.active?.takeIf { it.generation == generation }
        set(value) { execution.active = value }
    @Volatile private var defaults: ThreadOptions = initial.options

    internal fun isCurrent(): Boolean = runtime.store.isCurrent(id, generation)
    private fun checkCurrent() { check(isCurrent()) { "Thread was deleted" } }

    internal fun configure(options: ThreadOptions) {
        runtime.store.updateOptions(id, options, generation)
        defaults = options
    }

    override suspend fun run(input: TurnInput, options: TurnOptions): TurnResult {
        var result: TurnResult? = null
        val items = linkedMapOf<String, ThreadItem>()
        var usage: Usage? = null
        runStreamed(input, options).collect { event ->
            when (event) {
                is ThreadEvent.TurnCompleted -> result = event.result
                is ThreadEvent.ItemCompleted -> items[event.item.id] = event.item
                is ThreadEvent.UsageUpdated -> usage = event.usage
                is ThreadEvent.TurnFailed -> result = TurnResult(
                    event.turnId, TurnStatus.FAILED, "", items.values.toList(), usage, error = event.error,
                )
                else -> Unit
            }
        }
        return checkNotNull(result) { "Turn did not produce a result" }
    }

    override fun runStreamed(input: TurnInput, options: TurnOptions): Flow<ThreadEvent> {
        runtime.checkOpen()
        checkCurrent()
        val collected = AtomicBoolean()
        return channelFlow {
            check(collected.compareAndSet(false, true)) { "A turn flow can only be collected once" }
            val turnId = options.turnId ?: UUID.randomUUID().toString()
            require(turnId.isNotBlank()) { "Turn id must not be blank" }
            runtime.checkOpen()
            checkCurrent()
            runtime.store.turn(id, turnId, generation)?.let { replay(it); return@channelFlow }
            if (options.onConflict == SessionRunConflictPolicy.CANCEL_AND_REPLACE) interrupt()
            val busyPolicy = options.onConflict == SessionRunConflictPolicy.RESUME_CURRENT && active?.id != turnId
            when (val admitted = runtime.admission.enter(runtime, id, generation, busyPolicy)) {
                is ThreadAdmissionResult.Granted -> collectAdmitted(input, options, turnId, admitted.ticket)
                is ThreadAdmissionResult.Rejected -> reject(input.snapshot(), turnId, admitted)
            }
        }
    }

    private suspend fun ProducerScope<ThreadEvent>.collectAdmitted(
        input: TurnInput, options: TurnOptions, turnId: String, ticket: ThreadTicket,
    ) {
        val completed = try { collectTurn(input, options, turnId) } finally { runtime.admission.release(ticket) }
        if (completed.mailbox == null) replay(completed.result)
        else try {
            withContext(NonCancellable) {
                try { runtime.engine.notifyTurnFinished(completed.result, id) } catch (_: CancellationException) { }
            }
            completed.mailbox.emit(completed.result.terminalEvent(), awaitDelivery = false)
        } finally { completed.mailbox.close() }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun ProducerScope<ThreadEvent>.collectTurn(input: TurnInput, options: TurnOptions, turnId: String): FinishedThreadTurn {
        stoppedBeforeExecution(input, turnId)?.let { return it }
        val previous = try { runtime.store.turn(id, turnId, generation) } catch (error: IllegalStateException) {
            stoppedBeforeExecution(input, turnId)?.let { return it }
            throw error
        }
        return if (previous != null) FinishedThreadTurn(previous) else execute(input.snapshot(), options, turnId)
    }

    /** A granted ticket can be invalidated before its continuation installs the active execution. */
    private fun stoppedBeforeExecution(input: TurnInput, turnId: String): FinishedThreadTurn? {
        val error = when {
            !runtime.isOpen() -> TurnError("RUNTIME_CLOSED", "Runtime is closed.")
            !isCurrent() -> TurnError("THREAD_DELETED", "The thread was deleted.")
            else -> return null
        }
        val item = ThreadItem.UserMessage("$turnId:item:1", turnId, input.snapshot().parts)
        return FinishedThreadTurn(TurnResult(turnId, TurnStatus.FAILED, "", listOf(item), error = error))
    }

    private suspend fun ProducerScope<ThreadEvent>.reject(input: TurnInput, turnId: String, rejection: ThreadAdmissionResult.Rejected) {
        val projection = ThreadEventProjection(id, turnId, input, { error("No approval on a rejected turn") }) { send(it) }
        projection.fail(TurnError(rejection.code, rejection.message, true))
    }

    private suspend fun ProducerScope<ThreadEvent>.execute(input: TurnInput, options: TurnOptions, turnId: String): FinishedThreadTurn {
        val mailbox = ThreadEventMailbox(this)
        val turn = ActiveThreadTurn(turnId, defaults, runtime, generation)
        val projection = ThreadEventProjection(id, turnId, input, { error("Approval must be handled in the active turn") }) { event ->
            turn.items = turn.projection?.items().orEmpty()
            if (event !is ThreadEvent.TurnCompleted && event !is ThreadEvent.TurnFailed) {
                mailbox.emit(event, awaitDelivery = !turn.finalizing)
            }
        }
        turn.projection = projection
        val job = launch(ThreadExecutionContext(runtime.store.coordination, id, turn), start = CoroutineStart.LAZY) { executeTurn(turn, input, options) }
        turn.job = job
        active = turn
        try {
            try { job.start(); job.join() } catch (_: CancellationException) { turn.cancel() }
            return withContext(NonCancellable) {
                job.join()
                FinishedThreadTurn(finalizeTurn(turn), mailbox)
            }
        } catch (failure: Throwable) {
            mailbox.close()
            throw failure
        } finally {
            withContext(NonCancellable) { if (!job.isCompleted) turn.cancel(); job.join() }
            active = null
        }
    }

    private suspend fun finalizeTurn(turn: ActiveThreadTurn): TurnResult {
        val projection = checkNotNull(turn.projection)
        turn.finalizing = true
        if (projection.result == null) {
            val error = turn.cancellation ?: TurnError("MISSING_TURN_RESULT", "The model stopped without completing the turn.")
            projection.fail(error, interrupted = error.code == "INTERRUPTED")
        }
        val result = checkNotNull(projection.result)
        if (!isCurrent()) return result.failed(TurnError("THREAD_DELETED", "The thread was deleted."))
        return try {
            withContext(Dispatchers.IO) { runtime.store.saveTurn(id, result, generation) }
            if (isCurrent()) result else result.failed(TurnError("THREAD_DELETED", "The thread was deleted."))
        } catch (_: Exception) {
            result.failed(TurnError("STORAGE_FAILED", "The turn could not be saved."))
        }
    }

    private suspend fun executeTurn(turn: ActiveThreadTurn, input: TurnInput, options: TurnOptions) {
        val projection = checkNotNull(turn.projection)
        try {
            projection.begin()
            val record = checkNotNull(runtime.store.load(id, generation)) { "Thread was deleted" }.copy(options = turn.options)
            if (input.answering != null && input.answering != record.pendingQuestion?.id) {
                throw ThreadInputException("QUESTION_NOT_FOUND", "The question is not pending in this thread.")
            }
            val resolved = resolveThreadAttachments(input) { runtime.engine.readThreadAttachment(id, it) }
            val request = threadRequest(record, resolved, options, turn.id)
            if (request.inputImages.isNotEmpty() && (!runtime.imageInputEnabled || !runtime.supportsImages(request.modelRef))) {
                projection.fail(TurnError("IMAGE_INPUT_UNSUPPORTED", "The selected model does not accept image input."))
                return
            }
            runtime.engine.runThread(request) { pending -> approve(turn, pending, options) }.collect(projection::accept)
            if (projection.result == null) projection.fail(TurnError("MISSING_TURN_RESULT", "The model stopped without completing the turn."))
        } catch (_: CancellationException) {
            turn.approval?.cancel()
            turn.finalizing = true
            val error = turn.cancellation ?: TurnError("INTERRUPTED", "The turn was interrupted.")
            withContext(NonCancellable) { projection.fail(error, interrupted = error.code == "INTERRUPTED") }
        } catch (error: ThreadInputException) {
            projection.fail(TurnError(error.code, error.message ?: "Invalid turn input."))
        } catch (error: Exception) {
            projection.fail(TurnError("TURN_FAILED", error.message ?: "The turn failed."))
        } finally { turn.approval?.cancel() }
    }

    private suspend fun approve(turn: ActiveThreadTurn, pending: PendingConfirmation, options: TurnOptions): ApprovalState {
        val projection = checkNotNull(turn.projection)
        val policy = options.approvalPolicy ?: turn.options.approvalPolicy ?: ApprovalPolicy.ASK
        val domain = approvalDomain(pending.toolName.orEmpty(), pending.argumentsJson.orEmpty())
        val automatic = when (policy) {
            ApprovalPolicy.APPROVE_ALL -> ApprovalState.APPROVED
            ApprovalPolicy.DENY_ALL -> ApprovalState.DENIED
            ApprovalPolicy.REMEMBER -> if (domain in runtime.store.rememberedDomains(id)) ApprovalState.APPROVED else null
            ApprovalPolicy.ASK -> null
        }
        if (automatic != null) {
            projection.toolDecision(pending.idempotencyKey, policy)
            return automatic
        }
        val approval = ThreadApproval(pending, runtime.nowEpochMillis() + runtime.confirmationTimeoutMillis, runtime.nowEpochMillis)
        turn.approval = approval
        try {
            projection.beginApproval(approval)
            val decision = approval.await()
            if (decision == ApprovalState.APPROVED && (policy == ApprovalPolicy.REMEMBER || approval.scope == ApprovalScope.THREAD)) {
                withContext(Dispatchers.IO) { runtime.store.rememberDomain(id, domain, generation) }
            }
            projection.toolDecision(pending.idempotencyKey, policy)
            projection.resolveApproval(approval.snapshot())
            return decision
        } finally { approval.cancel(); turn.approval = null }
    }

    override suspend fun steer(text: String): SteerResult = steer(text, null)

    override suspend fun steer(text: String, answering: String?): SteerResult {
        checkCurrent()
        if (text.isBlank()) return SteerResult.EMPTY_INPUT
        val turn = active ?: return if (answering == null) SteerResult.NOT_RUNNING else SteerResult.QUESTION_NOT_FOUND
        val questions = turn.items.filterIsInstance<Question>().filter { it.async && it.status == ItemStatus.IN_PROGRESS }
        val question = if (answering == null) questions.lastOrNull() else questions.firstOrNull { it.id == answering }
        if (answering != null && question == null) return SteerResult.QUESTION_NOT_FOUND
        val result = turn.owner.engine.postUserInput(PostUserInputRequest(turn.id, UUID.randomUUID().toString(), text, id,
            question?.id?.removePrefix("${turn.id}:question:")))
        return when (result) {
            PostUserInputResult.Accepted, PostUserInputResult.Duplicate -> SteerResult.ACCEPTED
            is PostUserInputResult.Rejected -> when (result.code) {
                "INPUT_LIMIT" -> SteerResult.QUEUE_FULL
                "QUESTION_NOT_FOUND", "QUESTION_ALREADY_ANSWERED" -> SteerResult.QUESTION_NOT_FOUND
                else -> SteerResult.NOT_RUNNING
            }
            else -> SteerResult.NOT_RUNNING
        }
    }

    override suspend fun interrupt() { checkCurrent(); active?.cancel() }
    internal fun cancel() { active?.takeIf { it.owner === runtime }?.cancel() }
    override suspend fun status(): ThreadStatus {
        checkCurrent()
        val turn = active
        return when {
            turn?.approval != null -> ThreadStatus.WAITING_FOR_APPROVAL
            turn != null -> ThreadStatus.RUNNING
            else -> runtime.store.load(id, generation)?.status ?: ThreadStatus.IDLE
        }
    }

    override suspend fun pendingRequests(): List<PendingRequest> {
        checkCurrent()
        val turn = active
        if (turn != null) return listOfNotNull(turn.approval) + turn.items.filterIsInstance<Question>().filter { it.async && it.status == ItemStatus.IN_PROGRESS }
        return listOfNotNull(runtime.store.load(id, generation)?.pendingQuestion)
    }

    override suspend fun items(afterTurnId: String?): List<ThreadItem> = withContext(Dispatchers.IO) {
        runtime.store.items(id, afterTurnId, generation) + active?.items.orEmpty().filter { current ->
            runtime.store.turn(id, current.turnId, generation) == null
        }
    }

    private suspend fun ProducerScope<ThreadEvent>.replay(result: TurnResult) {
        send(ThreadEvent.TurnStarted(id, result.turnId))
        result.items.forEach { send(ThreadEvent.ItemStarted(it)); send(ThreadEvent.ItemCompleted(it)) }
        result.usage?.let { send(ThreadEvent.UsageUpdated(result.turnId, it)) }
        if (result.status == TurnStatus.FAILED) send(ThreadEvent.TurnFailed(result.turnId, checkNotNull(result.error)))
        else send(ThreadEvent.TurnCompleted(result))
    }
}

private data class FinishedThreadTurn(val result: TurnResult, val mailbox: ThreadEventMailbox? = null)

private fun TurnResult.failed(error: TurnError): TurnResult = copy(
    status = TurnStatus.FAILED, finalResponse = "", pending = null, error = error,
)

private fun TurnResult.terminalEvent(): ThreadEvent =
    if (status == TurnStatus.FAILED) ThreadEvent.TurnFailed(turnId, checkNotNull(error)) else ThreadEvent.TurnCompleted(this)

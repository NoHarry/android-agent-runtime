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
                is ThreadEvent.TurnFailed -> result = (if (isCurrent()) runtime.store.turn(id, event.turnId, generation) else null) ?: TurnResult(
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
                is ThreadAdmissionResult.Granted -> try { collectTurn(input, options, turnId) }
                    finally { runtime.admission.release(admitted.ticket) }
                is ThreadAdmissionResult.Rejected -> reject(input.snapshot(), turnId, admitted)
            }
        }
    }

    private suspend fun ProducerScope<ThreadEvent>.collectTurn(input: TurnInput, options: TurnOptions, turnId: String) {
        runtime.checkOpen()
        checkCurrent()
        val previous = runtime.store.turn(id, turnId, generation)
        if (previous != null) replay(previous) else execute(input.snapshot(), options, turnId)
    }

    private suspend fun ProducerScope<ThreadEvent>.reject(input: TurnInput, turnId: String, rejection: ThreadAdmissionResult.Rejected) {
        val projection = ThreadEventProjection(id, turnId, input, { error("No approval on a rejected turn") }) { send(it) }
        projection.fail(TurnError(rejection.code, rejection.message, true))
    }

    private suspend fun ProducerScope<ThreadEvent>.execute(input: TurnInput, options: TurnOptions, turnId: String) {
        val scope = this
        val turn = ActiveThreadTurn(turnId, defaults, runtime, generation)
        val projection = ThreadEventProjection(id, turnId, input, { error("Approval must be handled in the active turn") }) { event ->
            if (event is ThreadEvent.TurnCompleted || event is ThreadEvent.TurnFailed) {
                turn.projection?.result?.let {
                    withContext(Dispatchers.IO) { runtime.store.saveTurn(id, it, generation) }
                    runtime.engine.notifyTurnFinished(it, id)
                }
            }
            turn.items = turn.projection?.items().orEmpty()
            if (scope.isActive && isCurrent() && !turn.suppressDelivery) send(event)
        }
        turn.projection = projection
        val job = launch(ThreadExecutionContext(runtime.store.coordination, id, turn), start = CoroutineStart.LAZY) { executeTurn(turn, input, options) }
        turn.job = job
        active = turn
        try { job.start(); job.join() } finally {
            withContext(NonCancellable) {
                if (!scope.isActive) job.cancel()
                job.join()
            }
            active = null
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
        } catch (cancelled: CancellationException) {
            turn.approval?.cancel()
            withContext(NonCancellable) { projection.fail(TurnError("INTERRUPTED", "The turn was interrupted."), interrupted = true) }
        } catch (error: ThreadInputException) {
            projection.fail(TurnError(error.code, error.message ?: "Invalid turn input."))
        } catch (error: Exception) {
            projection.fail(TurnError("TURN_FAILED", error.message ?: "The turn failed."))
        } finally {
            turn.approval?.cancel()
            withContext(NonCancellable + Dispatchers.IO) { projection.result?.let { runtime.store.saveTurn(id, it, generation) } }
        }
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

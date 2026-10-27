@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.kernel.api.PostUserInputRequest
import cc.noharry.agent.kernel.api.PostUserInputResult
import cc.noharry.agent.kernel.api.SessionEventPayload.UserInteraction
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.kernel.core.trace.LoopSessionRecorder

/**
 * One input owner per run. Each run has its own lock, so host input for one session never waits on
 * another session's recorder; admission and normal terminal sealing share that per-run lock, and the
 * canonical RECEIVED fact is appended before the input becomes drainable. Facts are also queued for
 * the loop to project onto the public event stream at its next safe point.
 */
class RuntimeSteeringQueue(
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val runs = linkedMapOf<String, InputRun>()

    /** Admits a run before its loop attaches a recorder; facts are buffered until [open]. */
    fun admit(runId: String, sessionId: String) {
        synchronized(runs) { runs.getOrPut(runId) { InputRun(sessionId) } }
    }

    fun open(context: MaterializerContext, recorder: LoopSessionRecorder) {
        val run = synchronized(runs) { runs.getOrPut(context.runId) { InputRun(context.sessionId) } }
        synchronized(run) {
            run.recorder = { recorder.recordInteraction(context, it) }
            run.buffered.forEach { run.record(it, public = false) }
            run.buffered.clear()
            run.open = true
        }
    }

    fun pause(runId: String) = withRun(runId) { open = false }

    fun post(request: PostUserInputRequest): PostUserInputResult {
        val run = find(request.runId) ?: return PostUserInputResult.NotFound
        return synchronized(run) {
            if (request.sessionId != null && request.sessionId != run.sessionId) return@synchronized rejected("SESSION_MISMATCH")
            if (request.requestId.isBlank() || request.text.isBlank() || request.text.length > MAX_INPUT_CHARS) {
                return@synchronized rejected("INVALID_INPUT")
            }
            run.accepted[request.requestId]?.let { previous ->
                return@synchronized if (previous.text == request.text && previous.questionId == request.questionId) {
                    PostUserInputResult.Duplicate
                } else rejected("REQUEST_ID_CONFLICT")
            }
            if (!run.open) return@synchronized PostUserInputResult.RunNotActive
            val question = request.questionId?.let { run.questions[it] ?: return@synchronized rejected("QUESTION_NOT_FOUND") }
            if (question != null && question.state != "PENDING") return@synchronized rejected("QUESTION_ALREADY_ANSWERED")
            if (run.pending.size >= MAX_PENDING_INPUTS || run.accepted.size >= MAX_RUN_INPUTS) return@synchronized rejected("INPUT_LIMIT")
            val input = RuntimeSteeringInput(request.runId, request.requestId, request.text, nowEpochMillis(), request.questionId, question?.text)
            run.record(input.fact("RECEIVED"))
            run.accepted[request.requestId] = input
            run.pending.addLast(input)
            if (question != null) {
                val received = question.copy(state = "ANSWER_RECEIVED", inputRequestId = request.requestId)
                run.questions[question.questionId] = received
                run.record(received)
            }
            PostUserInputResult.Accepted
        }
    }

    fun drain(runId: String): List<RuntimeSteeringInput> = withRun(runId) {
        pending.toList().also { pending.clear(); inFlight.addAll(it) }
    } ?: emptyList()

    /** Marks inputs of a completed model request as consumed; the question becomes ANSWERED. */
    fun consumed(input: RuntimeSteeringInput) = withRun(input.runId) {
        if (!inFlight.remove(input)) return@withRun
        record(input.fact("CONSUMED"))
        input.questionId?.let { id ->
            questions[id]?.copy(state = "ANSWERED")?.also { questions[id] = it; record(it) }
        }
    }

    fun inFlight(runId: String): List<RuntimeSteeringInput> = withRun(runId) { inFlight.toList() } ?: emptyList()

    fun questions(runId: String, callId: String, questions: List<UserInteraction>): List<UserInteraction> {
        val run = checkNotNull(find(runId)) { "Run is not active" }
        return synchronized(run) {
            check(run.open) { "Run is not active" }
            check(run.questions.size + questions.size <= MAX_RUN_QUESTIONS) { "Question limit reached" }
            val identified = questions.mapIndexed { index, question -> question.copy(questionId = "$runId/$callId/$index") }
            check(identified.none { it.questionId in run.questions }) { "Question call id already used" }
            identified.forEach {
                run.record(it)
                run.questions[it.questionId] = it
            }
            identified
        }
    }

    /** False keeps the loop alive to consume input accepted during its last model request. */
    fun sealIfIdle(runId: String): Boolean = withRun(runId) {
        if (pending.isNotEmpty()) return@withRun false
        open = false
        true
    } ?: true

    /** Closes admission; unconsumed input and open questions are recorded with [reason]. Idempotent. */
    fun close(runId: String, reason: String) = withRun(runId) {
        open = false
        (pending + inFlight).forEach { record(it.fact("UNCONSUMED", reason)) }
        pending.clear()
        inFlight.clear()
        questions.values.filter { it.state == "PENDING" || it.state == "ANSWER_RECEIVED" }.forEach {
            val expired = it.copy(state = "EXPIRED", reason = reason)
            record(expired)
            questions[it.questionId] = expired
        }
    } ?: Unit

    /** Facts recorded since the last take, in canonical order, for the loop's public projection. */
    fun takePublicFacts(runId: String): List<UserInteraction> = withRun(runId) {
        publicPending.toList().also { publicPending.clear() }
    } ?: emptyList()

    /** Forgets the run; [reason] labels anything still open, so pass the terminal status when it is known. */
    fun clear(runId: String, reason: String = "RUN_CLOSED") {
        close(runId, reason)
        synchronized(runs) { runs.remove(runId) }
    }

    private fun find(runId: String): InputRun? = synchronized(runs) { runs[runId] }

    private inline fun <T> withRun(runId: String, block: InputRun.() -> T): T? {
        val run = find(runId) ?: return null
        return synchronized(run) { run.block() }
    }

    private class InputRun(val sessionId: String) {
        var open = true
        var recorder: ((UserInteraction) -> Unit)? = null
        val buffered = mutableListOf<UserInteraction>()
        val publicPending = mutableListOf<UserInteraction>()
        val accepted = linkedMapOf<String, RuntimeSteeringInput>()
        val pending = ArrayDeque<RuntimeSteeringInput>()
        val inFlight = mutableListOf<RuntimeSteeringInput>()
        val questions = linkedMapOf<String, UserInteraction>()

        fun record(fact: UserInteraction, public: Boolean = true) {
            recorder?.invoke(fact) ?: buffered.add(fact)
            if (public) publicPending += fact
        }
    }

    private fun rejected(code: String) = PostUserInputResult.Rejected(code)

    private companion object {
        const val MAX_INPUT_CHARS = 16_384
        const val MAX_PENDING_INPUTS = 64
        const val MAX_RUN_INPUTS = 256
        const val MAX_RUN_QUESTIONS = 32
    }
}

data class RuntimeSteeringInput(
    val runId: String,
    val requestId: String,
    val text: String,
    val createdAtEpochMillis: Long,
    val questionId: String? = null,
    val questionText: String? = null,
) {
    fun fact(state: String, reason: String = "") = UserInteraction(
        kind = "INPUT", state = state, inputRequestId = requestId,
        questionId = questionId.orEmpty(), text = text, reason = reason,
    )
}

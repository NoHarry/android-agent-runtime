package cc.noharry.agent

import kotlinx.coroutines.flow.Flow

/** A durable conversation. Collecting a turn's cold flow starts that turn exactly once. */
interface AgentThread {
    val id: String
    suspend fun run(input: TurnInput, options: TurnOptions = TurnOptions()): TurnResult
    fun runStreamed(input: TurnInput, options: TurnOptions = TurnOptions()): Flow<ThreadEvent>
    suspend fun run(text: String, options: TurnOptions = TurnOptions()): TurnResult = run(TurnInput.text(text), options)
    fun runStreamed(text: String, options: TurnOptions = TurnOptions()): Flow<ThreadEvent> = runStreamed(TurnInput.text(text), options)
    /** Queues ordinary input without answering any asynchronous question. */
    suspend fun steer(text: String): SteerResult = steer(text, SteerOptions())
    /**
     * Answers the asynchronous question whose complete [Question.id] is supplied. The question must still be pending
     * in the current turn; a stale, answered, or unknown id returns QUESTION_NOT_FOUND without posting input.
     * Null queues ordinary input without binding it to a question.
     */
    suspend fun steer(text: String, answering: String?): SteerResult =
        steer(text, SteerOptions(answering = answering))
    /** Queues input for the active turn, including while it is waiting for tool approval. */
    suspend fun steer(text: String, options: SteerOptions): SteerResult
    /** Requests cancellation of the current turn; keep collecting to receive its interrupted terminal result. */
    suspend fun interrupt()
    suspend fun status(): ThreadStatus
    suspend fun pendingRequests(): List<PendingRequest>
    /** Persisted tool contents follow the configured redactor and may differ from their live snapshots. */
    suspend fun items(afterTurnId: String? = null): List<ThreadItem>
}

/**
 * [requestId] identifies this input within the active turn and is echoed on [ThreadItem.UserMessage]. Repeating
 * the same id with different text or question returns REQUEST_ID_CONFLICT. Null creates an id automatically.
 * [answering] is a complete [Question.id]; null always means ordinary input, even with unanswered questions.
 */
data class SteerOptions(val requestId: String? = null, val answering: String? = null) {
    init { require(requestId == null || requestId.isNotBlank()) { "Steering request id must not be blank" } }
}

enum class SteerResult { ACCEPTED, NOT_RUNNING, EMPTY_INPUT, QUEUE_FULL, QUESTION_NOT_FOUND, REQUEST_ID_CONFLICT }

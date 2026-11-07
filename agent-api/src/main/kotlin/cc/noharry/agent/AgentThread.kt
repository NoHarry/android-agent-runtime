package cc.noharry.agent

import kotlinx.coroutines.flow.Flow

/** A durable conversation. Collecting a turn's cold flow starts that turn exactly once. */
interface AgentThread {
    val id: String
    suspend fun run(input: TurnInput, options: TurnOptions = TurnOptions()): TurnResult
    fun runStreamed(input: TurnInput, options: TurnOptions = TurnOptions()): Flow<ThreadEvent>
    suspend fun run(text: String, options: TurnOptions = TurnOptions()): TurnResult = run(TurnInput.text(text), options)
    fun runStreamed(text: String, options: TurnOptions = TurnOptions()): Flow<ThreadEvent> = runStreamed(TurnInput.text(text), options)
    suspend fun steer(text: String): SteerResult
    /**
     * Answers the asynchronous question whose complete [Question.id] is supplied. The question must still be pending
     * in the current turn; a stale, answered, or unknown id returns QUESTION_NOT_FOUND without posting input.
     * Null preserves ordinary steering behavior. Implementations without explicit binding reject non-null ids.
     */
    suspend fun steer(text: String, answering: String?): SteerResult =
        if (answering == null) steer(text) else SteerResult.QUESTION_NOT_FOUND
    /** Requests cancellation of the current turn; keep collecting to receive its interrupted terminal result. */
    suspend fun interrupt()
    suspend fun status(): ThreadStatus
    suspend fun pendingRequests(): List<PendingRequest>
    /** Persisted tool contents follow the configured redactor and may differ from their live snapshots. */
    suspend fun items(afterTurnId: String? = null): List<ThreadItem>
}

enum class SteerResult { ACCEPTED, NOT_RUNNING, EMPTY_INPUT, QUEUE_FULL, QUESTION_NOT_FOUND }

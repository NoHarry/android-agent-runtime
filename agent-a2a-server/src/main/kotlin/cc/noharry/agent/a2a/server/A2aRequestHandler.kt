package cc.noharry.agent.a2a.server

import kotlinx.coroutines.flow.Flow

/** Candidate host boundary: reuse the host runtime; cancellation must release its resources. */
fun interface A2aRequestHandler {
    /** Execute one admitted text task and end with exactly one [A2aOutput.Result]. */
    fun execute(request: A2aRequest): Flow<A2aOutput>

    /** Inject input into the exact existing execution. The default explicitly declines this capability. */
    suspend fun postInput(request: A2aRequest, messageId: String, text: String, questionId: String?): A2aInputReceipt =
        A2aInputReceipt(accepted = false, code = "INPUT_UNSUPPORTED")
}

package cc.noharry.agent.a2a

import kotlinx.coroutines.flow.Flow

/** Outbound A2A client facade independent of HTTP, JSON, SSE, and third-party SDK types. */
@ExperimentalA2aClientApi
public interface A2aClient : AutoCloseable {
    /** Discovers and validates the selected remote Agent Card. */
    public suspend fun getAgentCard(remote: RemoteAgentRef): A2aResult<A2aAgentCard>

    /** Sends one synchronous A2A message. */
    public suspend fun sendMessage(
        remote: RemoteAgentRef,
        request: A2aSendMessageRequest,
    ): A2aResult<A2aSendMessageResult>

    /** Streams an A2A message exchange with explicit terminal events. */
    public fun streamMessage(
        remote: RemoteAgentRef,
        request: A2aSendMessageRequest,
    ): Flow<A2aStreamEvent>

    /** Fetches the current remote Task snapshot. */
    public suspend fun getTask(remote: RemoteAgentRef, taskId: String): A2aResult<A2aTask>

    /** Requests remote cancellation and returns the resulting Task snapshot. */
    public suspend fun cancelTask(remote: RemoteAgentRef, taskId: String): A2aResult<A2aTask>

    /** Resubscribes to Task events; disconnect without terminal proof is incomplete. */
    public fun resubscribeTask(remote: RemoteAgentRef, taskId: String): Flow<A2aStreamEvent>

    /** Cancels active calls and rejects future operations. */
    override fun close()
}

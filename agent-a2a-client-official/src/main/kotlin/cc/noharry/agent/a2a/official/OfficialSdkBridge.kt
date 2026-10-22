package cc.noharry.agent.a2a.official

import cc.noharry.agent.a2a.A2aAgentCard
import cc.noharry.agent.a2a.A2aClientError
import cc.noharry.agent.a2a.A2aSendMessageRequest
import cc.noharry.agent.a2a.A2aSendMessageResult
import cc.noharry.agent.a2a.A2aStreamEvent
import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.agent.a2a.RemoteAgentRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

@OptIn(ExperimentalA2aClientApi::class)
internal interface OfficialSdkBridge {
    suspend fun getAgentCard(remote: RemoteAgentRef, headers: Map<String, String>): A2aAgentCard

    suspend fun sendMessage(
        remote: RemoteAgentRef,
        request: A2aSendMessageRequest,
        headers: Map<String, String>,
    ): A2aSendMessageResult

    fun streamMessage(
        remote: RemoteAgentRef,
        request: A2aSendMessageRequest,
        headers: Map<String, String>,
    ): Flow<A2aStreamEvent>

    suspend fun getTask(remote: RemoteAgentRef, taskId: String, headers: Map<String, String>): A2aTask

    suspend fun cancelTask(remote: RemoteAgentRef, taskId: String, headers: Map<String, String>): A2aTask

    fun resubscribeTask(
        remote: RemoteAgentRef,
        taskId: String,
        headers: Map<String, String>,
    ): Flow<A2aStreamEvent>

    fun authenticatedStream(create: suspend () -> Flow<A2aStreamEvent>): Flow<A2aStreamEvent> = flow {
        emitAll(create())
    }.catch { error ->
        if (error is CancellationException) throw error
        val safe = (error as? OfficialSdkFailure)?.error
            ?: A2aClientError.Protocol("unexpected_official_sdk_failure")
        emit(A2aStreamEvent.Failed(safe))
    }

    fun close()
}

internal class OfficialSdkFailure(
    val error: A2aClientError,
) : RuntimeException(null, null, false, false)

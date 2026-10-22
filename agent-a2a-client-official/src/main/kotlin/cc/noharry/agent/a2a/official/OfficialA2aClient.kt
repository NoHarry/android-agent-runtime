package cc.noharry.agent.a2a.official

import cc.noharry.agent.a2a.A2aAgentCard
import cc.noharry.agent.a2a.A2aAuthContext
import cc.noharry.agent.a2a.A2aClient
import cc.noharry.agent.a2a.A2aClientConfig
import cc.noharry.agent.a2a.A2aClientError
import cc.noharry.agent.a2a.A2aMethod
import cc.noharry.agent.a2a.A2aProtectedHeaders
import cc.noharry.agent.a2a.A2aResult
import cc.noharry.agent.a2a.A2aSendMessageRequest
import cc.noharry.agent.a2a.A2aSendMessageResult
import cc.noharry.agent.a2a.A2aStreamEvent
import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.agent.a2a.RemoteAgentRef
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalA2aClientApi::class)
internal class OfficialA2aClient(
    private val config: A2aClientConfig,
    private val bridge: OfficialSdkBridge,
) : A2aClient {
    private val closed = AtomicBoolean(false)
    private val configurationError = config.validate()

    override suspend fun getAgentCard(remote: RemoteAgentRef): A2aResult<A2aAgentCard> = execute(
        remote,
        A2aMethod.GET_AGENT_CARD,
    ) { headers -> bridge.getAgentCard(remote, headers) }

    override suspend fun sendMessage(
        remote: RemoteAgentRef,
        request: A2aSendMessageRequest,
    ): A2aResult<A2aSendMessageResult> = execute(remote, A2aMethod.SEND_MESSAGE) { headers ->
        bridge.sendMessage(remote, request, headers)
    }

    override fun streamMessage(
        remote: RemoteAgentRef,
        request: A2aSendMessageRequest,
    ): Flow<A2aStreamEvent> = stream(remote, A2aMethod.STREAM_MESSAGE) { headers ->
        bridge.streamMessage(remote, request, headers)
    }

    override suspend fun getTask(remote: RemoteAgentRef, taskId: String): A2aResult<A2aTask> =
        execute(remote, A2aMethod.GET_TASK) { headers -> bridge.getTask(remote, taskId, headers) }

    override suspend fun cancelTask(remote: RemoteAgentRef, taskId: String): A2aResult<A2aTask> =
        execute(remote, A2aMethod.CANCEL_TASK) { headers -> bridge.cancelTask(remote, taskId, headers) }

    override fun resubscribeTask(remote: RemoteAgentRef, taskId: String): Flow<A2aStreamEvent> =
        stream(remote, A2aMethod.RESUBSCRIBE_TASK) { headers ->
            bridge.resubscribeTask(remote, taskId, headers)
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) bridge.close()
    }

    private suspend fun <T> execute(
        remote: RemoteAgentRef,
        method: A2aMethod,
        operation: suspend (Map<String, String>) -> T,
    ): A2aResult<T> {
        preflight(remote)?.let { return A2aResult.Failure(it) }
        return try {
            A2aResult.Success(operation(protectedHeaders(remote, method)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OfficialSdkFailure) {
            A2aResult.Failure(failure.error)
        } catch (_: Throwable) {
            A2aResult.Failure(A2aClientError.Protocol("unexpected_official_sdk_failure"))
        }
    }

    private fun stream(
        remote: RemoteAgentRef,
        method: A2aMethod,
        operation: (Map<String, String>) -> Flow<A2aStreamEvent>,
    ): Flow<A2aStreamEvent> {
        preflight(remote)?.let { return flowOf(A2aStreamEvent.Failed(it)) }
        return bridge.authenticatedStream {
            operation(protectedHeaders(remote, method))
        }
    }

    private fun preflight(remote: RemoteAgentRef): A2aClientError? {
        configurationError?.let { return it }
        if (closed.get()) return A2aClientError.Configuration("client_closed")
        if (remote.id.isBlank()) return A2aClientError.Configuration("remote_id_blank")
        return OfficialUriPolicy.validate(remote, config.allowLoopbackHttpForDebug)
    }

    private suspend fun protectedHeaders(remote: RemoteAgentRef, method: A2aMethod): Map<String, String> {
        val requestId = UUID.randomUUID().toString()
        val protected = try {
            config.authProvider.headers(A2aAuthContext(remote.id, method, requestId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw OfficialSdkFailure(A2aClientError.Authentication("auth_provider_failed"))
        }
        rejectReservedHeaders(protected)
        return buildMap { protected.forEachValue(::put) }
    }

    private fun rejectReservedHeaders(headers: A2aProtectedHeaders) {
        if (headers.names().any { it.lowercase(Locale.ROOT) in RESERVED_HEADERS }) {
            throw OfficialSdkFailure(A2aClientError.Configuration("reserved_header_conflict"))
        }
    }

    private companion object {
        val RESERVED_HEADERS = setOf("accept", "content-type", "a2a-version")
    }
}

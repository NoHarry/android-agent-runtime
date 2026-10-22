package cc.noharry.agent.a2a.direct

import cc.noharry.agent.a2a.A2aAgentCard
import cc.noharry.agent.a2a.A2aAuthContext
import cc.noharry.agent.a2a.A2aClient
import cc.noharry.agent.a2a.A2aClientConfig
import cc.noharry.agent.a2a.A2aClientError
import cc.noharry.agent.a2a.A2aMessage
import cc.noharry.agent.a2a.A2aMethod
import cc.noharry.agent.a2a.A2aResult
import cc.noharry.agent.a2a.A2aSendMessageRequest
import cc.noharry.agent.a2a.A2aSendMessageResult
import cc.noharry.agent.a2a.A2aStreamEvent
import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.agent.a2a.RemoteAgentRef
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

@OptIn(ExperimentalA2aClientApi::class)
internal class DirectA2aClient(
    private val config: A2aClientConfig,
    baseClient: OkHttpClient,
) : A2aClient {
    private val closed = AtomicBoolean(false)
    private val configurationError = config.validate()
    private val httpClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(config.connectTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .readTimeout(config.streamReadTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .callTimeout(config.requestTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun getAgentCard(remote: RemoteAgentRef): A2aResult<A2aAgentCard> {
        val requestId = requestId()
        return executeDocument(remote) {
            val uri = remote.cardUrl ?: defaultCardUri(remote.endpoint)
            val request = requestBuilder(remote, uri, A2aMethod.GET_AGENT_CARD, requestId)
                .get()
                .header(HEADER_ACCEPT, MEDIA_TYPE_JSON)
                .build()
            execute(request).use { response ->
                if (!response.isSuccessful) return@use httpFailure(response, discovery = true)
                val root = parseLimitedJson(response)
                val card = DirectA2aJsonCodec.decodeCard(root)
                if (remote.expectedIdentity != null && remote.expectedIdentity != card.name) {
                    A2aResult.Failure(A2aClientError.Discovery("identity_mismatch"))
                } else {
                    validateUri(card.url)
                    A2aResult.Success(card)
                }
            }
        }
    }

    override suspend fun sendMessage(
        remote: RemoteAgentRef,
        request: A2aSendMessageRequest,
    ): A2aResult<A2aSendMessageResult> = rpc(
        remote = remote,
        method = A2aMethod.SEND_MESSAGE,
        rpcMethod = RPC_MESSAGE_SEND,
        params = DirectA2aJsonCodec.messageParams(request.message),
        decoder = DirectA2aJsonCodec::decodeSendResult,
    )

    override fun streamMessage(
        remote: RemoteAgentRef,
        request: A2aSendMessageRequest,
    ): Flow<A2aStreamEvent> = streamRpc(
        remote = remote,
        method = A2aMethod.STREAM_MESSAGE,
        rpcMethod = RPC_MESSAGE_STREAM,
        params = DirectA2aJsonCodec.messageParams(request.message),
    )

    override suspend fun getTask(remote: RemoteAgentRef, taskId: String): A2aResult<A2aTask> = rpc(
        remote = remote,
        method = A2aMethod.GET_TASK,
        rpcMethod = RPC_TASKS_GET,
        params = DirectA2aJsonCodec.taskParams(taskId),
        decoder = DirectA2aJsonCodec::decodeTask,
    )

    override suspend fun cancelTask(remote: RemoteAgentRef, taskId: String): A2aResult<A2aTask> = rpc(
        remote = remote,
        method = A2aMethod.CANCEL_TASK,
        rpcMethod = RPC_TASKS_CANCEL,
        params = DirectA2aJsonCodec.taskParams(taskId),
        decoder = DirectA2aJsonCodec::decodeTask,
    )

    override fun resubscribeTask(remote: RemoteAgentRef, taskId: String): Flow<A2aStreamEvent> = streamRpc(
        remote = remote,
        method = A2aMethod.RESUBSCRIBE_TASK,
        rpcMethod = RPC_TASKS_RESUBSCRIBE,
        params = DirectA2aJsonCodec.taskParams(taskId),
    )

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            httpClient.dispatcher.cancelAll()
            httpClient.connectionPool.evictAll()
        }
    }

    private suspend fun <T> rpc(
        remote: RemoteAgentRef,
        method: A2aMethod,
        rpcMethod: String,
        params: JsonElement,
        decoder: (JsonElement) -> T,
    ): A2aResult<T> {
        val requestId = requestId()
        return executeDocument(remote) {
            val body = DirectA2aJsonCodec.rpcRequest(requestId, rpcMethod, params)
            if (body.toByteArray().size > config.maxDocumentBytes) {
                return@executeDocument A2aResult.Failure(A2aClientError.Configuration("request_too_large"))
            }
            val request = rpcRequest(remote, method, requestId, body, streaming = false)
            execute(request).use { response ->
                if (!response.isSuccessful) return@use httpFailure(response)
                val root = parseLimitedJson(response)
                DirectA2aJsonCodec.rpcError(root)?.let { (code, reason) ->
                    return@use A2aResult.Failure(A2aClientError.Rpc(code, reason))
                }
                A2aResult.Success(decoder(DirectA2aJsonCodec.rpcResult(root, requestId)))
            }
        }
    }

    private fun streamRpc(
        remote: RemoteAgentRef,
        method: A2aMethod,
        rpcMethod: String,
        params: JsonElement,
    ): Flow<A2aStreamEvent> = flow {
        val requestId = requestId()
        val initialError = preflight(remote)
        if (initialError != null) {
            emit(A2aStreamEvent.Failed(initialError))
            return@flow
        }
        val body = DirectA2aJsonCodec.rpcRequest(requestId, rpcMethod, params)
        if (body.toByteArray().size > config.maxDocumentBytes) {
            emit(A2aStreamEvent.Failed(A2aClientError.Configuration("request_too_large")))
            return@flow
        }
        try {
            val request = rpcRequest(remote, method, requestId, body, streaming = true)
            execute(request).use { response ->
                if (!response.isSuccessful) {
                    emit(A2aStreamEvent.Failed(httpError(response)))
                    return@use
                }
                val responseBody = response.body ?: run {
                    emit(A2aStreamEvent.Failed(A2aClientError.Protocol("empty_stream_body")))
                    return@use
                }
                val source = responseBody.source()
                val dataLines = mutableListOf<String>()
                val seenEvents = mutableSetOf<A2aStreamEvent>()
                var outcome = FrameOutcome.CONTINUE
                while (currentCoroutineContext().isActive) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty()) {
                        outcome = emitFrame(dataLines, requestId, seenEvents)
                        dataLines.clear()
                        if (outcome != FrameOutcome.CONTINUE) break
                    } else if (line.startsWith(SSE_DATA_PREFIX)) {
                        dataLines += line.removePrefix(SSE_DATA_PREFIX).removePrefix(" ")
                    }
                    checkFrameLimit(dataLines)
                }
                if (outcome == FrameOutcome.CONTINUE && dataLines.isNotEmpty()) {
                    outcome = emitFrame(dataLines, requestId, seenEvents)
                }
                when (outcome) {
                    FrameOutcome.COMPLETED -> emit(A2aStreamEvent.Completed)
                    FrameOutcome.CONTINUE -> emit(A2aStreamEvent.Incomplete(A2aClientError.Incomplete()))
                    FrameOutcome.FAILED -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emit(A2aStreamEvent.Failed(mapFailure(error)))
        }
    }.buffer(config.maxStreamBufferEvents)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<A2aStreamEvent>.emitFrame(
        dataLines: List<String>,
        requestId: String,
        seenEvents: MutableSet<A2aStreamEvent>,
    ): FrameOutcome {
        if (dataLines.isEmpty()) return FrameOutcome.CONTINUE
        val root = DirectA2aJsonCodec.json.parseToJsonElement(dataLines.joinToString("\n")).jsonObject
        DirectA2aJsonCodec.rpcError(root)?.let { (code, reason) ->
            emit(A2aStreamEvent.Failed(A2aClientError.Rpc(code, reason)))
            return FrameOutcome.FAILED
        }
        val result = DirectA2aJsonCodec.rpcResult(root, requestId)
        val (events, terminal) = DirectA2aJsonCodec.decodeStreamResult(result)
        events.forEach { event ->
            if (!seenEvents.add(event)) {
                emit(A2aStreamEvent.Failed(A2aClientError.Protocol("duplicate_stream_event")))
                return FrameOutcome.FAILED
            }
            emit(event)
        }
        return if (terminal) FrameOutcome.COMPLETED else FrameOutcome.CONTINUE
    }

    private suspend fun requestBuilder(
        remote: RemoteAgentRef,
        uri: URI,
        method: A2aMethod,
        requestId: String,
    ): Request.Builder {
        validateUri(uri)
        val builder = Request.Builder().url(uri.toURL()).header(HEADER_REQUEST_ID, requestId)
        val headers = try {
            config.authProvider.headers(A2aAuthContext(remote.id, method, requestId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw SafeFailure(A2aClientError.Authentication("auth_provider_failed"))
        }
        val conflict = headers.names().firstOrNull { it.lowercase(Locale.ROOT) in RESERVED_HEADERS }
        if (conflict != null) throw SafeFailure(A2aClientError.Configuration("reserved_header_conflict"))
        headers.forEachValue { name, value -> builder.header(name, value) }
        return builder
    }

    private suspend fun rpcRequest(
        remote: RemoteAgentRef,
        method: A2aMethod,
        requestId: String,
        body: String,
        streaming: Boolean,
    ): Request = requestBuilder(remote, remote.endpoint, method, requestId)
        .header(HEADER_CONTENT_TYPE, MEDIA_TYPE_JSON)
        .header(HEADER_ACCEPT, if (streaming) MEDIA_TYPE_SSE else MEDIA_TYPE_JSON)
        .post(body.toRequestBody(JSON_MEDIA_TYPE))
        .build()

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        val call = httpClient.newCall(request)
        val handle = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            call.execute()
        } finally {
            handle.dispose()
        }
    }

    private suspend fun <T> executeDocument(
        remote: RemoteAgentRef,
        block: suspend () -> A2aResult<T>,
    ): A2aResult<T> {
        preflight(remote)?.let { return A2aResult.Failure(it) }
        return try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SafeFailure) {
            A2aResult.Failure(failure.error)
        } catch (error: Throwable) {
            A2aResult.Failure(mapFailure(error))
        }
    }

    private fun preflight(remote: RemoteAgentRef): A2aClientError? {
        configurationError?.let { return it }
        if (closed.get()) return A2aClientError.Configuration("client_closed")
        if (remote.id.isBlank()) return A2aClientError.Configuration("remote_id_blank")
        return try {
            validateUri(remote.endpoint)
            remote.cardUrl?.let(::validateUri)
            null
        } catch (failure: SafeFailure) {
            failure.error
        }
    }

    private fun validateUri(uri: URI) {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (uri.userInfo != null || host == null || (scheme != "https" && scheme != "http")) {
            throw SafeFailure(A2aClientError.Configuration("invalid_remote_uri"))
        }
        if (scheme == "http" && !(config.allowLoopbackHttpForDebug && isLoopback(host))) {
            throw SafeFailure(A2aClientError.Configuration("insecure_remote_uri"))
        }
    }

    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host == "::1" || host == "[::1]" ||
            isIpv4Loopback(host)

    private fun isIpv4Loopback(host: String): Boolean {
        val segments = host.split('.')
        if (segments.size != IPV4_SEGMENT_COUNT) return false
        val values = segments.map { segment ->
            if (segment.isEmpty() || segment.any { it !in '0'..'9' }) return false
            segment.toIntOrNull()?.takeIf { it in 0..IPV4_SEGMENT_MAX } ?: return false
        }
        return values.first() == IPV4_LOOPBACK_PREFIX
    }

    private fun defaultCardUri(endpoint: URI): URI = URI(
        endpoint.scheme,
        null,
        endpoint.host,
        endpoint.port,
        CARD_PATH,
        null,
        null,
    )

    private fun parseLimitedJson(response: Response): JsonObject {
        val body = response.body ?: throw SafeFailure(A2aClientError.Protocol("empty_response_body"))
        val buffer = Buffer()
        val source = body.source()
        var total = 0L
        while (true) {
            val read = source.read(buffer, READ_CHUNK_BYTES)
            if (read == -1L) break
            total += read
            if (total > config.maxDocumentBytes) {
                throw SafeFailure(A2aClientError.Protocol("response_too_large"))
            }
        }
        return DirectA2aJsonCodec.json.parseToJsonElement(buffer.readUtf8()).jsonObject
    }

    private fun checkFrameLimit(dataLines: List<String>) {
        val bytes = dataLines.sumOf { it.toByteArray().size.toLong() }
        if (bytes > config.maxDocumentBytes) {
            throw SafeFailure(A2aClientError.Protocol("sse_frame_too_large"))
        }
    }

    private fun <T> httpFailure(response: Response, discovery: Boolean = false): A2aResult<T> =
        A2aResult.Failure(if (discovery) discoveryHttpError(response) else httpError(response))

    private fun discoveryHttpError(response: Response): A2aClientError = when (response.code) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> A2aClientError.Authentication("authentication_rejected")
        else -> A2aClientError.Discovery("card_http_failure")
    }

    private fun httpError(response: Response): A2aClientError = when (response.code) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> A2aClientError.Authentication("authentication_rejected")
        else -> A2aClientError.Transport("http_failure")
    }

    private fun mapFailure(error: Throwable): A2aClientError = when (error) {
        is SafeFailure -> error.error
        is SocketTimeoutException -> A2aClientError.Timeout()
        is InterruptedIOException -> A2aClientError.Timeout()
        is IOException -> A2aClientError.Transport("network_failure")
        is IllegalArgumentException -> A2aClientError.Protocol("invalid_protocol_document")
        else -> A2aClientError.Protocol("unexpected_client_failure")
    }

    private fun requestId(): String = UUID.randomUUID().toString()

    private class SafeFailure(val error: A2aClientError) : RuntimeException(null, null, false, false)

    private enum class FrameOutcome {
        CONTINUE,
        COMPLETED,
        FAILED,
    }

    private companion object {
        const val CARD_PATH = "/.well-known/agent-card.json"
        const val RPC_MESSAGE_SEND = "message/send"
        const val RPC_MESSAGE_STREAM = "message/stream"
        const val RPC_TASKS_GET = "tasks/get"
        const val RPC_TASKS_CANCEL = "tasks/cancel"
        const val RPC_TASKS_RESUBSCRIBE = "tasks/resubscribe"
        const val HEADER_ACCEPT = "Accept"
        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val HEADER_REQUEST_ID = "X-A2A-Request-Id"
        const val MEDIA_TYPE_JSON = "application/json"
        const val MEDIA_TYPE_SSE = "text/event-stream"
        const val SSE_DATA_PREFIX = "data:"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val READ_CHUNK_BYTES = 8_192L
        const val IPV4_SEGMENT_COUNT = 4
        const val IPV4_SEGMENT_MAX = 255
        const val IPV4_LOOPBACK_PREFIX = 127
        val JSON_MEDIA_TYPE = MEDIA_TYPE_JSON.toMediaType()
        val RESERVED_HEADERS = setOf(
            HEADER_ACCEPT.lowercase(Locale.ROOT),
            HEADER_CONTENT_TYPE.lowercase(Locale.ROOT),
            HEADER_REQUEST_ID.lowercase(Locale.ROOT),
        )
    }
}

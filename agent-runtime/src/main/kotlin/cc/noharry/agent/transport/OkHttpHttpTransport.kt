package cc.noharry.agent.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

/**
 * [HttpTransport] over OkHttp. It waits up to [readTimeoutMillis] between bytes (300 s: a model's whole answer comes as
 * one response unless streamed, and a thinking model may say nothing for a while), [connectTimeoutMillis] to connect
 * (10 s), both positive, and goes through [proxy] when the host gives one; HTTP/2 pings find a dead connection. A call ends as soon as
 * the coroutine that made it is cancelled. A host that needs more of its HTTP client (interceptors, certificate
 * pinning) implements [HttpTransport] over its own.
 */
class OkHttpHttpTransport @JvmOverloads constructor(
    connectTimeoutMillis: Long = CONNECT_TIMEOUT_MILLIS,
    readTimeoutMillis: Long = READ_TIMEOUT_MILLIS,
    proxy: Proxy? = null,
) : HttpTransport {
    private val calls: OkHttpCalls

    init {
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0) { "HTTP timeouts must be positive." }
        val isDefault = connectTimeoutMillis == CONNECT_TIMEOUT_MILLIS && readTimeoutMillis == READ_TIMEOUT_MILLIS && proxy == null
        // A client of its own still shares the default one's connections and threads.
        calls = if (isDefault) DefaultOkHttpTransport else OkHttpCalls(
            DefaultOkHttpTransport.client.newBuilder()
                .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
                .proxy(proxy)
                .build(),
        )
    }

    override fun stream(request: HttpRequest): Flow<HttpEvent> = flow {
        calls.stream(
            request = request,
            onResponse = { statusCode, headers -> emit(HttpEvent.Response(statusCode, headers)) },
            onData = { bytes -> emit(HttpEvent.Data(bytes)) },
        )
        emit(HttpEvent.Done)
    }.flowOn(Dispatchers.IO)

    override suspend fun execute(request: HttpRequest): HttpResponse =
        calls.execute(request)
}

private object DefaultOkHttpTransport : OkHttpCalls(
    OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build(),
)

/** Calls through [client], each cancelled with the coroutine that made it. */
private open class OkHttpCalls(val client: OkHttpClient) {
    suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        cancellable(client.newCall(request.toOkHttpRequest())) { call ->
            call.execute().use { response ->
                HttpResponse(
                    statusCode = response.code,
                    headers = response.headers.toMultimap(),
                    body = response.body?.bytes() ?: ByteArray(0),
                )
            }
        }
    }

    suspend fun stream(
        request: HttpRequest,
        onResponse: suspend (Int, Map<String, List<String>>) -> Unit,
        onData: suspend (ByteArray) -> Unit,
    ) = cancellable(client.newCall(request.toOkHttpRequest())) { call ->
        call.execute().use { response ->
            onResponse(response.code, response.headers.toMultimap())
            val source = response.body?.source() ?: return@use
            val buffer = Buffer()
            while (source.read(buffer, STREAM_CHUNK_SIZE) != -1L) {
                val bytes = buffer.readByteArray()
                if (bytes.isNotEmpty()) {
                    onData(bytes)
                }
            }
        }
    }
}

/**
 * Runs [block] on [call], cancelling the call when the caller is: a blocking OkHttp call does not see a coroutine
 * cancelled, so a timeout or a stopped run would otherwise wait for the server, up to the read timeout.
 */
private suspend fun <T> cancellable(call: Call, block: suspend (Call) -> T): T = coroutineScope {
    val watch = launch { try { awaitCancellation() } finally { call.cancel() } }
    try {
        block(call)
    } catch (failure: IOException) {
        // The call ended because the caller was cancelled: that, not the closed socket, is what happened.
        currentCoroutineContext().ensureActive()
        throw failure
    } finally {
        watch.cancel()
    }
}

private fun HttpRequest.toOkHttpRequest(): Request {
    val builder = Request.Builder().url(url)
    headers.forEach { (name, value) -> builder.addHeader(name, value) }
    val methodName = method.uppercase()
    val requestBody = when (methodName) {
        "GET", "HEAD" -> null
        else -> (body ?: ByteArray(0)).toRequestBody()
    }
    return builder.method(methodName, requestBody).build()
}

private const val STREAM_CHUNK_SIZE = 8192L

private const val CONNECT_TIMEOUT_MILLIS = 10_000L
private const val READ_TIMEOUT_MILLIS = 300_000L
private const val PING_INTERVAL_SECONDS = 30L

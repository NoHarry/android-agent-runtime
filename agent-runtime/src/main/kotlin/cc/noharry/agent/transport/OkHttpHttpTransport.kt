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
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

class OkHttpHttpTransport : HttpTransport {
    override fun stream(request: HttpRequest): Flow<HttpEvent> = flow {
        DefaultOkHttpTransport.stream(
            request = request,
            onResponse = { statusCode, headers -> emit(HttpEvent.Response(statusCode, headers)) },
            onData = { bytes -> emit(HttpEvent.Data(bytes)) },
        )
        emit(HttpEvent.Done)
    }.flowOn(Dispatchers.IO)

    override suspend fun execute(request: HttpRequest): HttpResponse =
        DefaultOkHttpTransport.execute(request)
}

private object DefaultOkHttpTransport {
    // A model's whole answer comes as one response unless streamed, and a thinking model may say nothing for a while:
    // OkHttp's 10 s between bytes ends both. A call ends sooner when its caller is cancelled ([cancellable]); HTTP/2
    // pings find a dead connection.
    private val client = OkHttpClient.Builder()
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()

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
}

private const val STREAM_CHUNK_SIZE = 8192L

private const val READ_TIMEOUT_SECONDS = 300L
private const val PING_INTERVAL_SECONDS = 30L

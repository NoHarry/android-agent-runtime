package cc.noharry.agent.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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
    private val client = OkHttpClient()

    suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        client.newCall(request.toOkHttpRequest()).execute().use { response ->
            HttpResponse(
                statusCode = response.code,
                headers = response.headers.toMultimap(),
                body = response.body?.bytes() ?: ByteArray(0),
            )
        }
    }

    suspend fun stream(
        request: HttpRequest,
        onResponse: suspend (Int, Map<String, List<String>>) -> Unit,
        onData: suspend (ByteArray) -> Unit,
    ) {
        client.newCall(request.toOkHttpRequest()).execute().use { response ->
            onResponse(response.code, response.headers.toMultimap())
            val source = response.body?.source() ?: return
            val buffer = Buffer()
            while (source.read(buffer, STREAM_CHUNK_SIZE) != -1L) {
                val bytes = buffer.readByteArray()
                if (bytes.isNotEmpty()) {
                    onData(bytes)
                }
            }
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

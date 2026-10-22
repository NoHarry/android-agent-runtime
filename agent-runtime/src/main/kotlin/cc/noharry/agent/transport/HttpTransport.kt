package cc.noharry.agent.transport

import kotlinx.coroutines.flow.Flow

interface HttpTransport {
    fun stream(request: HttpRequest): Flow<HttpEvent>

    suspend fun execute(request: HttpRequest): HttpResponse
}

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray = ByteArray(0),
)

sealed interface HttpEvent {
    data class Response(
        val statusCode: Int,
        val headers: Map<String, List<String>> = emptyMap(),
    ) : HttpEvent

    data class Data(val bytes: ByteArray) : HttpEvent

    data object Done : HttpEvent
}

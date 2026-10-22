package cc.noharry.agent.transport

import java.net.UnknownHostException

internal fun interface WebFetchTransport {
    suspend fun execute(request: WebFetchRequest): WebFetchResponse
}

internal data class WebFetchRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val maxTransferBytes: Int,
)

internal data class WebFetchResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val transferTruncated: Boolean = false,
)

internal class NetworkAddressDeniedException(hostname: String) :
    UnknownHostException("Network target resolves only to denied addresses: $hostname")

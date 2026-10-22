package cc.noharry.agent.mcp

import kotlinx.serialization.json.JsonObject

/**
 * An MCP server spoken to in JSON-RPC, such as a standard MCP server over Streamable HTTP ([McpHttpClient]).
 * [McpToolProvider] lists and calls tools through it; a response's [McpRpcResponse.dataJson] is the JSON-RPC response
 * object (`result` or `error`). Candidate API, not frozen.
 */
interface McpRpcClient {
    suspend fun connect(): McpConnectResult

    suspend fun sendRequest(method: String, params: JsonObject): McpRpcResponse

    /** Ends the connection and its reconnect attempts. */
    fun close()
}

/** Status codes of [McpConnectResult] and [McpRpcResponse]. */
object McpStatus {
    const val OK = 0
    const val CONNECT_ERROR = -1
}

data class McpConnectResult(
    val statusCode: Int,
    val rawMessage: String = "",
    val errorMessage: String? = null,
) {
    val ok: Boolean = statusCode == McpStatus.OK
}

data class McpRpcResponse(
    val statusCode: Int,
    val rawEnvelope: String,
    val dataJson: JsonObject?,
    val errorMessage: String?,
) {
    val ok: Boolean = statusCode == McpStatus.OK && errorMessage == null
}

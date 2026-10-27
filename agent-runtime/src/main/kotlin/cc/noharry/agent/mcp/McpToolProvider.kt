package cc.noharry.agent.mcp

import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolInitializeContext
import cc.noharry.agent.kernel.api.ToolInitializeResult
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Keeps an MCP server's tool list between runs, so a run need not wait on `tools/list`: [get] returns the kept list or
 * loads it with `fetch`. Candidate API, not frozen.
 */
fun interface McpToolCatalog {
    suspend fun get(fetch: suspend () -> List<ToolDescriptor>): List<ToolDescriptor>
}

/**
 * An MCP server's tools in a run, through any [McpRpcClient], such as a standard MCP server over HTTP
 * ([McpHttpClient]). It speaks JSON-RPC `tools/list` and `tools/call`; [catalog] keeps the tool list between runs, and
 * [describe] may adjust each tool before the model sees it. Over [McpHttpClient] up to four calls run at once (each is
 * its own request, and MCP lets a session have several in flight), so a round's read-only calls run together; through
 * another client, one at a time. Candidate API, not frozen.
 */
class McpToolProvider(
    private val client: McpRpcClient,
    override val serverId: String,
    override val displayName: String = serverId,
    private val catalog: McpToolCatalog? = null,
    private val describe: (ToolDescriptor) -> ToolDescriptor = { it },
) : McpClientProvider {
    @OptIn(ExperimentalAgentRuntimeApi::class)
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        if (client is McpHttpClient) ProviderConcurrencyDeclaration.threadSafe() else ProviderConcurrencyDeclaration.serialized()

    override suspend fun initialize(context: ToolInitializeContext): ToolInitializeResult {
        val result = client.connect()
        return if (result.ok) {
            ToolInitializeResult.connected("$displayName connected.")
        } else {
            ToolInitializeResult.failed(result.errorMessage ?: "$displayName connection failed.")
        }
    }

    override suspend fun listTools(): List<ToolDescriptor> =
        (catalog?.get { listMcpToolsCatalog(client) } ?: listMcpToolsCatalog(client)).map(describe)

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult {
        val arguments = parseJsonObject(argumentsJson) ?: return ToolCallResult(
            success = false,
            resultJson = errorJson(INVALID_ARGUMENTS_CODE, "Tool arguments must be a JSON object.", toolName),
        )
        val response = client.sendRequest(
            method = TOOLS_CALL_METHOD,
            params = buildToolsCallParams(toolName, arguments),
        )
        if (!response.ok) {
            return ToolCallResult(
                success = false,
                resultJson = errorJson(MCP_TRANSPORT_CODE, response.errorMessage.orEmpty(), toolName),
                retryable = true,
            )
        }
        val data = response.dataJson ?: return ToolCallResult(
            success = false,
            resultJson = errorJson(MCP_EMPTY_DATA_CODE, "MCP response did not contain JSON-RPC data.", toolName),
            retryable = true,
        )
        return ToolCallResult(
            success = !data.hasMcpToolError(),
            resultJson = data.toString(),
        )
    }
}

/** Every page of `tools/list`; empty when any page failed. */
suspend fun listMcpToolsCatalog(client: McpRpcClient): List<ToolDescriptor> =
    listMcpTools(client).let { if (it.problem == null) it.tools else emptyList() }

/** A server's whole tool list, or why it could not be read. Candidate API, not frozen. */
class McpToolsListing(val tools: List<ToolDescriptor>, val problem: String? = null)

/** Reads every page of the server's `tools/list`; [messages] words a problem. Candidate API, not frozen. */
suspend fun listMcpTools(
    client: McpRpcClient,
    messages: McpClientMessages = McpClientMessages.Default,
): McpToolsListing {
    val collected = ArrayList<ToolDescriptor>()
    var cursor: String? = null
    val seenCursors = HashSet<String>()
    repeat(MAX_TOOLS_LIST_PAGES) {
        val page = fetchToolsListPage(client, cursor, messages)
        page.problem?.let { return McpToolsListing(emptyList(), it) }
        collected += page.tools
        val nextCursor = page.nextCursor ?: return McpToolsListing(collected)
        if (!seenCursors.add(nextCursor)) return McpToolsListing(emptyList(), messages.toolsListCursorRepeated())
        cursor = nextCursor
    }
    return McpToolsListing(emptyList(), messages.toolsListTooManyPages(MAX_TOOLS_LIST_PAGES))
}

private suspend fun fetchToolsListPage(
    client: McpRpcClient,
    cursor: String?,
    messages: McpClientMessages,
): ToolsListPage {
    val response = client.sendRequest(TOOLS_LIST_METHOD, toolsListParams(cursor))
    if (!response.ok) return ToolsListPage(problem = response.errorMessage ?: messages.toolsListFailed(response.statusCode))
    val data = response.dataJson ?: return ToolsListPage(problem = messages.toolsListNotJson())
    if (data.hasJsonRpcError()) {
        val message = ((data["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
        return ToolsListPage(problem = messages.toolsListError(message ?: data["error"].toString()))
    }
    return ToolsListPage(
        tools = data.toolsArray().mapNotNull(::parseTool),
        nextCursor = data.nextCursor(),
    )
}

private fun toolsListParams(cursor: String?): JsonObject =
    if (cursor == null) {
        EMPTY_PARAMS
    } else {
        buildJsonObject { put(CURSOR_PARAM, JsonPrimitive(cursor)) }
    }

private fun JsonObject.nextCursor(): String? {
    val result = this["result"] as? JsonObject ?: return null
    val value = result[NEXT_CURSOR_FIELD] ?: return null
    if (value is JsonNull) return null
    return (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
}

private fun JsonObject.hasJsonRpcError(): Boolean = this["error"] != null && this["error"] !is JsonNull

private data class ToolsListPage(
    val tools: List<ToolDescriptor> = emptyList(),
    val nextCursor: String? = null,
    val problem: String? = null,
)

private fun parseTool(element: JsonElement): ToolDescriptor? {
    val item = element as? JsonObject ?: return null
    val name = item.stringField("name") ?: return null
    return ToolDescriptor(
        name = name,
        description = item.stringField("description").orEmpty(),
        inputSchemaJson = (item["inputSchema"] as? JsonObject)?.toString() ?: EMPTY_SCHEMA_JSON,
        title = item.stringField("title") ?: item.stringField("description") ?: name,
        // The server's own word that the tool changes nothing (MCP `readOnlyHint`); a plan may then call it on a watch.
        effectKind = if (item.readOnlyHint()) READ_ONLY_EFFECT else UNKNOWN_EFFECT,
        outputSchemaJson = (item["outputSchema"] as? JsonObject)?.toString(),
    )
}

private fun buildToolsCallParams(toolName: String, arguments: JsonObject): JsonObject =
    buildJsonObject {
        put("name", JsonPrimitive(toolName))
        put("arguments", arguments)
    }

private fun JsonObject.toolsArray(): List<JsonElement> =
    ((this["result"] as? JsonObject)?.get("tools") as? JsonArray).orEmpty()

private fun JsonObject.hasMcpToolError(): Boolean {
    if (hasJsonRpcError()) return true
    val topLevelError = (this["isError"] as? JsonPrimitive)?.booleanOrNull == true
    val nestedError = ((this["result"] as? JsonObject)?.get("isError") as? JsonPrimitive)
        ?.booleanOrNull == true
    return topLevelError || nestedError
}

private fun JsonObject.readOnlyHint(): Boolean =
    ((this["annotations"] as? JsonObject)?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull == true

private fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun parseJsonObject(json: String): JsonObject? =
    try {
        Json.parseToJsonElement(json) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun errorJson(code: String, message: String, toolName: String): String =
    """{"ok":false,"code":"${code.escapeJson()}","tool":"${toolName.escapeJson()}","message":"${message.escapeJson()}"}"""

private fun String.escapeJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

private const val TOOLS_LIST_METHOD = "tools/list"
private const val TOOLS_CALL_METHOD = "tools/call"
private const val CURSOR_PARAM = "cursor"
private const val NEXT_CURSOR_FIELD = "nextCursor"
private const val MAX_TOOLS_LIST_PAGES = 128
private const val INVALID_ARGUMENTS_CODE = "MCP_INVALID_ARGUMENTS"
private const val MCP_TRANSPORT_CODE = "MCP_TRANSPORT_ERROR"
private const val MCP_EMPTY_DATA_CODE = "MCP_EMPTY_DATA"
private const val READ_ONLY_EFFECT = "READ_ONLY"
private const val UNKNOWN_EFFECT = "UNKNOWN"
private const val EMPTY_SCHEMA_JSON = """{"type":"object","additionalProperties":true}"""
private val EMPTY_PARAMS = JsonObject(emptyMap())

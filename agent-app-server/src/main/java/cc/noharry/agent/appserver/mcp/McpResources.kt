package cc.noharry.agent.appserver.mcp
import android.content.Context
import cc.noharry.agent.appserver.AppServerPluginApi
import cc.noharry.agent.appserver.protocol.McpServer
import cc.noharry.agent.mcp.McpRpcClient
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A resource a loaded MCP server offers now: something a plan can watch. */
@AppServerPluginApi
data class McpResource(val server: String, val serverLabel: String, val uri: String, val name: String, val description: String)

/**
 * [serverId]'s connection, connected, while that configured MCP server is switched on; null otherwise. Plans
 * watch and act through whatever servers are loaded, not a list in the app.
 */
@AppServerPluginApi
suspend fun mcpResourceClient(context: Context, serverId: String): McpRpcClient? {
    val server = loadMcpServers(context).firstOrNull { it.id == serverId && it.enabled } ?: return null
    val client: McpRpcClient = McpHttpClients.get(context, server)
    return client.takeIf { it.connect().ok }
}

/** One reading of [uri] on [serverId]: the resource's data, or null when it could not be had. */
internal suspend fun readMcpResource(context: Context, serverId: String, uri: String): JsonObject? =
    readMcpResourceAll(context, serverId, uri)?.firstOrNull()

/** Every reading of [uri] on [serverId] (a server may answer several, one per item); null when it could not be had. */
@AppServerPluginApi
suspend fun readMcpResourceAll(context: Context, serverId: String, uri: String): List<JsonObject>? {
    val client = mcpResourceClient(context, serverId) ?: return null
    val response = client.sendRequest(RESOURCES_READ, buildJsonObject { put("uri", uri) })
    return if (response.ok) resourceReadings(response.dataJson).ifEmpty { null } else null
}

/**
 * One reading of [tool] on [serverId], called with [arguments]: a watch on a server that offers tools only. The tool's
 * `structuredContent`, else its first text that is a JSON object; null when the call failed or said nothing readable.
 */
@AppServerPluginApi
suspend fun readMcpTool(context: Context, serverId: String, tool: String, arguments: JsonObject): JsonObject? {
    val client = mcpResourceClient(context, serverId) ?: return null
    val response = client.sendRequest(TOOLS_CALL, buildJsonObject {
        put("name", tool)
        put("arguments", arguments)
    })
    return if (response.ok) toolReading(response.dataJson) else null
}

/** What a `tools/call` answer reads as for a watch ([readMcpTool]); null for an error or an answer that is not JSON. */
internal fun toolReading(data: JsonObject?): JsonObject? {
    val result = data?.get("result") as? JsonObject ?: return null
    if ((result["isError"] as? JsonPrimitive)?.contentOrNull == "true") return null
    (result["structuredContent"] as? JsonObject)?.let { return it }
    return (result["content"] as? JsonArray).orEmpty().firstNotNullOfOrNull { content ->
        ((content as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull?.let(::parseObject)
    }
}

/** The first reading in a `resources/read` answer, as [resourceReadings] finds them; null when there is none. */
internal fun resourceData(data: JsonObject?): JsonObject? = resourceReadings(data).firstOrNull()

/**
 * The readings in a `resources/read` answer: each `result.contents[].text` as JSON, unwrapped from a
 * `{"data":{…},"ts":…,"uri":…}` envelope; an error is none, and a missing or non-JSON text is skipped.
 */
internal fun resourceReadings(data: JsonObject?): List<JsonObject> {
    val result = data?.get("result") as? JsonObject ?: return emptyList()
    return (result["contents"] as? JsonArray).orEmpty().mapNotNull { content ->
        val text = ((content as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val reading = parseObject(text) ?: return@mapNotNull null
        (reading["data"] as? JsonObject)?.takeIf { reading.containsKey("uri") } ?: reading
    }
}

/**
 * What the loaded MCP servers offer to watch, through `resources/list` on each server that is on; fetched again after
 * [RESOURCE_LIST_TTL_MS], so a server added or switched on in 设置 shows up without a restart.
 */
@AppServerPluginApi
object McpResourceCatalog {
    private val cached = ConcurrentHashMap<String, Pair<Long, List<McpResource>>>()

    /**
     * The servers are asked together, each for at most [RESOURCE_LIST_WAIT_MS]. A server that could not be asked, or
     * whose listing broke off or ran late, counts as having none for [FAILED_LIST_TTL_MS] (only a whole listing is kept
     * longer): otherwise one slow server costs a plan's watch its wait twice, once to list and once to check the watch.
     */
    suspend fun resources(context: Context, now: Long = System.currentTimeMillis()): List<McpResource> = coroutineScope {
        loadMcpServers(context).filter { it.enabled }.map { server ->
            async {
                cached[server.id]?.takeIf { (at, list) -> now - at < if (list.isEmpty()) FAILED_LIST_TTL_MS else RESOURCE_LIST_TTL_MS }?.second
                    ?: (withTimeoutOrNull(RESOURCE_LIST_WAIT_MS) { fetch(context, server) } ?: emptyList()).also { cached[server.id] = now to it }
            }
        }.awaitAll().flatten()
    }

    private suspend fun fetch(context: Context, server: McpServer): List<McpResource>? {
        val client = mcpResourceClient(context, server.id) ?: return null
        val listed = mutableListOf<McpResource>()
        var cursor: String? = null
        repeat(MAX_LIST_PAGES) {
            val params = buildJsonObject { cursor?.let { put("cursor", it) } }
            val result = client.sendRequest(RESOURCES_LIST, params).takeIf { it.ok }?.dataJson?.get("result") as? JsonObject ?: return null
            listed += listedResources(server, result)
            cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull ?: return listed
        }
        return listed
    }

    fun forget(serverId: String) {
        cached.remove(serverId)
    }
}

internal fun listedResources(server: McpServer, result: JsonObject): List<McpResource> =
    (result["resources"] as? JsonArray).orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        fun text(name: String) = (item[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val uri = text("uri").takeIf(String::isNotBlank) ?: return@mapNotNull null
        McpResource(server.id, server.label, uri, text("name"), text("description"))
    }

private fun parseObject(text: String): JsonObject? = try {
    Json.parseToJsonElement(text) as? JsonObject
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private const val RESOURCES_READ = "resources/read"
private const val TOOLS_CALL = "tools/call"
private const val RESOURCES_LIST = "resources/list"
private const val MAX_LIST_PAGES = 10
private const val RESOURCE_LIST_TTL_MS = 5 * 60 * 1000L
private const val RESOURCE_LIST_WAIT_MS = 5_000L
private const val FAILED_LIST_TTL_MS = 2 * 60 * 1000L

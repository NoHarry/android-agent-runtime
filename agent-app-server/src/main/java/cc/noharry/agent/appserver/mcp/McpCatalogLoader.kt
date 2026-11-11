package cc.noharry.agent.appserver.mcp
import android.content.Context
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun loadMcpCatalog(context: Context): CatalogData? =
    try {
        parseMcpCatalog(context.assets.open(MCP_TOOLS_ASSET).use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        })
    } catch (_: IOException) {
        null
    }

internal fun parseMcpCatalog(toolsJson: String): CatalogData? =
    try {
        parseToolsJson(toolsJson)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun parseToolsJson(toolsJson: String): CatalogData? {
    val root = Json.parseToJsonElement(toolsJson) as? JsonObject ?: return null
    val tools = root.toolsArray().mapNotNull(::parseTool)
    if (tools.isEmpty()) return null
    return CatalogData(tools = tools)
}

private fun parseTool(element: kotlinx.serialization.json.JsonElement): CatalogTool? {
    val item = element as? JsonObject ?: return null
    val name = item.stringField("name") ?: return null
    return CatalogTool(
        name = name,
        displayName = item.stringField("title") ?: item.stringField("description") ?: name,
        description = item.stringField("description").orEmpty(),
        inputSchemaJson = (item["inputSchema"] as? JsonObject)?.toString() ?: EMPTY_SCHEMA_JSON,
    )
}

private fun JsonObject.toolsArray(): List<kotlinx.serialization.json.JsonElement> =
    ((this["result"] as? JsonObject)?.get("tools") as? JsonArray).orEmpty()

private fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

internal data class CatalogData(
    val tools: List<CatalogTool>,
) {
    private val toolsByName = tools.associateBy { it.name }

    fun tool(toolName: String): CatalogTool? = toolsByName[toolName]
}

internal data class CatalogTool(
    val name: String,
    val displayName: String,
    val description: String,
    val inputSchemaJson: String,
)

private const val MCP_TOOLS_ASSET = "mcp/tools.json"
private const val EMPTY_SCHEMA_JSON = """{"type":"object","additionalProperties":true}"""

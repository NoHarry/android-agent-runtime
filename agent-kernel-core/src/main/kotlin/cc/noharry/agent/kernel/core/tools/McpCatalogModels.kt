package cc.noharry.agent.kernel.core.tools
import cc.noharry.agent.kernel.api.ToolProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class McpCatalogSnapshot(
    val servers: List<McpServerSnapshot>,
    val preparationTimingsMs: Map<String, Long> = emptyMap(),
) {
    val tools: List<McpToolRef> = servers.flatMap { it.tools }.sortedBy { it.qualifiedName }

    fun tool(qualifiedName: String): McpToolRef? =
        tools.firstOrNull { it.qualifiedName == qualifiedName }
}

data class McpServerSnapshot(
    val provider: ToolProvider,
    val serverId: String,
    val normalizedServerId: String,
    val displayName: String,
    val status: String,
    val message: String?,
    val tools: List<McpToolRef>,
) {
    fun matches(value: String): Boolean =
        value == serverId || value == normalizedServerId
}

fun McpServerSnapshot.serverJson(includeToolCount: Boolean): JsonObject =
    buildJsonObject {
        put("id", serverId)
        put("normalizedId", normalizedServerId)
        put("displayName", displayName)
        put("status", status)
        if (includeToolCount) put("toolCount", tools.size)
        message?.let { put("message", it) }
    }

fun McpToolRef.toolJson(): JsonObject =
    buildJsonObject {
        put("name", qualifiedName)
        put("serverId", serverId)
        put("normalizedServerId", normalizedServerId)
        put("originalName", originalToolName)
        put("normalizedName", normalizedToolName)
        descriptor.title?.let { put("title", it) }
        put("description", descriptor.description)
        put("schemaPath", "/mcp/tools/$qualifiedName/schema.json")
        put("cardPath", "/mcp/tools/$qualifiedName/card.json")
    }

fun McpToolRef.indexJson(): JsonObject =
    buildJsonObject {
        put("name", qualifiedName)
        put("serverId", serverId)
        put("originalName", originalToolName)
        descriptor.title?.let { put("title", it) }
        put("description", descriptor.description)
        put("schemaPath", "/mcp/tools/$qualifiedName/schema.json")
    }

fun McpToolRef.cardJson(): JsonObject =
    buildJsonObject {
        put("name", qualifiedName)
        put("serverId", serverId)
        put("normalizedServerId", normalizedServerId)
        put("originalName", originalToolName)
        put("normalizedName", normalizedToolName)
        descriptor.title?.let { put("title", it) }
        put("description", descriptor.description)
        put("schemaPath", "/mcp/tools/$qualifiedName/schema.json")
        descriptor.outputSchemaJson?.let { raw ->
            (runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject)?.let { put("outputSchema", it) }
        }
    }

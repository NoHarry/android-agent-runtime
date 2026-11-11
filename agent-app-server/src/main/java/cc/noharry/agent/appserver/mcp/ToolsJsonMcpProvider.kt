package cc.noharry.agent.appserver.mcp
import android.content.Context
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolCallTrace
import cc.noharry.agent.spi.ToolCatalogCacheIdentity
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.mcp.McpClientProvider
import cc.noharry.agent.appserver.tools.SchemaValidation
import cc.noharry.agent.appserver.tools.validateInputSchema

@cc.noharry.agent.appserver.AppServerPluginApi
class ToolsJsonMcpProvider internal constructor(
    private val catalog: CatalogData,
) : McpClientProvider, ToolCatalogCacheIdentity {
    override val serverId: String = SERVER_ID
    override val displayName: String = "tools.json"
    override val catalogCacheIdentity: String = "app-server:$SERVER_ID:v1"
    @OptIn(ExperimentalAgentRuntimeApi::class)
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe()

    override suspend fun listTools(): List<ToolDescriptor> =
        catalog.tools.map { tool ->
            ToolDescriptor(
                name = tool.name,
                description = tool.description,
                inputSchemaJson = tool.inputSchemaJson,
                title = tool.displayName,
            )
        }

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult {
        val tool = catalog.tool(toolName) ?: return ToolCallResult(
            success = false,
            resultJson = unknownToolResult(toolName),
            trace = ToolCallTrace(
                validationOk = false,
                validationCode = UNKNOWN_TOOL_CODE,
                validationMessage = "Unknown tools.json catalog tool.",
            ),
        )
        return when (val validation = validateInputSchema(tool.inputSchemaJson, argumentsJson)) {
            SchemaValidation.Valid -> ToolCallResult(
                success = true,
                resultJson = fixtureResult(tool, argumentsJson, context),
                trace = ToolCallTrace(validationOk = true, validationCode = OK_CODE),
            )
            is SchemaValidation.Invalid -> ToolCallResult(
                success = false,
                resultJson = validationResult(toolName, validation),
                trace = ToolCallTrace(
                    validationOk = false,
                    validationCode = validation.code,
                    validationMessage = validation.message,
                    validationSchemaPath = validation.schemaPath,
                ),
            )
        }
    }

    private fun fixtureResult(
        tool: CatalogTool,
        argumentsJson: String,
        context: ToolCallContext,
    ): String =
        """
            {
              "ok": true,
              "tool": "${tool.name.escapeJson()}",
              "serverId": "$SERVER_ID",
              "runId": "${context.runId.escapeJson()}",
              "requestId": "${context.requestId.escapeJson()}",
              "argumentsLength": ${argumentsJson.length},
              "statusCode": 10000,
              "message": "tools.json provider accepted the catalog tool call."
            }
        """.trimIndent()

    companion object {
        fun fromAssets(context: Context): ToolsJsonMcpProvider =
            ToolsJsonMcpProvider(loadMcpCatalog(context) ?: CatalogData(emptyList()))
    }
}

private fun unknownToolResult(toolName: String): String =
    """{"ok":false,"code":"$UNKNOWN_TOOL_CODE","tool":"${toolName.escapeJson()}","message":"Unknown tools.json catalog tool."}"""

private fun validationResult(toolName: String, validation: SchemaValidation.Invalid): String =
    """{"ok":false,"code":"${validation.code.escapeJson()}","tool":"${toolName.escapeJson()}","message":"${validation.message.escapeJson()}","schemaPath":"${validation.schemaPath.orEmpty().escapeJson()}"}"""

private fun String.escapeJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

private const val SERVER_ID = "tools_json"
private const val OK_CODE = "OK"
private const val UNKNOWN_TOOL_CODE = "UNKNOWN_DEBUG_TOOL"

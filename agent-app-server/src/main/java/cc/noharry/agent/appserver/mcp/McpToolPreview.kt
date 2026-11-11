package cc.noharry.agent.appserver.mcp
import android.content.Context
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.McpProviderState
import cc.noharry.agent.appserver.protocol.McpServer
import cc.noharry.agent.appserver.protocol.McpToolPreview
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.mcp.McpRpcClient
import cc.noharry.agent.mcp.listMcpTools
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface McpToolsResult {
    data class Loaded(val tools: List<McpToolPreview>) : McpToolsResult
    data class Unavailable(val reason: Finding) : McpToolsResult
}

/**
 * Loads the tool catalog a provider exposes, for the "what's loaded" preview in MCP settings. A configured server
 * (a standard MCP server over HTTP) goes through a live tools/list on the app's connection to
 * it; the bundled tools and a plugin's tool server answer locally.
 */
internal suspend fun loadMcpProviderTools(context: Context, provider: McpProviderState): McpToolsResult {
    provider.server?.let { return loadServerTools(context.applicationContext, it) }
    return when (provider.id) {
        DEBUG_MCP_PROVIDER_TOOLS_JSON -> {
            val catalog = loadMcpCatalog(context.applicationContext)
            if (catalog == null) {
                McpToolsResult.Unavailable(finding(FindingCode.MCP_TOOLS_JSON_MISSING))
            } else {
                McpToolsResult.Loaded(
                    catalog.tools.map { tool ->
                        McpToolPreview(
                            name = tool.name,
                            title = tool.displayName,
                            description = tool.description,
                        )
                    },
                )
            }
        }
        else -> AppHost.additions.toolServers.firstOrNull { it.id == provider.id }
            ?.provider?.invoke(context.applicationContext) { it }?.listTools()?.toPreviewResult()
            ?: McpToolsResult.Unavailable(finding(FindingCode.MCP_UNKNOWN_PROVIDER, provider.id))
    }
}

private suspend fun loadServerTools(context: Context, server: McpServer): McpToolsResult {
    return withTimeoutOrNull(SERVER_PREVIEW_TIMEOUT_MS) {
        val client: McpRpcClient = McpHttpClients.get(context, server)
        val connect = client.connect()
        if (!connect.ok) {
            McpHttpClients.close(server.id)
            return@withTimeoutOrNull McpToolsResult.Unavailable(
                connect.errorMessage?.let(::text) ?: finding(FindingCode.MCP_SERVER_UNREACHABLE, server.label),
            )
        }
        val listing = listMcpTools(client, ScreenMcpMessages(context))
        listing.problem?.let { McpToolsResult.Unavailable(text(it)) } ?: listing.tools.toPreviewResult()
    } ?: McpToolsResult.Unavailable(finding(FindingCode.MCP_SERVER_TOOLS_TIMEOUT, server.label))
}

private fun List<ToolDescriptor>.toPreviewResult(): McpToolsResult =
    McpToolsResult.Loaded(
        map { descriptor ->
            McpToolPreview(
                name = descriptor.name,
                title = descriptor.title ?: descriptor.name,
                description = descriptor.description,
            )
        },
    )

private const val SERVER_PREVIEW_TIMEOUT_MS = 60_000L

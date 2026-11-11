package cc.noharry.agent.appserver.server

import android.content.Context
import cc.noharry.agent.appserver.mcp.McpSecrets
import cc.noharry.agent.appserver.mcp.McpToolsResult
import cc.noharry.agent.appserver.mcp.fixedMcpProviderTitle
import cc.noharry.agent.appserver.mcp.loadMcpProviderTools
import cc.noharry.agent.appserver.mcp.loadMcpProviders
import cc.noharry.agent.appserver.mcp.loadMcpServers
import cc.noharry.agent.appserver.protocol.McpRequests
import cc.noharry.agent.appserver.protocol.McpSecretsView
import cc.noharry.agent.appserver.protocol.McpServerKind
import cc.noharry.agent.appserver.protocol.McpStatusView
import cc.noharry.agent.appserver.protocol.McpToolsView
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcErrorCodes
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.runtime.AppHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** MCP status: the providers' names, their stored secrets (never their values) and each one's tool list. */
internal class McpRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(McpRequests.STATUS) {
            withContext(Dispatchers.IO) {
                // The tool server that sees more with notification access (the device's media), if a plugin brings one.
                val access = AppHost.additions.toolServers.firstOrNull { it.notificationAccess != null }
                McpStatusView(
                    titles = loadMcpProviders(app).mapNotNull { row -> fixedMcpProviderTitle(app, row.id)?.let { row.id to it } }
                        .toMap(),
                    secrets = loadMcpServers(app).filter { it.kind == McpServerKind.HTTP }.associate { it.id to secretsOf(it.id) },
                    mediaProviderId = access?.id.orEmpty(),
                    mediaAccess = access?.notificationAccess?.invoke(app) == true,
                )
            }
        }
        on(McpRequests.TOOLS) { params ->
            // By id only: a configured server is reached with its own stored secrets, at its own stored address.
            val provider = loadMcpProviders(app).firstOrNull { it.id == params.id }
                ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "no MCP provider ${params.id}")
            when (val result = loadMcpProviderTools(app, provider)) {
                is McpToolsResult.Loaded -> McpToolsView(tools = result.tools)
                is McpToolsResult.Unavailable -> McpToolsView(unavailable = result.reason)
            }
        }
    }

    private fun secretsOf(id: String): McpSecretsView = McpSecrets.read(app, id).let {
        McpSecretsView(token = it.token.isNotEmpty(), headers = it.headers.size, query = it.query.isNotEmpty())
    }
}

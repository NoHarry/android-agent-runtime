package cc.noharry.agent.appserver.mcp
import android.content.Context
import cc.noharry.agent.appserver.R
import cc.noharry.agent.appserver.protocol.McpProviderState
import cc.noharry.agent.appserver.protocol.McpServer
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.server.PluginToolServer

/** Tools JSON, then the configured standard MCP servers, then the running plugins' tool servers ([PluginToolServer]). */
@cc.noharry.agent.appserver.AppServerPluginApi
fun loadMcpProviders(context: Context): List<McpProviderState> {
    val prefs = context.getSharedPreferences(MCP_PREFS_NAME, Context.MODE_PRIVATE)
    return providerRows(loadMcpServers(context)) { id, onAtFirst -> prefs.getBoolean(switchKey(id), onAtFirst) }
}

internal fun defaultMcpProviders(): List<McpProviderState> =
    providerRows(emptyList()) { _, onAtFirst -> onAtFirst }

internal fun saveMcpProviderEnabled(context: Context, id: String, enabled: Boolean): List<McpProviderState> {
    if (id == DEBUG_MCP_PROVIDER_TOOLS_JSON || AppHost.additions.toolServers.any { it.id == id }) {
        context.getSharedPreferences(MCP_PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(switchKey(id), enabled).apply()
    } else {
        setMcpServerEnabled(context, id, enabled)
    }
    return loadMcpProviders(context)
}

internal fun List<McpProviderState>.enabledProviderLabels(): List<String> =
    filter { it.enabled }.map { it.label }

internal fun List<McpProviderState>.isProviderEnabled(id: String): Boolean =
    firstOrNull { it.id == id }?.enabled == true

/**
 * Whether a run mounts the plugin's [server]: as its row in [providers] says, or, for a plugin started after those rows
 * were read, as its switch was last saved.
 */
internal fun pluginToolServerOn(context: Context, providers: List<McpProviderState>, server: PluginToolServer): Boolean =
    providers.firstOrNull { it.id == server.id }?.enabled
        ?: context.getSharedPreferences(MCP_PREFS_NAME, Context.MODE_PRIVATE).getBoolean(switchKey(server.id), server.onAtFirst)

/** The name the screen shows for a provider that is not a configured server, in the app's language; null for one. */
internal fun fixedMcpProviderTitle(context: Context, id: String): String? = when (id) {
    DEBUG_MCP_PROVIDER_TOOLS_JSON -> context.getString(R.string.mcp_provider_tools_json)
    else -> AppHost.additions.toolServers.firstOrNull { it.id == id }?.title?.invoke(context)
}

/** The ids no configured server takes: the bundled tools' and the running plugins' tool servers'. */
internal fun fixedProviderIds(): Set<String> = setOf(DEBUG_MCP_PROVIDER_TOOLS_JSON) + AppHost.additions.toolServers.map { it.id }

private fun providerRows(
    servers: List<McpServer>,
    enabled: (id: String, onAtFirst: Boolean) -> Boolean,
): List<McpProviderState> {
    val bundled = McpProviderState(DEBUG_MCP_PROVIDER_TOOLS_JSON, TOOLS_JSON_LABEL, enabled(DEBUG_MCP_PROVIDER_TOOLS_JSON, false))
    val configured = servers.map { McpProviderState(it.id, it.label, it.enabled, it) }
    val plugins = AppHost.additions.toolServers.map { McpProviderState(it.id, it.label, enabled(it.id, it.onAtFirst)) }
    return listOf(bundled) + configured + plugins
}

/** Where a fixed provider's switch is kept: the bundled tools', a plugin's tool server's. */
private fun switchKey(id: String): String = "mcp_provider_$id"

@cc.noharry.agent.appserver.AppServerPluginApi
const val DEBUG_MCP_PROVIDER_TOOLS_JSON = "tools_json"
private const val TOOLS_JSON_LABEL = "Tools JSON"

internal const val MCP_PREFS_NAME = "app_server_mcp_provider_config"

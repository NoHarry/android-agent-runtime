@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.mcp

import android.content.Context
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.server.PluginMcpServer
import cc.noharry.agent.mcp.McpHttpClient
import cc.noharry.agent.mcp.McpToolProvider
import cc.noharry.agent.spi.RuntimeEffectKind
import cc.noharry.agent.spi.ToolDescriptor
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The running plugins' MCP servers ([PluginMcpServer]) as runs reach them: each one's connection and tool list, kept
 * while the same server runs (a plugin started again brings new ones), and which of its tools say they only read (MCP
 * `readOnlyHint`, taken only from a server whose word is taken), for the gate on the others.
 */
internal object PluginMcpServers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = HashMap<String, Entry>()

    /** [server]'s tools for one run, as `mcp__<name>__<tool>`. */
    fun provider(context: Context, name: String, server: PluginMcpServer): McpToolProvider {
        val entry = entry(context.applicationContext, name, server)
        return McpToolProvider(
            client = entry.client,
            serverId = name,
            displayName = server.label,
            catalog = entry.catalog,
            describe = { tool -> AppHost.runs.describeTool(entry.noted(tool)) },
        )
    }

    /** Whether [tool] of the server named [name] said it only reads when its list was last read; false when unknown. */
    fun readsOnly(name: String, tool: String): Boolean = synchronized(entries) { entries[name] }?.readOnly?.contains(tool) == true

    /** Whether [name] is a name a running plugin's server has gone by in a run (one it no longer has counts too). */
    fun isPluginServer(name: String): Boolean = synchronized(entries) { name in entries }

    /** The plugins changed: the connections of servers no running plugin brings any more close. */
    fun retain(servers: List<PluginMcpServer>) {
        val gone = synchronized(entries) {
            val names = entries.filterValues { entry -> servers.none { it === entry.server } }.keys
            names.mapNotNull(entries::remove)
        }
        gone.forEach { it.client.close() }
    }

    private fun entry(app: Context, name: String, server: PluginMcpServer): Entry = synchronized(entries) {
        entries[name]?.takeIf { it.server === server }
            // A plugin's server reaches the host it named and no other: a redirect is not followed.
            ?: Entry(server, McpHttpClients.create(app, server.url, server.headers, server.query, followRedirects = false), McpToolCatalogCache(scope))
                .also { entries.put(name, it)?.client?.close() }
    }

    private class Entry(val server: PluginMcpServer, val client: McpHttpClient, val catalog: McpToolCatalogCache) {
        val readOnly: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun noted(tool: ToolDescriptor): ToolDescriptor = servedTool(tool, server).also {
            if (it.effectKind == RuntimeEffectKind.READ_ONLY.name) readOnly += it.name else readOnly -= it.name
        }
    }
}

/**
 * [tool] as runs see it from [server]: one the server marks read-only stays so only when the server's word is taken
 * ([PluginMcpServer.readOnlyHints]); otherwise it counts as one that acts.
 */
internal fun servedTool(tool: ToolDescriptor, server: PluginMcpServer): ToolDescriptor =
    if (server.readOnlyHints || tool.effectKind != RuntimeEffectKind.READ_ONLY.name) tool else tool.copy(effectKind = RuntimeEffectKind.UNKNOWN.name)

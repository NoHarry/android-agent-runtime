package cc.noharry.agent.appserver.mcp
import android.content.Context
import android.util.Log
import cc.noharry.agent.appserver.protocol.McpServer
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.mcp.McpRpcClient
import cc.noharry.agent.mcp.McpToolCatalog
import cc.noharry.agent.mcp.listMcpToolsCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Host-owned MCP tool catalog. A live `tools/list` can take seconds, so the catalog is fetched when the app starts and
 * again whenever a server's switch changes; every run in between reuses it.
 *
 * This is deliberately not the Kernel's environment catalog cache: a server may expose no catalog version, so
 * freshness is decided by these explicit host refresh points instead of a claimed stable identity. A failed or
 * empty fetch is never cached. Optional servers can defer retries after a failure without blocking later runs.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
class McpToolCatalogCache(
    private val scope: CoroutineScope,
    private val failureRetryDelayMillis: Long = 0L,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : McpToolCatalog {
    private val lock = Any()
    private var generation = 0L
    private var cached: List<ToolDescriptor>? = null
    private var pending: Deferred<List<ToolDescriptor>>? = null
    private var retryAtMillis: Long? = null

    /** Returns the cached catalog, joins an in-flight fetch, or fetches with [fetch] and caches a non-empty result. */
    override suspend fun get(fetch: suspend () -> List<ToolDescriptor>): List<ToolDescriptor> {
        val (deferred, startedHere) = synchronized(lock) {
            cached?.let { return it }
            retryAtMillis?.let { retryAt ->
                if (pending == null && nowMillis() >= retryAt) startLocked(fetch)
                return emptyList()
            }
            pending?.let { it to false } ?: (startLocked(fetch) to true)
        }
        val tools = awaitOrEmpty(deferred)
        if (tools.isNotEmpty() || startedHere || failureRetryDelayMillis > 0) return tools
        // A background refresh came back empty (for example the server was not ready at app start):
        // retry once with this run's own connection instead of running without backend tools.
        val retry = synchronized(lock) {
            cached?.let { return it }
            if (pending === deferred) pending = null
            pending ?: startLocked(fetch)
        }
        return awaitOrEmpty(retry)
    }

    /** Drops the current catalog and starts a background fetch whose result serves the following runs. */
    fun refresh(fetch: suspend () -> List<ToolDescriptor>) {
        synchronized(lock) {
            generation++
            cached = null
            pending = null
            retryAtMillis = null
            startLocked(fetch)
        }
    }

    /** Drops the catalog; an in-flight fetch from before this call can no longer populate the cache. */
    internal fun invalidate() {
        synchronized(lock) {
            generation++
            cached = null
            pending = null
            retryAtMillis = null
        }
    }

    internal fun cachedForTest(): List<ToolDescriptor>? = synchronized(lock) { cached }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startLocked(fetch: suspend () -> List<ToolDescriptor>): Deferred<List<ToolDescriptor>> {
        val startedGeneration = generation
        val deferred = scope.async { fetch() }
        pending = deferred
        deferred.invokeOnCompletion { error ->
            synchronized(lock) {
                if (pending === deferred) pending = null
                if (startedGeneration != generation || error is CancellationException) return@synchronized
                val tools = if (error == null) deferred.getCompleted() else emptyList()
                if (tools.isNotEmpty()) {
                    cached = tools
                    retryAtMillis = null
                } else if (failureRetryDelayMillis > 0) {
                    retryAtMillis = nowMillis() + failureRetryDelayMillis
                }
            }
        }
        return deferred
    }

    private suspend fun awaitOrEmpty(deferred: Deferred<List<ToolDescriptor>>): List<ToolDescriptor> =
        try {
            deferred.await()
        } catch (cancelled: CancellationException) {
            if (!deferred.isCancelled) throw cancelled
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
}

/**
 * Process-wide catalogs, one per configured MCP server, used by every run's provider, the app
 * start hook, the switches and the server edits.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
object McpToolCatalogs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val caches = java.util.concurrent.ConcurrentHashMap<String, McpToolCatalogCache>()

    fun cache(serverId: String): McpToolCatalogCache = caches.getOrPut(serverId) { McpToolCatalogCache(scope) }

    /** [server]'s catalog: the cached one, or a fetch whose result is cached for the following runs. */
    suspend fun tools(context: Context, server: McpServer): List<ToolDescriptor> =
        cache(server.id).get { fetch(context.applicationContext, server) }

    /** App start: fetch in the background for each enabled server so the first run has its tools. */
    internal fun refreshOnAppStart(context: Context) {
        val appContext = context.applicationContext
        loadMcpServers(appContext).filter { it.enabled }.forEach { server ->
            cache(server.id).refresh { fetch(appContext, server) }
        }
    }

    /** A server's switch: turning it on fetches a fresh catalog, turning it off drops the cached one and the connection. */
    internal fun onServerSwitch(context: Context, server: McpServer, enabled: Boolean) {
        if (enabled) {
            val appContext = context.applicationContext
            cache(server.id).refresh { fetch(appContext, server) }
        } else {
            forget(server.id)
        }
    }

    /** A server was edited or deleted: its connection and catalog belong to the old settings. */
    internal fun onServerChanged(context: Context, serverId: String) {
        forget(serverId)
        loadMcpServers(context).firstOrNull { it.id == serverId && it.enabled }?.let { onServerSwitch(context, it, true) }
    }

    private fun forget(serverId: String) {
        cache(serverId).invalidate()
        McpResourceCatalog.forget(serverId)
        McpHttpClients.close(serverId)
    }

    /** A server that does not answer is closed again. */
    private suspend fun fetch(context: Context, server: McpServer): List<ToolDescriptor> {
        val client: McpRpcClient = McpHttpClients.get(context, server)
        val started = System.nanoTime()
        val connected = client.connect()
        if (!connected.ok) forget(server.id)
        val tools = if (connected.ok) listMcpToolsCatalog(client) else emptyList()
        Log.i(LOG_TAG, "${server.label} catalog refresh: ${tools.size} tools in ${(System.nanoTime() - started) / 1_000_000}ms")
        return tools
    }

    private const val LOG_TAG = "McpCatalog"
}

package cc.noharry.agent.kernel.core.tools
import cc.noharry.agent.kernel.api.AgentCapabilities
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.SessionCapabilityContext
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolCatalogCacheIdentity
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolInitializeContext
import cc.noharry.agent.kernel.api.ToolInitializeResult
import cc.noharry.agent.kernel.api.ToolPrefetchProvider
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.api.ToolProviderStatus
import cc.noharry.agent.kernel.core.loop.failed
import cc.noharry.agent.kernel.core.plugin.RuntimeBlueprint
import cc.noharry.agent.kernel.core.trace.RUNTIME_COUNTER_PROVIDER_CATALOG_CACHE_HITS
import cc.noharry.agent.kernel.core.trace.RUNTIME_COUNTER_PROVIDER_CATALOG_CACHE_MISSES
import cc.noharry.agent.kernel.core.trace.RuntimeVfsPerformanceCounters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@OptIn(ExperimentalAgentRuntimeApi::class)
class ToolProviderRegistry(
    providers: List<ToolProvider> = emptyList(),
    private val concurrencyLimiter: ProviderConcurrencyLimiter = ProviderConcurrencyLimiter(),
    private val blueprint: RuntimeBlueprint? = null,
) {
    private val staticProviders = providers.toList()
    private val snapshotLock = Mutex()
    private val snapshotCache = linkedMapOf<CatalogSnapshotKey, McpCatalogSnapshot>()
    private val snapshotKeysBySession = linkedMapOf<String, MutableSet<CatalogSnapshotKey>>()
    private val sessionProvidersLock = Any()
    private val sessionProviders = linkedMapOf<String, List<ToolProvider>>()

    init {
        staticProviders.forEach { provider ->
            requireSupportedDirectProvider(provider.providerConcurrency)
        }
    }

    val isConfigured: Boolean
        get() = staticProviders.isNotEmpty() ||
            blueprint?.resolveSharedAll(AgentCapabilities.TOOL_PROVIDER).orEmpty().isNotEmpty() ||
            blueprint?.factoryKeys()?.any { it === AgentCapabilities.TOOL_PROVIDER } == true

    suspend fun serversJson(context: MaterializerContext): String {
        val catalog = snapshot(context)
        return buildJsonObject {
            putJsonArray("servers") {
                catalog.servers.forEach { server ->
                    add(server.serverJson(includeToolCount = true))
                }
            }
        }.toString()
    }

    suspend fun toolsJson(context: MaterializerContext): String {
        val catalog = snapshot(context)
        return toolsPayloadJson(catalog.tools.map { tool -> tool.toolJson() })
    }

    suspend fun toolsIndexJson(context: MaterializerContext): String {
        val catalog = snapshot(context)
        return toolsPayloadJson(catalog.tools.map { tool -> tool.indexJson() })
    }

    suspend fun serverToolsJson(serverId: String, context: MaterializerContext): String? {
        val server = snapshot(context).servers.singleOrNull { it.matches(serverId) } ?: return null
        return buildJsonObject {
            put("serverId", server.serverId)
            put("normalizedServerId", server.normalizedServerId)
            putJsonArray("tools") {
                server.tools.forEach { tool -> add(tool.toolJson()) }
            }
        }.toString()
    }

    suspend fun toolCardJson(qualifiedName: String, context: MaterializerContext): String? =
        snapshot(context).tool(qualifiedName)?.cardJson()?.toString()

    suspend fun toolSchema(qualifiedName: String, context: MaterializerContext): McpResolvedSchema? {
        val tool = snapshot(context).tool(qualifiedName) ?: return null
        return McpResolvedSchema(tool = tool, schemaJson = tool.descriptor.inputSchemaJson)
    }

    suspend fun callTool(
        qualifiedName: String,
        argumentsJson: String,
        context: MaterializerContext,
    ): McpRoutedCallResult? {
        val tool = snapshot(context).tool(qualifiedName) ?: return null
        val result = withProviderPermit(tool.provider) {
            tool.provider.callTool(tool.originalToolName, argumentsJson, context.toMcpCallContext())
        }
        return McpRoutedCallResult(tool, result)
    }

    suspend fun resourcesIndexJson(context: MaterializerContext): String {
        val resources = mutableListOf<JsonObject>()
        providersFor(context).forEach { provider ->
            listConnectedContribution(provider, context) { listResources() }.forEach { resource ->
                resources += buildJsonObject {
                    put("serverId", provider.providerId)
                    put("uri", resource.uri)
                    resource.name?.let { put("name", it) }
                    resource.description?.let { put("description", it) }
                }
            }
        }
        return buildJsonObject {
            putJsonArray("resources") {
                resources.forEach(::add)
            }
        }.toString()
    }

    suspend fun promptsIndexJson(context: MaterializerContext): String {
        val prompts = mutableListOf<JsonObject>()
        providersFor(context).forEach { provider ->
            listConnectedContribution(provider, context) { listPrompts() }.forEach { prompt ->
                prompts += buildJsonObject {
                    put("serverId", provider.providerId)
                    put("name", prompt.name)
                    prompt.description?.let { put("description", it) }
                }
            }
        }
        return buildJsonObject {
            putJsonArray("prompts") {
                prompts.forEach(::add)
            }
        }.toString()
    }

    suspend fun toolsForServer(serverId: String, context: MaterializerContext): List<McpToolRef> =
        snapshot(context).servers.singleOrNull { it.matches(serverId) }?.tools.orEmpty()

    suspend fun allTools(context: MaterializerContext): List<McpToolRef> =
        snapshot(context).tools

    /** Actual preparation of this run's catalog snapshot; cache hits do not repeat original fetch timings. */
    suspend fun preparationTimings(context: MaterializerContext): Map<String, Long> =
        snapshot(context).preparationTimingsMs.toMap()

    /** Current-catalog groups opting into advisory prefetch; ordinary providers do no extra I/O. */
    internal suspend fun prefetchCatalog(context: MaterializerContext): Map<ToolProvider, List<McpToolRef>> {
        if (providersFor(context).none { it is ToolPrefetchProvider }) return emptyMap()
        return snapshot(context).tools.filter { it.provider is ToolPrefetchProvider }.groupBy { it.provider }
    }

    suspend fun toolRef(qualifiedName: String, context: MaterializerContext): McpToolRef? =
        snapshot(context).tool(qualifiedName)

    internal suspend fun selectPrefetchedTools(
        provider: ToolProvider,
        request: cc.noharry.agent.kernel.api.ToolPrefetchRequest,
        trace: cc.noharry.agent.kernel.api.ToolPrefetchTrace,
    ): List<String> = withProviderPermit(provider) {
        (provider as ToolPrefetchProvider).prefetchTools(request, trace)
    }

    suspend fun serverDirectoryNames(context: MaterializerContext): List<String> =
        snapshot(context).servers.map { it.normalizedServerId }.sorted()

    private suspend fun snapshot(context: MaterializerContext): McpCatalogSnapshot {
        val key = context.catalogSnapshotKey()
        snapshotLock.withLock { snapshotCache[key] }?.let { return it }
        val created = buildSnapshot(context)
        return snapshotLock.withLock {
            snapshotCache[key] ?: created.also { snapshot ->
                snapshotCache[key] = snapshot
                rememberSnapshotKey(context.sessionId, key)
                evictOldSnapshots()
            }
        }
    }

    private suspend fun buildSnapshot(context: MaterializerContext): McpCatalogSnapshot {
        val started = System.nanoTime()
        val timings = linkedMapOf<String, Long>()
        val qualifiedCounts = linkedMapOf<String, Int>()
        val usedServerIds = linkedMapOf<String, Int>()
        val servers = providersFor(context).mapIndexed { index, provider ->
            val catalog = providerCatalog(index, provider, context, timings).disambiguate(index, usedServerIds)
            McpServerSnapshot(
                provider = provider,
                serverId = catalog.serverId,
                normalizedServerId = catalog.normalizedServerId,
                displayName = catalog.displayName,
                status = catalog.status,
                message = catalog.message,
                tools = catalog.toolRefs(provider, qualifiedCounts),
            )
        }
        timings["catalogSnapshotMs"] = (System.nanoTime() - started) / NANOS_PER_MILLI
        return McpCatalogSnapshot(servers, timings.toMap())
    }

    private suspend fun providerCatalog(
        providerIndex: Int,
        provider: ToolProvider,
        context: MaterializerContext,
        timings: MutableMap<String, Long>,
    ): McpProviderCatalog {
        val useEnvironmentCache = usesEnvironmentCatalogCache(provider)
        val cacheKey = provider.catalogCacheKey(providerIndex)
        if (useEnvironmentCache) {
            val cached = environmentProviderCatalogLock.withLock {
                environmentProviderCatalogCache[cacheKey]
            }
            if (cached != null) {
                RuntimeVfsPerformanceCounters.increment(RUNTIME_COUNTER_PROVIDER_CATALOG_CACHE_HITS)
                return cached
            }
        }
        RuntimeVfsPerformanceCounters.increment(RUNTIME_COUNTER_PROVIDER_CATALOG_CACHE_MISSES)
        val fetched = withProviderPermit(provider) {
            val initialized = measureCatalog(timings, "provider.$providerIndex.initializeMs") { initialize(provider, context) }
            val descriptors = if (initialized.status == ToolProviderStatus.CONNECTED) {
                measureCatalog(timings, "provider.$providerIndex.listToolsMs") { listToolDescriptors(provider) }
            } else {
                null
            }
            provider.catalog(
                status = initialized.status,
                message = initialized.message,
                descriptors = descriptors.orEmpty(),
            ) to (descriptors != null)
        }
        val catalog = fetched.first
        if (!useEnvironmentCache) {
            return catalog
        }
        if (fetched.second) {
            environmentProviderCatalogLock.withLock {
                environmentProviderCatalogCache.putIfAbsent(cacheKey, catalog)
                evictOldProviderCatalogsLocked()
            }
        }
        return environmentProviderCatalogLock.withLock {
            environmentProviderCatalogCache[cacheKey] ?: catalog
        }
    }

    private fun usesEnvironmentCatalogCache(provider: ToolProvider): Boolean {
        val compiled = blueprint ?: return true
        return compiled.resolveSharedAll(AgentCapabilities.TOOL_PROVIDER).any { it === provider }
    }

    private suspend fun <T> withProviderPermit(
        provider: ToolProvider,
        action: suspend () -> T,
    ): T = concurrencyLimiter.withPermit(provider, action)

    private fun providersFor(context: MaterializerContext): List<ToolProvider> {
        val compiled = blueprint ?: return staticProviders
        val sessionId = context.sessionId
        if (sessionId.isBlank()) {
            return compiled.resolveSharedAll(AgentCapabilities.TOOL_PROVIDER)
        }
        synchronized(sessionProvidersLock) {
            sessionProviders[sessionId]
        }?.let { return it }
        val resolved = compiled.resolveSessionAll(
            SessionCapabilityContext(sessionId),
            AgentCapabilities.TOOL_PROVIDER,
        )
        return synchronized(sessionProvidersLock) {
            sessionProviders.getOrPut(sessionId) { resolved }
        }
    }

    private fun evictSessionLimiterIdentities(sessionResolved: List<ToolProvider>) {
        val shared = blueprint?.resolveSharedAll(AgentCapabilities.TOOL_PROVIDER).orEmpty()
        sessionResolved.forEach { provider ->
            if (shared.none { it === provider }) {
                concurrencyLimiter.evict(provider)
            }
        }
    }

    private fun rememberSnapshotKey(sessionId: String, key: CatalogSnapshotKey) {
        val bucket = sessionId.ifBlank { DEFAULT_CATALOG_CACHE_KEY }
        snapshotKeysBySession.getOrPut(bucket) { mutableSetOf() } += key
    }

    /**
     * Evicts session snapshots and limiter identities. Does not close providers.
     * Blueprint/plugin ledger owns provider close.
     */
    suspend fun closeSession(sessionId: String) {
        val evictedProviders = synchronized(sessionProvidersLock) {
            sessionProviders.remove(sessionId).orEmpty()
        }
        snapshotLock.withLock {
            val bucket = sessionId.ifBlank { DEFAULT_CATALOG_CACHE_KEY }
            snapshotKeysBySession.remove(bucket).orEmpty().forEach { snapshotCache.remove(it) }
        }
        evictSessionLimiterIdentities(evictedProviders)
    }

    /**
     * Drops in-memory snapshots and limiter entries so this registry does not retain providers.
     * Runtime close drains loop resources first, then calls this before plugin/provider close.
     */
    suspend fun close() {
        try {
            synchronized(sessionProvidersLock) { sessionProviders.clear() }
            snapshotLock.withLock {
                snapshotCache.clear()
                snapshotKeysBySession.clear()
            }
        } finally {
            concurrencyLimiter.close()
        }
    }

    suspend fun cachedSnapshotCountForTests(): Int =
        snapshotLock.withLock { snapshotCache.size }

    fun cachedLimiterEntryCountForTests(): Int =
        concurrencyLimiter.cachedEntryCountForTests()

    private fun evictOldSnapshots() {
        while (snapshotCache.size > MAX_CACHED_SNAPSHOTS) {
            val oldest = snapshotCache.keys.firstOrNull() ?: return
            snapshotCache.remove(oldest)
            snapshotKeysBySession.values.forEach { it.remove(oldest) }
        }
    }

    companion object {
        private val environmentProviderCatalogLock = Mutex()
        private val environmentProviderCatalogCache = linkedMapOf<String, McpProviderCatalog>()

        suspend fun clearEnvironmentProviderCatalogCacheForTests() {
            environmentProviderCatalogLock.withLock {
                environmentProviderCatalogCache.clear()
            }
        }

        private fun evictOldProviderCatalogsLocked() {
            while (environmentProviderCatalogCache.size > MAX_CACHED_PROVIDER_CATALOGS) {
                val oldest = environmentProviderCatalogCache.keys.firstOrNull() ?: return
                environmentProviderCatalogCache.remove(oldest)
            }
        }
    }

    private suspend fun initialize(
        provider: ToolProvider,
        context: MaterializerContext,
    ) = try {
        provider.initialize(context.toMcpInitializeContext())
    } catch (error: CancellationException) {
        throw error
    } catch (error: RuntimeException) {
        ToolInitializeResult.failed(error.message ?: "MCP provider initialization failed.")
    }

    private suspend fun listToolDescriptors(provider: ToolProvider): List<ToolDescriptor>? = try {
        provider.listTools()
    } catch (error: CancellationException) {
        throw error
    } catch (_: RuntimeException) {
        null
    }

    private suspend fun <T> listConnectedContribution(
        provider: ToolProvider,
        context: MaterializerContext,
        list: suspend ToolProvider.() -> List<T>,
    ): List<T> = withProviderPermit(provider) {
        val initialized = initialize(provider, context)
        if (initialized.status != ToolProviderStatus.CONNECTED) {
            return@withProviderPermit emptyList()
        }
        try {
            provider.list()
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            emptyList()
        }
    }
}

private fun MaterializerContext.catalogSnapshotKey(): CatalogSnapshotKey =
    CatalogSnapshotKey(sessionId = sessionId, runId = runId, requestId = requestId)

private data class CatalogSnapshotKey(
    val sessionId: String,
    val runId: String,
    val requestId: String,
)

private const val MAX_CACHED_SNAPSHOTS = 16
private const val MAX_CACHED_PROVIDER_CATALOGS = 32
private const val DEFAULT_CATALOG_CACHE_KEY = "default"
private const val NANOS_PER_MILLI = 1_000_000L

private suspend fun <T> measureCatalog(timings: MutableMap<String, Long>, phase: String, action: suspend () -> T): T {
    val started = System.nanoTime()
    return try { action() } finally { timings[phase] = (System.nanoTime() - started) / NANOS_PER_MILLI }
}

private data class McpProviderCatalog(
    val serverId: String,
    val normalizedServerId: String,
    val displayName: String,
    val status: String,
    val message: String?,
    val descriptors: List<ToolDescriptor>,
) {
    fun toolRefs(
        provider: ToolProvider,
        qualifiedCounts: MutableMap<String, Int>,
    ): List<McpToolRef> = descriptors.mapNotNull { descriptor ->
        if (descriptor.name.isBlank() || descriptor.inputSchemaJson.isBlank()) {
            null
        } else {
            val toolId = normalizeMcpComponent(descriptor.name)
            val baseName = "mcp__${normalizedServerId}__${toolId}"
            val count = qualifiedCounts.merge(baseName, 1, Int::plus) ?: 1
            val qualified = if (count == 1) baseName else "${baseName}_$count"
            McpToolRef(
                provider = provider,
                serverId = serverId,
                normalizedServerId = normalizedServerId,
                originalToolName = descriptor.name,
                normalizedToolName = toolId,
                qualifiedName = qualified,
                descriptor = descriptor,
            )
        }
    }
}

private fun ToolProvider.catalog(
    status: ToolProviderStatus,
    message: String?,
    descriptors: List<ToolDescriptor>,
): McpProviderCatalog =
    McpProviderCatalog(
        serverId = providerId,
        normalizedServerId = normalizeMcpComponent(providerId),
        displayName = displayName,
        status = status.name.lowercase(),
        message = message,
        descriptors = descriptors,
    )

private fun McpProviderCatalog.disambiguate(
    providerIndex: Int,
    usedServerIds: MutableMap<String, Int>,
): McpProviderCatalog {
    val occurrence = usedServerIds.merge(serverId, 1, Int::plus) ?: 1
    if (occurrence == 1) {
        return this
    }
    val suffixed = "${serverId}__$providerIndex"
    return copy(
        serverId = suffixed,
        normalizedServerId = normalizeMcpComponent(suffixed),
    )
}

private fun ToolProvider.catalogCacheKey(providerIndex: Int): String =
    (this as? ToolCatalogCacheIdentity)
        ?.catalogCacheIdentity
        ?.takeIf { it.isNotBlank() }
        ?.let { stableIdentity ->
            listOf("stable", this::class.java.name, providerId, stableIdentity)
                .joinToString(separator = "\u001F")
        }
        ?: listOf(
            "instance",
            providerIndex.toString(),
            this::class.java.name,
            providerId,
            displayName,
            System.identityHashCode(this).toString(),
        )
            .joinToString(separator = "\u001F")

data class McpResolvedSchema(
    val tool: McpToolRef,
    val schemaJson: String,
)

data class McpRoutedCallResult(
    val tool: McpToolRef,
    val result: ToolCallResult,
)

data class McpToolRef(
    val provider: ToolProvider,
    val serverId: String,
    val normalizedServerId: String,
    val originalToolName: String,
    val normalizedToolName: String,
    val qualifiedName: String,
    val descriptor: ToolDescriptor,
)

fun normalizeMcpComponent(value: String): String =
    value.map { char ->
        if (char.isLetterOrDigit() || char == '_' || char == '-') char else '_'
    }.joinToString("").ifBlank { "unnamed" }

private fun toolsPayloadJson(tools: List<JsonObject>): String =
    tools.joinToString(prefix = "{\"tools\":[\n", separator = ",\n", postfix = "\n]}")

fun MaterializerContext.toMcpCallContext(): ToolCallContext =
    ToolCallContext(sessionId = sessionId, runId = runId, requestId = requestId)

private fun MaterializerContext.toMcpInitializeContext(): ToolInitializeContext =
    ToolInitializeContext(sessionId = sessionId, runId = runId, requestId = requestId)

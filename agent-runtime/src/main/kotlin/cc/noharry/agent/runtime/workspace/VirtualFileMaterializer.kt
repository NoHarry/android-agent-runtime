package cc.noharry.agent.runtime.workspace
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.core.loop.failed
import cc.noharry.agent.kernel.core.tools.MaterializedEntry
import cc.noharry.agent.kernel.core.tools.MaterializedSchemaCacheEntry
import cc.noharry.agent.kernel.core.tools.MaterializedSchemaMetadata
import cc.noharry.agent.kernel.core.tools.MaterializerCacheMetadata
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.kernel.core.tools.MaterializerListResult
import cc.noharry.agent.kernel.core.tools.MaterializerReadResult
import cc.noharry.agent.kernel.core.tools.McpToolRef
import cc.noharry.agent.kernel.core.tools.ToolActivationMetadata
import cc.noharry.agent.kernel.core.tools.ToolActivationStore
import cc.noharry.agent.kernel.core.tools.ToolProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalAgentRuntimeApi::class)
internal class VirtualFileMaterializer(
    private val mcpRegistry: ToolProviderRegistry = ToolProviderRegistry(),
    private val activationStore: ToolActivationStore = ToolActivationStore(),
    private val schemaTtlMillis: Long = DEFAULT_SCHEMA_TTL_MILLIS,
    private val maxSchemaMaterializationsPerRun: Int = DEFAULT_SCHEMA_MATERIALIZATION_BUDGET,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val schemaCacheStore: MaterializerSchemaCacheStore? = null,
    private val contextRouter: ContextMaterializerRouter? = null,
) {
    private val schemaStateLock = Mutex()
    private val schemaMaterializationLocks = linkedMapOf<String, Mutex>()
    private val schemaCache = linkedMapOf<String, MaterializedSchemaCacheEntry>()
    private val schemaMaterializationCounts = linkedMapOf<String, Int>()

    fun isMaterializedPath(path: String): Boolean =
        isMcpPath(path) || isContextMaterializedPath(path)

    suspend fun cacheMetadata(path: String, context: MaterializerContext): MaterializerCacheMetadata = when {
        path == AGENTS_INDEX -> contextRouter?.cacheMetadata(path, context) ?: MaterializerCacheMetadata.NotCacheable
        isContextPath(path) || isSkillPath(path) ->
            contextRouter?.cacheMetadata(path, context) ?: MaterializerCacheMetadata.NotCacheable
        isMcpCatalogReadCacheable(path) -> MaterializerCacheMetadata.shared(MCP_CATALOG_CACHE_VERSION)
        else -> MaterializerCacheMetadata.NotCacheable
    }

    fun runtimeMountTargets(): List<String> = buildList {
        if (contextRouter?.hasAgentsIndex() == true) add(AGENTS_INDEX)
        if (contextRouter != null) {
            add(AGENTS_ROOT)
            add(CONTEXT_ROOT)
        }
        add(MCP_ROOT)
    }

    suspend fun read(path: String, context: MaterializerContext): MaterializerReadResult {
        if (!isMaterializedPath(path)) {
            return MaterializerReadResult.NotMaterialized
        }
        // Context paths do not require a capability backend (SPEC-CDR-002)
        if (path == AGENTS_INDEX) {
            return contextRouter?.readAgentsIndex() ?: contextRouterMissing()
        }
        if (isContextPath(path) || isSkillPath(path)) {
            return contextRouter?.read(path, context) ?: contextRouterMissing()
        }
        return when {
            path == MCP_SERVERS_INDEX -> mcpCatalogRead(context) {
                foundMcpCatalog(path, mcpRegistry.serversJson(context))
            }
            path == MCP_ALL_TOOLS -> mcpCatalogRead(context) {
                activationStore.recordCatalog(context.runId, mcpRegistry.allTools(context))
                foundMcpCatalog(path, mcpRegistry.toolsJson(context))
            }
            path == MCP_TOOLS_INDEX -> mcpCatalogRead(context) {
                activationStore.recordCatalog(context.runId, mcpRegistry.allTools(context))
                foundMcpCatalog(path, mcpRegistry.toolsIndexJson(context))
            }
            path.matches(SERVER_TOOLS_PATH) -> readServerTools(path, context)
            path.matches(TOOL_CARD_PATH) -> readToolCard(path, context)
            path.matches(TOOL_SCHEMA_PATH) -> readToolSchema(path, context)
            path == MCP_RESOURCES_INDEX -> mcpCatalogRead(context) {
                foundMcpCatalog(path, mcpRegistry.resourcesIndexJson(context))
            }
            path == MCP_PROMPTS_INDEX -> mcpCatalogRead(context) {
                foundMcpCatalog(path, mcpRegistry.promptsIndexJson(context))
            }
            else -> MaterializerReadResult.NotFound
        }
    }

    suspend fun list(path: String, context: MaterializerContext): MaterializerListResult {
        if (!isMaterializedPath(path)) {
            return MaterializerListResult.NotMaterialized
        }
        // Context paths do not require a capability backend (SPEC-CDR-002)
        if (isContextPath(path) || isSkillPath(path)) {
            return contextRouter?.list(path, context) ?: MaterializerListResult.Error(
                code = RuntimeErrorCode.CAPABILITY_BACKEND_NOT_CONFIGURED.wireCode,
                message = "Context router is not configured.",
                retryable = false,
            )
        }
        return when {
            path == MCP_ROOT -> MaterializerListResult.Entries(
                listOf(
                    MaterializedEntry("servers", "dir"),
                    MaterializedEntry("servers.json", "file"),
                    MaterializedEntry("tools", "dir"),
                    MaterializedEntry("tools.index.json", "file"),
                    MaterializedEntry("tools.json", "file"),
                    MaterializedEntry("resources", "dir"),
                    MaterializedEntry("prompts", "dir"),
                ),
            )
            path == MCP_SERVERS -> listServers(context)
            path.matches(SERVER_PATH) -> listServer(path, context)
            path == MCP_TOOLS -> listTools(context)
            path.matches(TOOL_PATH) -> listToolFiles(path, context)
            path == MCP_RESOURCES -> MaterializerListResult.Entries(listOf(MaterializedEntry("index.json", "file")))
            path == MCP_PROMPTS -> MaterializerListResult.Entries(listOf(MaterializedEntry("index.json", "file")))
            else -> MaterializerListResult.NotFound
        }
    }

    private suspend fun readToolSchema(path: String, context: MaterializerContext): MaterializerReadResult {
        val toolName = pathSegments(path)[2]
        val tool = mcpRegistry.toolRef(toolName, context)
            ?: return MaterializerReadResult.NotFound
        val now = nowEpochMillis()
        cachedSchemaEntry(toolName, now)?.let { cached ->
            activate(context, tool, cached.content, cached.metadata, cacheHit = true)
            return foundSchema(
                content = cached.content,
                cacheHit = true,
                metadata = cached.metadata,
            )
        }
        schemaCacheStore?.read(toolName, now)?.let { durableCached ->
            rememberSchemaEntry(toolName, durableCached)
            activate(context, tool, durableCached.content, durableCached.metadata, cacheHit = true)
            return foundSchema(
                content = durableCached.content,
                cacheHit = true,
                metadata = durableCached.metadata,
            )
        }
        return schemaMaterializationLock(toolName).withLock {
            val lockedNow = nowEpochMillis()
            cachedSchemaEntry(toolName, lockedNow)?.let { cached ->
                activate(context, tool, cached.content, cached.metadata, cacheHit = true)
                return@withLock foundSchema(
                    content = cached.content,
                    cacheHit = true,
                    metadata = cached.metadata,
                )
            }
            schemaCacheStore?.read(toolName, lockedNow)?.let { durableCached ->
                rememberSchemaEntry(toolName, durableCached)
                activate(context, tool, durableCached.content, durableCached.metadata, cacheHit = true)
                return@withLock foundSchema(
                    content = durableCached.content,
                    cacheHit = true,
                    metadata = durableCached.metadata,
                )
            }
            if (!consumeSchemaBudget(context)) {
                return@withLock MaterializerReadResult.Error(
                    code = RuntimeErrorCode.SCHEMA_MATERIALIZE_BUDGET_EXCEEDED.wireCode,
                    message = "Schema materialization budget exceeded.",
                    retryable = false,
                )
            }
            try {
                val schema = withTimeout(context.timeoutMillis) { mcpRegistry.toolSchema(toolName, context) }
                    ?: return@withLock MaterializerReadResult.NotFound
                val materializedAt = nowEpochMillis()
                val metadata = materializedSchemaMetadata(
                    schemaJson = schema.schemaJson,
                    expiresAtEpochMillis = materializedAt + schemaTtlMillis,
                )
                val entry = MaterializedSchemaCacheEntry(
                    content = schema.schemaJson,
                    metadata = metadata,
                )
                rememberMaterializedSchemaEntry(toolName, entry, materializedAt)
                schemaCacheStore?.write(toolName, entry, materializedAt)
                activate(context, schema.tool, schema.schemaJson, metadata, cacheHit = false)
                foundSchema(
                    content = schema.schemaJson,
                    cacheHit = false,
                    metadata = metadata,
                )
            } catch (_: TimeoutCancellationException) {
                MaterializerReadResult.Error(
                    code = RuntimeErrorCode.SCHEMA_MATERIALIZE_TIMEOUT.wireCode,
                    message = "Timed out while materializing schema.",
                    retryable = true,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: RuntimeException) {
                MaterializerReadResult.Error(
                    code = RuntimeErrorCode.SCHEMA_INSPECT_FAILED.wireCode,
                    message = error.message ?: "Tool schema inspect failed.",
                    retryable = true,
                )
            }
        }
    }

    private suspend fun readServerTools(path: String, context: MaterializerContext): MaterializerReadResult =
        mcpCatalogRead(context) {
            val serverId = pathSegments(path)[2]
            val tools = mcpRegistry.toolsForServer(serverId, context)
            val content = mcpRegistry.serverToolsJson(serverId, context) ?: return@mcpCatalogRead MaterializerReadResult.NotFound
            activationStore.recordCatalog(context.runId, tools)
            foundMcpCatalog(path, content)
        }

    private suspend fun readToolCard(path: String, context: MaterializerContext): MaterializerReadResult =
        mcpCatalogRead(context) {
            val qualifiedName = pathSegments(path)[2]
            val card = mcpRegistry.toolCardJson(qualifiedName, context)
                ?: return@mcpCatalogRead MaterializerReadResult.NotFound
            activationStore.recordCatalog(context.runId, mcpRegistry.allTools(context))
            foundMcpCatalog(path, card)
        }

    private suspend fun listServers(context: MaterializerContext): MaterializerListResult =
        mcpCatalogList(context) {
            MaterializerListResult.Entries(
                mcpRegistry.serverDirectoryNames(context).map { MaterializedEntry(it, "dir") },
            )
        }

    private suspend fun listServer(path: String, context: MaterializerContext): MaterializerListResult =
        mcpCatalogList(context) {
            val serverId = pathSegments(path)[2]
            if (mcpRegistry.serverToolsJson(serverId, context) == null) {
                MaterializerListResult.NotFound
            } else {
                MaterializerListResult.Entries(listOf(MaterializedEntry("tools.json", "file")))
            }
        }

    private suspend fun listTools(context: MaterializerContext): MaterializerListResult =
        mcpCatalogList(context) {
            val tools = mcpRegistry.allTools(context)
            activationStore.recordCatalog(context.runId, tools)
            MaterializerListResult.Entries(
                tools.map { MaterializedEntry(it.qualifiedName, "dir") }.sortedBy { it.name },
            )
        }

    private suspend fun listToolFiles(path: String, context: MaterializerContext): MaterializerListResult =
        mcpCatalogList(context) {
            val qualifiedName = pathSegments(path)[2]
            activationStore.recordCatalog(context.runId, mcpRegistry.allTools(context))
            if (mcpRegistry.toolCardJson(qualifiedName, context) == null) {
                MaterializerListResult.NotFound
            } else {
                MaterializerListResult.Entries(
                    listOf(MaterializedEntry("card.json", "file"), MaterializedEntry("schema.json", "file")),
                )
            }
        }

    private suspend fun consumeSchemaBudget(context: MaterializerContext): Boolean = schemaStateLock.withLock {
        val key = context.runId.ifBlank {
            context.requestId.ifBlank { DEFAULT_SCHEMA_BUDGET_KEY }
        }
        val count = schemaMaterializationCounts[key] ?: 0
        if (count >= maxSchemaMaterializationsPerRun) {
            return@withLock false
        }
        schemaMaterializationCounts[key] = count + 1
        true
    }

    private suspend fun schemaMaterializationLock(toolName: String): Mutex = schemaStateLock.withLock {
        schemaMaterializationLocks.getOrPut(toolName) { Mutex() }
    }

    private suspend fun cachedSchemaEntry(
        toolName: String,
        nowEpochMillis: Long,
    ): MaterializedSchemaCacheEntry? = schemaStateLock.withLock {
        val cached = schemaCache[toolName] ?: return@withLock null
        if (cached.metadata.expiresAtEpochMillis > nowEpochMillis) {
            cached
        } else {
            schemaCache.remove(toolName)
            null
        }
    }

    private suspend fun rememberSchemaEntry(
        toolName: String,
        entry: MaterializedSchemaCacheEntry,
    ) {
        schemaStateLock.withLock {
            schemaCache[toolName] = entry
        }
    }

    private suspend fun rememberMaterializedSchemaEntry(
        toolName: String,
        entry: MaterializedSchemaCacheEntry,
        nowEpochMillis: Long,
    ) {
        schemaStateLock.withLock {
            evictExpiredMemoryEntries(nowEpochMillis)
            schemaCache[toolName] = entry
        }
    }

    private fun evictExpiredMemoryEntries(nowEpochMillis: Long) {
        schemaCache.entries.removeIf { it.value.metadata.expiresAtEpochMillis <= nowEpochMillis }
    }

    private fun activate(
        context: MaterializerContext,
        tool: McpToolRef,
        schemaJson: String,
        metadata: MaterializedSchemaMetadata,
        cacheHit: Boolean,
    ) {
        activationStore.activate(
            runId = context.runId,
            tool = tool,
            schemaJson = schemaJson,
            metadata = ToolActivationMetadata(
                cacheHit = cacheHit,
                schemaHash = metadata.schemaHash,
                schemaVersion = metadata.schemaVersion,
            ),
        )
    }

    private fun foundSchema(
        content: String,
        cacheHit: Boolean,
        metadata: MaterializedSchemaMetadata,
    ): MaterializerReadResult.Found = MaterializerReadResult.Found(
        content = content,
        cacheHit = cacheHit,
        metadata = metadata,
    )

    private fun foundMcpCatalog(path: String, content: String): MaterializerReadResult.Found =
        MaterializerReadResult.Found(
            content = content,
            cacheHit = false,
            cacheMetadata = mcpCatalogCacheMetadata(path),
        )

    private fun contextRouterMissing(): MaterializerReadResult = MaterializerReadResult.Error(
        code = RuntimeErrorCode.CAPABILITY_BACKEND_NOT_CONFIGURED.wireCode,
        message = "Context router is not configured.",
        retryable = false,
    )
}

private const val DEFAULT_SCHEMA_TTL_MILLIS = 300_000L
private const val DEFAULT_SCHEMA_MATERIALIZATION_BUDGET = 32
private const val DEFAULT_SCHEMA_BUDGET_KEY = "default"
private const val MCP_CATALOG_CACHE_VERSION = "mcp-catalog-v1"

private fun isMcpCatalogReadCacheable(path: String): Boolean =
    path == MCP_SERVERS_INDEX ||
        path == MCP_ALL_TOOLS ||
        path == MCP_TOOLS_INDEX ||
        path == MCP_RESOURCES_INDEX ||
        path == MCP_PROMPTS_INDEX ||
        path.matches(SERVER_TOOLS_PATH) ||
        path.matches(TOOL_CARD_PATH)

private fun mcpCatalogCacheMetadata(path: String): MaterializerCacheMetadata =
    if (isMcpCatalogReadCacheable(path)) {
        MaterializerCacheMetadata.shared(MCP_CATALOG_CACHE_VERSION)
    } else {
        MaterializerCacheMetadata.NotCacheable
    }

private fun pathSegments(path: String): List<String> =
    path.trim('/').split('/').filter { it.isNotBlank() }

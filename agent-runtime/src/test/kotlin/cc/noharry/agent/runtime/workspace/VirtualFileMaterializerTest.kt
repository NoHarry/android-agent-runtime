@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.workspace
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.core.tools.MaterializedSchemaCacheEntry
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.kernel.core.tools.ToolProviderRegistry
import cc.noharry.agent.runtime.TEST_MCP_SERVER_ID
import cc.noharry.agent.runtime.qualifiedMcpTool
import cc.noharry.agent.runtime.tool
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VirtualFileMaterializerTest {
    @Before
    fun clearEnvironmentCatalogCache() = runBlocking {
        ToolProviderRegistry.clearEnvironmentProviderCatalogCacheForTests()
    }

    @Test
    fun readFileMaterializesMcpCatalogFiles() = runBlocking {
        val workspace = workspaceWith(FakeMcpProvider())

        val servers = workspace.executeJson("read_file", """{"path":"/mcp/servers.json"}""")
        val toolsIndex = workspace.executeJson("read_file", """{"path":"/mcp/tools.index.json"}""")
        val allTools = workspace.executeJson("read_file", """{"path":"/mcp/tools.json"}""")
        val serverTools = workspace.executeJson("read_file", """{"path":"/mcp/servers/default/tools.json"}""")
        val card = workspace.executeJson("read_file", """{"path":"/mcp/tools/${qualifiedMcpTool("set_temperature")}/card.json"}""")

        assertTrue(servers.contains(TEST_MCP_SERVER_ID))
        assertTrue(toolsIndex.contains(qualifiedMcpTool("set_temperature")))
        assertTrue(toolsIndex.contains("schemaPath"))
        assertTrue(toolsIndex.contains("Set temperature"))
        assertTrue(allTools.contains(qualifiedMcpTool("set_temperature")))
        assertTrue(serverTools.contains("set_temperature"))
        assertTrue(card.contains("Set temperature"))
    }

    @Test
    fun toolsIndexProvidesCompactDiscoveryWithoutFullToolCards() = runBlocking {
        val workspace = workspaceWith(
            FakeMcpProvider(
                toolNames = listOf(
                    "command_navi_KeyWordSearch",
                    "command_music_PlaySearchMusic",
                    "command_parking_SearchParking",
                ),
            ),
        )

        val index = workspace.executeJson("read_file", """{"path":"/mcp/tools.index.json","max_chars":32000}""")
        val full = workspace.executeJson("read_file", """{"path":"/mcp/tools.json","max_chars":32000}""")

        assertTrue(index.contains(qualifiedMcpTool("command_navi_KeyWordSearch")))
        assertTrue(index.contains(qualifiedMcpTool("command_music_PlaySearchMusic")))
        assertTrue(index.contains(qualifiedMcpTool("command_parking_SearchParking")))
        assertTrue(index.contains("/mcp/tools/${qualifiedMcpTool("command_music_PlaySearchMusic")}/schema.json"))
        assertTrue(index.contains(""""cache_hit":false"""))
        assertTrue("tools.index.json should be lighter than full catalog", index.length < full.length)
        assertTrue("full catalog keeps card paths for callers that need details", full.contains("cardPath"))
    }

    @Test
    fun repeatedCatalogReadsReuseRunSnapshot() = runBlocking {
        val provider = FakeMcpProvider()
        val workspace = workspaceWith(provider)

        workspace.executeJson("read_file", """{"path":"/mcp/servers.json"}""")
        workspace.executeJson("read_file", """{"path":"/mcp/tools.json"}""")
        workspace.executeJson("read_file", """{"path":"/mcp/servers/default/tools.json"}""")
        workspace.executeJson("list_dir", """{"path":"/mcp/tools"}""")

        assertEquals(1, provider.listToolsCalls)
    }

    @Test
    fun grepFindsMaterializedMcpToolsWithAlternationPattern() = runBlocking {
        val workspace = workspaceWith(
            FakeMcpProvider(
                toolNames = listOf(
                    "command_navi_KeyWordSearch",
                    "command_music_PlaySearchMusic",
                    "command_ac_TemperatureAdjust",
                ),
            ),
        )

        val result = workspace.executeJson(
            "grep",
            """{"path":"/mcp/tools.json","pattern":"navi|music","max_matches":10}""",
        )

        assertTrue(result.contains(""""ok":true"""))
        assertTrue(result.contains(qualifiedMcpTool("command_navi_KeyWordSearch")))
        assertTrue(result.contains(qualifiedMcpTool("command_music_PlaySearchMusic")))
    }

    @Test
    fun grepToolsIndexPreviewReturnsMatchedToolLine() = runBlocking {
        val workspace = workspaceWith(
            FakeMcpProvider(
                toolNames = listOf(
                    "command_ac_TemperatureAdjust",
                    "command_navi_KeyWordSearch",
                    "command_music_PlaySearchMusic",
                ),
            ),
        )

        val result = workspace.executeJson(
            "grep",
            """{"path":"/mcp/tools.index.json","pattern":"navi|music","max_matches":10}""",
        )

        assertTrue(result, result.contains(""""ok":true"""))
        assertTrue(result, result.contains(qualifiedMcpTool("command_navi_KeyWordSearch")))
        assertTrue(result, result.contains(qualifiedMcpTool("command_music_PlaySearchMusic")))
        assertFalse(
            "grep preview should not include the unrelated catalog prefix from the same JSON line",
            result.contains(qualifiedMcpTool("command_ac_TemperatureAdjust")),
        )
    }

    @Test
    fun readFileMaterializesSchemaWithCacheHit() = runBlocking {
        val workspace = workspaceWith(FakeMcpProvider())

        val first = workspace.readSchema()
        val second = workspace.readSchema()

        assertTrue(first.contains("degreesCelsius"))
        assertTrue(second.contains("cache_hit\":true"))
    }

    @Test
    fun cachedSchemaReadDoesNotRunDurableCleanupScan() = runBlocking {
        val schemaCacheStore = CountingMaterializerSchemaCacheStore()
        val workspace = workspaceWith(
            provider = FakeMcpProvider(),
            schemaCacheStore = schemaCacheStore,
        )

        val first = workspace.readSchema()
        val second = workspace.readSchema()

        assertTrue(first.contains("cache_hit\":false"))
        assertTrue(second.contains("cache_hit\":true"))
        assertEquals(0, schemaCacheStore.cleanupCalls)
        assertEquals(1, schemaCacheStore.writeCalls)
    }

    @Test
    fun durableSchemaHitAcrossMaterializersDoesNotRunCleanupScan() = runBlocking {
        val schemaCacheStore = CountingMaterializerSchemaCacheStore()
        workspaceWith(
            provider = FakeMcpProvider(),
            schemaCacheStore = schemaCacheStore,
        ).readSchema()
        val restoredProvider = FakeMcpProvider().apply { schemaRevision = 2 }
        val restoredWorkspace = workspaceWith(
            provider = restoredProvider,
            schemaCacheStore = schemaCacheStore,
        )

        val restored = restoredWorkspace.readSchema()

        assertTrue(restored.contains("cache_hit\":true"))
        assertEquals(0, schemaCacheStore.cleanupCalls)
        assertEquals(1, schemaCacheStore.writeCalls)
    }

    @Test
    fun concurrentDistinctSchemaReadsDoNotShareGlobalSchemaCacheLock() = runBlocking {
        val toolNames = listOf("tool_a", "tool_b")
        val schemaCacheStore = ConcurrentReadProbeSchemaCacheStore(toolNames.map(::qualifiedMcpTool).toSet())
        val materializer = VirtualFileMaterializer(
            mcpRegistry = ToolProviderRegistry(listOf(FakeMcpProvider(toolNames = toolNames))),
            schemaCacheStore = schemaCacheStore,
        )

        coroutineScope {
            toolNames.map { toolName ->
                async(Dispatchers.Default) {
                    materializer.read(
                        path = "/mcp/tools/${qualifiedMcpTool(toolName)}/schema.json",
                        context = MaterializerContext(runId = "run-concurrent-schema"),
                    )
                }
            }.awaitAll()
        }

        assertTrue(
            "distinct schema reads should enter durable cache read concurrently",
            schemaCacheStore.maxConcurrentReads.get() > 1,
        )
    }

    @Test
    fun readFileIncludesSchemaMetadataAndRefreshesAfterTtl() = runBlocking {
        var now = 1_000L
        val provider = FakeMcpProvider()
        val workspace = VirtualWorkspace(
            root = createWorkspace(),
            materializer = VirtualFileMaterializer(
                mcpRegistry = ToolProviderRegistry(listOf(provider)),
                schemaTtlMillis = 10L,
                nowEpochMillis = { now },
            ),
        )

        val first = workspace.readSchema()
        val second = workspace.readSchema()
        provider.schemaRevision = 2
        now = 1_011L
        val third = workspace.readSchema()

        assertTrue(first.contains("schema_hash"))
        assertTrue(first.contains("schema_version"))
        assertTrue(first.contains("expires_at_epoch_millis\":1010"))
        assertTrue(second.contains("cache_hit\":true"))
        assertTrue(third.contains("revision"))
        assertTrue(third.contains("cache_hit\":false"))
    }

    @Test
    fun readFileRestoresSchemaFromFileBackedCacheAcrossMaterializers() = runBlocking {
        val storageRoot = Files.createTempDirectory("agent-runtime-schema-cache-").toFile()
        val firstWorkspace = workspaceWith(
            provider = FakeMcpProvider(),
            schemaCacheStore = FileBackedMaterializerSchemaCache(storageRoot),
        )

        val first = firstWorkspace.readSchema()
        val restoredProvider = FakeMcpProvider().apply { schemaRevision = 2 }
        val restoredWorkspace = workspaceWith(
            provider = restoredProvider,
            schemaCacheStore = FileBackedMaterializerSchemaCache(storageRoot),
        )
        val restored = restoredWorkspace.readSchema()
        val marker = File(storageRoot, MATERIALIZER_SCHEMA_CACHE_MARKER_FILE).readText()

        assertTrue(first.contains("cache_hit\":false"))
        assertTrue(restored.contains("cache_hit\":true"))
        assertTrue(marker.contains("schemaCacheMode=FILE_BACKED"))
        assertTrue(marker.contains("schemaCacheEntryCount=1"))
    }

    @Test
    fun fileBackedSchemaCacheRefreshesExpiredEntry() = runBlocking {
        var now = 1_000L
        val storageRoot = Files.createTempDirectory("agent-runtime-schema-cache-").toFile()
        val firstWorkspace = workspaceWith(
            provider = FakeMcpProvider(),
            schemaCacheStore = FileBackedMaterializerSchemaCache(storageRoot),
            schemaTtlMillis = 10L,
            nowEpochMillis = { now },
        )

        firstWorkspace.readSchema()
        now = 1_011L
        val refreshedProvider = FakeMcpProvider().apply { schemaRevision = 2 }
        val refreshedWorkspace = workspaceWith(
            provider = refreshedProvider,
            schemaCacheStore = FileBackedMaterializerSchemaCache(storageRoot),
            schemaTtlMillis = 10L,
            nowEpochMillis = { now },
        )
        val refreshed = refreshedWorkspace.readSchema()
        val marker = File(storageRoot, MATERIALIZER_SCHEMA_CACHE_MARKER_FILE).readText()

        assertTrue(refreshed.contains("cache_hit\":false"))
        assertTrue(refreshed.contains("revision"))
        assertTrue(marker.contains("schemaCacheMode=FILE_BACKED"))
        assertTrue(marker.contains("schemaCacheEntryCount=1"))
    }

    @Test
    fun listDirReturnsVirtualMcpEntries() = runBlocking {
        val workspace = workspaceWith(FakeMcpProvider())

        val root = workspace.executeJson("list_dir", """{"path":"/"}""")
        val mcp = workspace.executeJson("list_dir", """{"path":"/mcp"}""")
        val tool = workspace.executeJson("list_dir", """{"path":"/mcp/tools/${qualifiedMcpTool("set_temperature")}"}""")

        assertTrue(root.contains("mcp"))
        assertTrue(mcp.contains("servers"))
        assertTrue(mcp.contains("tools.index.json"))
        assertTrue(mcp.contains("tools.json"))
        assertTrue(tool.contains("schema.json"))
    }

    @Test
    fun emptyRegistryReturnsEmptyMcpServerCatalog() = runBlocking {
        val workspace = VirtualWorkspace(root = createWorkspace(), materializer = VirtualFileMaterializer())

        val result = workspace.executeJson("read_file", """{"path":"/mcp/servers.json"}""")

        assertTrue(result.contains("servers"))
        assertTrue(result.contains("[]"))
    }

    private fun workspaceWith(
        provider: McpClientProvider,
        schemaCacheStore: MaterializerSchemaCacheStore? = null,
        schemaTtlMillis: Long = 300_000L,
        nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    ): VirtualWorkspace =
        VirtualWorkspace(
            root = createWorkspace(),
            materializer = VirtualFileMaterializer(
                mcpRegistry = ToolProviderRegistry(listOf(provider)),
                schemaTtlMillis = schemaTtlMillis,
                nowEpochMillis = nowEpochMillis,
                schemaCacheStore = schemaCacheStore,
            ),
        )

    private fun createWorkspace(): File =
        Files.createTempDirectory("agent-runtime-materializer-").toFile().canonicalFile
}

private suspend fun VirtualWorkspace.readSchema(): String =
    executeJson(
        toolName = "read_file",
        argumentsJson = """{"path":"/mcp/tools/${qualifiedMcpTool("set_temperature")}/schema.json"}""",
    )

private class FakeMcpProvider(
    private val toolNames: List<String> = listOf("set_temperature"),
) : McpClientProvider {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override val serverId: String = TEST_MCP_SERVER_ID
    var schemaRevision = 1
    var listToolsCalls = 0

    override suspend fun listTools(): List<ToolDescriptor> {
        listToolsCalls += 1
        return toolNames.map { name ->
            ToolDescriptor(
                name = name,
                description = fakeToolTitle(name),
                title = fakeToolTitle(name),
                inputSchemaJson = """{"type":"object","revision":$schemaRevision,"properties":{"degreesCelsius":{"type":"number"}}}""",
            )
        }
    }

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult = ToolCallResult(success = true, resultJson = "{}")
}

private fun fakeToolTitle(name: String): String =
    if (name == "set_temperature") "Set temperature" else name

private class CountingMaterializerSchemaCacheStore : MaterializerSchemaCacheStore {
    private val entries = linkedMapOf<String, MaterializedSchemaCacheEntry>()
    var readCalls: Int = 0
        private set
    var writeCalls: Int = 0
        private set
    var cleanupCalls: Int = 0
        private set

    override fun read(toolName: String, nowEpochMillis: Long): MaterializedSchemaCacheEntry? {
        readCalls += 1
        val entry = entries[toolName] ?: return null
        if (entry.metadata.expiresAtEpochMillis <= nowEpochMillis) {
            entries.remove(toolName)
            return null
        }
        return entry
    }

    override fun write(
        toolName: String,
        entry: MaterializedSchemaCacheEntry,
        nowEpochMillis: Long,
    ) {
        writeCalls += 1
        entries[toolName] = entry
    }

    override fun cleanupExpired(nowEpochMillis: Long): Int {
        cleanupCalls += 1
        val sizeBefore = entries.size
        entries.entries.removeIf { it.value.metadata.expiresAtEpochMillis <= nowEpochMillis }
        return sizeBefore - entries.size
    }
}

private class ConcurrentReadProbeSchemaCacheStore(
    private val probedToolNames: Set<String>,
) : MaterializerSchemaCacheStore {
    private val enteredReads = CountDownLatch(probedToolNames.size)
    private val activeReads = AtomicInteger(0)
    val maxConcurrentReads = AtomicInteger(0)

    override fun read(toolName: String, nowEpochMillis: Long): MaterializedSchemaCacheEntry? {
        if (toolName !in probedToolNames) return null
        val active = activeReads.incrementAndGet()
        recordMax(active)
        enteredReads.countDown()
        enteredReads.await(500, TimeUnit.MILLISECONDS)
        activeReads.decrementAndGet()
        return null
    }

    override fun write(
        toolName: String,
        entry: MaterializedSchemaCacheEntry,
        nowEpochMillis: Long,
    ) = Unit

    override fun cleanupExpired(nowEpochMillis: Long): Int = 0

    private fun recordMax(value: Int) {
        while (true) {
            val current = maxConcurrentReads.get()
            if (value <= current || maxConcurrentReads.compareAndSet(current, value)) return
        }
    }
}

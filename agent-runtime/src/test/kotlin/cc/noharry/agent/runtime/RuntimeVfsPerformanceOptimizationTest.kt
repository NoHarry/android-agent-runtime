@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration

import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolCatalogCacheIdentity
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.ToolInitializeContext
import cc.noharry.agent.kernel.api.ToolInitializeResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import cc.noharry.agent.kernel.core.MaterializerContext
import cc.noharry.agent.kernel.core.RUNTIME_COUNTER_MATERIALIZER_BYTE_ENCODES
import cc.noharry.agent.kernel.core.RUNTIME_COUNTER_MATERIALIZER_READS
import cc.noharry.agent.kernel.core.RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_HITS
import cc.noharry.agent.kernel.core.RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_MISSES
import cc.noharry.agent.kernel.core.RUNTIME_COUNTER_MATERIALIZER_SEARCH_BYTE_ENCODES
import cc.noharry.agent.kernel.core.RUNTIME_COUNTER_OVERLAY_ATTACHES
import cc.noharry.agent.kernel.core.RUNTIME_COUNTER_PROVIDER_CATALOG_CACHE_HITS
import cc.noharry.agent.kernel.core.RUNTIME_COUNTER_PROVIDER_CATALOG_CACHE_MISSES
import cc.noharry.agent.kernel.core.RuntimeVfsPerformanceCounters
import cc.noharry.agent.kernel.core.ToolProviderRegistry

class RuntimeVfsPerformanceOptimizationTest {
    @Before
    fun clearEnvironmentCatalogCache() = runBlocking {
        ToolProviderRegistry.clearEnvironmentProviderCatalogCacheForTests()
    }

    @Test
    fun tcPerf301RepeatedToolsJsonReadReusesOverlayMaterializedContentAndBytes() = runBlocking {
        RuntimeVfsPerformanceCounters.reset()
        val provider = RuntimePerformanceToolsJsonProvider()
        val workspace = toolsJsonWorkspace(provider)
        val context = context()

        repeat(VfsPerformanceBaselineFixture.MCP_TOOLS_JSON_REPEATED_READS) {
            val result = workspace.executeJson("read_file", """{"path":"/mcp/tools.json","max_chars":32000}""", context)
            assertTrue(result, result.contains(toolsJsonQualifiedTool(RuntimePerformanceToolsJsonProvider.AC_TOOL_NAME)))
            assertTrue(result, result.contains("\"cache_hit\":false"))
        }

        assertEquals(1, provider.listToolsCalls)
        assertCounter(RUNTIME_COUNTER_OVERLAY_ATTACHES, 1L)
        assertCounter(RUNTIME_COUNTER_MATERIALIZER_READS, 1L)
        assertCounter(RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_MISSES, 1L)
        assertCounter(
            RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_HITS,
            VfsPerformanceBaselineFixture.MCP_TOOLS_JSON_REPEATED_READS.toLong() - 1L,
        )
        assertCounter(RUNTIME_COUNTER_MATERIALIZER_BYTE_ENCODES, 1L)
        assertTrue(
            counter(RUNTIME_COUNTER_OVERLAY_ATTACHES) <
                VfsPerformanceBaselineFixture.MCP_TOOLS_JSON_REPEATED_READ_OVERLAY_ATTACHES,
        )
        assertTrue(
            counter(RUNTIME_COUNTER_MATERIALIZER_BYTE_ENCODES) <
                VfsPerformanceBaselineFixture.MCP_TOOLS_JSON_REPEATED_READ_BYTE_ENCODES,
        )
    }

    @Test
    fun tcPerf301ServerToolsJsonPathUsesToolsJsonModeAndSameRunCacheScope() = runBlocking {
        RuntimeVfsPerformanceCounters.reset()
        val provider = RuntimePerformanceToolsJsonProvider()
        val workspace = toolsJsonWorkspace(provider)
        val firstRequest = context(requestId = "request-a")
        val secondRequest = context(requestId = "request-b")

        repeat(3) {
            val result = workspace.executeJson(
                "read_file",
                """{"path":"/mcp/servers/tools_json/tools.json","max_chars":32000}""",
                firstRequest,
            )
            assertTrue(result, result.contains(toolsJsonQualifiedTool(RuntimePerformanceToolsJsonProvider.AC_TOOL_NAME)))
            assertTrue(result, result.contains("\"cache_hit\":false"))
        }
        workspace.executeJson(
            "read_file",
            """{"path":"/mcp/servers/tools_json/tools.json","max_chars":32000}""",
            secondRequest,
        )

        assertEquals(1, provider.listToolsCalls)
        assertCounter(RUNTIME_COUNTER_OVERLAY_ATTACHES, 2L)
        assertCounter(RUNTIME_COUNTER_MATERIALIZER_READS, 1L)
        assertCounter(RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_MISSES, 1L)
        assertCounter(RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_HITS, 3L)
        assertCounter(RUNTIME_COUNTER_MATERIALIZER_BYTE_ENCODES, 1L)
    }

    @Test
    fun tcPerf302RepeatedToolsJsonGrepReusesMaterializedReadAndByteCache() = runBlocking {
        RuntimeVfsPerformanceCounters.reset()
        val provider = RuntimePerformanceToolsJsonProvider()
        val workspace = toolsJsonWorkspace(provider)
        val context = context()

        repeat(2) {
            val result = workspace.executeJson(
                "grep",
                """{"path":"/mcp/tools.json","pattern":"TemperatureAdjust","max_matches":10}""",
                context,
            )
            assertTrue(result, result.contains(toolsJsonQualifiedTool(RuntimePerformanceToolsJsonProvider.AC_TOOL_NAME)))
        }

        assertEquals(1, provider.listToolsCalls)
        assertCounter(RUNTIME_COUNTER_OVERLAY_ATTACHES, 1L)
        assertCounter(RUNTIME_COUNTER_MATERIALIZER_READS, 1L)
        assertCounter(RUNTIME_COUNTER_MATERIALIZER_BYTE_ENCODES, 1L)
        assertCounter(RUNTIME_COUNTER_MATERIALIZER_SEARCH_BYTE_ENCODES, 0L)
    }

    @Test
    fun tcPerf104SchemaCacheHitAttributeRemainsSchemaOnly() = runBlocking {
        RuntimeVfsPerformanceCounters.reset()
        val provider = RuntimePerformanceToolsJsonProvider()
        val workspace = toolsJsonWorkspace(provider)
        val context = context()
        val schemaPath = "/mcp/tools/${toolsJsonQualifiedTool(RuntimePerformanceToolsJsonProvider.AC_TOOL_NAME)}/schema.json"

        val catalog = workspace.executeJson("read_file", """{"path":"/mcp/tools.json","max_chars":32000}""", context)
        val firstSchema = workspace.executeJson("read_file", """{"path":"$schemaPath","max_chars":32000}""", context)
        val secondSchema = workspace.executeJson("read_file", """{"path":"$schemaPath","max_chars":32000}""", context)

        assertTrue(catalog, catalog.contains("\"cache_hit\":false"))
        assertTrue(firstSchema, firstSchema.contains("\"cache_hit\":false"))
        assertTrue(secondSchema, secondSchema.contains("\"cache_hit\":true"))
    }

    @Test
    fun tcPerf403RuntimeCacheIsThreadSafeAndSharedAcrossRunsAndSessions() = runBlocking {
        RuntimeVfsPerformanceCounters.reset()
        val provider = RuntimePerformanceToolsJsonProvider()
        val workspace = toolsJsonWorkspace(provider)
        val warmContext = context(
            sessionId = "session-tools-json-0",
            runId = "run-tools-json-0",
            requestId = "request-tools-json-0",
        )
        val sharedContexts = (1..8).map { index ->
            context(
                sessionId = "session-tools-json-$index",
                runId = "run-tools-json-$index",
                requestId = "request-tools-json-$index",
            )
        }
        val catalogPaths = listOf("/mcp/tools.json", "/mcp/tools.index.json")

        val warmResults = catalogPaths.map { path ->
            workspace.executeJson("read_file", """{"path":"$path","max_chars":32000}""", warmContext)
        }
        val sharedResults = coroutineScope {
            sharedContexts.flatMap { sharedContext ->
                catalogPaths.map { path ->
                    async {
                        workspace.executeJson("read_file", """{"path":"$path","max_chars":32000}""", sharedContext)
                    }
                }
            }.awaitAll()
        }

        (warmResults + sharedResults).forEach { result ->
            assertTrue(result, result.contains(toolsJsonQualifiedTool(RuntimePerformanceToolsJsonProvider.AC_TOOL_NAME)))
            assertTrue(result, result.contains("\"cache_hit\":false"))
        }
        assertEquals(1, provider.listToolsCalls)
        assertCounter(RUNTIME_COUNTER_OVERLAY_ATTACHES, sharedContexts.size + 1L)
        assertCounter(RUNTIME_COUNTER_MATERIALIZER_READS, catalogPaths.size.toLong())
        assertCounter(RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_MISSES, catalogPaths.size.toLong())
        assertCounter(
            RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_HITS,
            (sharedContexts.size * catalogPaths.size).toLong(),
        )
        assertCounter(RUNTIME_COUNTER_MATERIALIZER_BYTE_ENCODES, catalogPaths.size.toLong())
    }

    @Test
    fun tcPerf404ProviderCatalogIpcIsSharedAcrossRunScopedSnapshots() = runBlocking {
        RuntimeVfsPerformanceCounters.reset()
        val schemaPath = "/mcp/tools/${toolsJsonQualifiedTool(RuntimePerformanceToolsJsonProvider.AC_TOOL_NAME)}/schema.json"
        val contexts = (1..4).map { index ->
            context(
                sessionId = "session-schema-$index",
                runId = "run-schema-$index",
                requestId = "request-schema-$index",
            )
        }
        val providers = contexts.map { RuntimePerformanceToolsJsonProvider() }
        val workspaces = providers.map(::toolsJsonWorkspace)

        val results = contexts.zip(workspaces).map { (schemaContext, workspace) ->
            workspace.executeJson("read_file", """{"path":"$schemaPath","max_chars":32000}""", schemaContext)
        }

        results.forEach { result ->
            assertTrue(result, result.contains("\"cache_hit\":false"))
        }
        assertEquals(1, providers.sumOf { it.initializeCalls })
        assertEquals(1, providers.sumOf { it.listToolsCalls })
        assertCounter(RUNTIME_COUNTER_OVERLAY_ATTACHES, contexts.size.toLong())
        assertCounter(RUNTIME_COUNTER_PROVIDER_CATALOG_CACHE_MISSES, 1L)
        assertCounter(RUNTIME_COUNTER_PROVIDER_CATALOG_CACHE_HITS, contexts.size.toLong() - 1L)
    }

    @Test
    fun tcPerf405ProviderCatalogStableIdentityIsIndependentFromProviderOrder() = runBlocking {
        RuntimeVfsPerformanceCounters.reset()
        val schemaPath = "/mcp/tools/${toolsJsonQualifiedTool(RuntimePerformanceToolsJsonProvider.AC_TOOL_NAME)}/schema.json"
        val firstProvider = RuntimePerformanceToolsJsonProvider()
        val secondProvider = RuntimePerformanceToolsJsonProvider()
        val firstWorkspace = toolsJsonWorkspace(
            listOf(RuntimePerformanceEmptyProvider("first_empty"), firstProvider),
        )
        val secondWorkspace = toolsJsonWorkspace(
            listOf(secondProvider, RuntimePerformanceEmptyProvider("second_empty")),
        )

        val firstResult = firstWorkspace.executeJson(
            "read_file",
            """{"path":"$schemaPath","max_chars":32000}""",
            context(runId = "run-order-first", sessionId = "session-order-first"),
        )
        val secondResult = secondWorkspace.executeJson(
            "read_file",
            """{"path":"$schemaPath","max_chars":32000}""",
            context(runId = "run-order-second", sessionId = "session-order-second"),
        )

        assertTrue(firstResult, firstResult.contains("\"cache_hit\":false"))
        assertTrue(secondResult, secondResult.contains("\"cache_hit\":false"))
        assertEquals(1, firstProvider.initializeCalls)
        assertEquals(1, firstProvider.listToolsCalls)
        assertEquals(0, secondProvider.initializeCalls)
        assertEquals(0, secondProvider.listToolsCalls)
        assertCounter(RUNTIME_COUNTER_PROVIDER_CATALOG_CACHE_HITS, 1L)
    }

    private fun toolsJsonWorkspace(provider: McpClientProvider): VirtualWorkspace =
        toolsJsonWorkspace(listOf(provider))

    private fun toolsJsonWorkspace(providers: List<McpClientProvider>): VirtualWorkspace =
        VirtualWorkspace(
            root = Files.createTempDirectory("runtime-vfs-performance-").toFile(),
            materializer = VirtualFileMaterializer(
                mcpRegistry = ToolProviderRegistry(providers),
            ),
        )

    private fun context(
        sessionId: String = "session-tools-json",
        runId: String = "run-tools-json",
        requestId: String = "request-tools-json",
    ): MaterializerContext =
        MaterializerContext(runId = runId, sessionId = sessionId, requestId = requestId)

    private fun assertCounter(name: String, expected: Long) {
        assertEquals("counter=$name", expected, counter(name))
    }

    private fun counter(name: String): Long =
        RuntimeVfsPerformanceCounters.snapshot().getOrDefault(name, 0L)
}

private fun toolsJsonQualifiedTool(name: String): String =
    "mcp__tools_json__$name"

private class RuntimePerformanceEmptyProvider(
    override val serverId: String,
) : McpClientProvider {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override suspend fun listTools(): List<ToolDescriptor> = emptyList()

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult = ToolCallResult(success = false, resultJson = "{}")
}

private class RuntimePerformanceToolsJsonProvider : McpClientProvider, ToolCatalogCacheIdentity {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override val serverId: String = "tools_json"
    override val displayName: String = "tools.json"
    override val catalogCacheIdentity: String = "runtime-performance-tools-json"
    var initializeCalls: Int = 0
    var listToolsCalls: Int = 0

    override suspend fun initialize(context: ToolInitializeContext): ToolInitializeResult {
        initializeCalls += 1
        return ToolInitializeResult.connected()
    }

    override suspend fun listTools(): List<ToolDescriptor> {
        listToolsCalls += 1
        return TOOL_NAMES.map { name ->
            ToolDescriptor(
                name = name,
                description = "tools.json catalog tool $name",
                title = name,
                inputSchemaJson = """{"type":"object","properties":{"value":{"type":"number"}}}""",
            )
        }
    }

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult =
        ToolCallResult(success = true, resultJson = """{"ok":true}""")

    companion object {
        const val AC_TOOL_NAME = "command_ac_TemperatureAdjust"
        private val TOOL_NAMES = listOf(
            "command_app_PlayTts",
            "command_music_Play",
            "command_navi_SearchPoi",
            AC_TOOL_NAME,
        )
    }
}

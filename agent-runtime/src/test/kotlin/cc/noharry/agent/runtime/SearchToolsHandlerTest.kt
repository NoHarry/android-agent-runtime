@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration

import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolInitializeContext
import cc.noharry.agent.kernel.api.ToolInitializeResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import cc.noharry.agent.kernel.core.MaterializerContext
import cc.noharry.agent.kernel.core.ToolActivationStore
import cc.noharry.agent.kernel.core.ToolProviderRegistry

class SearchToolsHandlerTest {

    @Test
    fun searchByChineseKeywordFindsAroundSearch() = runBlocking {
        val registry = ToolProviderRegistry(listOf(naviProvider()))
        val workspace = VirtualWorkspace(
            fileSystem = singleRootFs(),
            mcpRegistry = registry,
        )
        val result = workspace.executeJson(
            toolName = "search_tools",
            argumentsJson = """{"query":"周边搜索 停车场"}""",
            context = MaterializerContext(runId = "test-search"),
        )
        assertTrue(result.contains("\"ok\":true"))
        assertTrue("should find AroundSearch", result.contains("AroundSearch"))
    }

    @Test
    fun searchByEnglishKeywordFindsPlayMusic() = runBlocking {
        val registry = ToolProviderRegistry(listOf(musicProvider()))
        val workspace = VirtualWorkspace(
            fileSystem = singleRootFs(),
            mcpRegistry = registry,
        )
        val result = workspace.executeJson(
            toolName = "search_tools",
            argumentsJson = """{"query":"play music singer"}""",
            context = MaterializerContext(runId = "test-search"),
        )
        assertTrue(result.contains("\"ok\":true"))
        assertTrue("should find PlaySearchMusic", result.contains("PlaySearchMusic"))
    }

    @Test
    fun searchWithNoMatchReturnsEmptyMatches() = runBlocking {
        val registry = ToolProviderRegistry(listOf(naviProvider()))
        val workspace = VirtualWorkspace(
            fileSystem = singleRootFs(),
            mcpRegistry = registry,
        )
        val result = workspace.executeJson(
            toolName = "search_tools",
            argumentsJson = """{"query":"xyzqqq unrelated"}""",
            context = MaterializerContext(runId = "test-search"),
        )
        assertTrue(result.contains("\"ok\":true"))
        assertTrue("matches should be empty", result.contains("\"matches\":[]"))
    }

    @Test
    fun searchWithoutMcpRegistryReturnsError() = runBlocking {
        val workspace = VirtualWorkspace(fileSystem = singleRootFs())
        val result = workspace.executeJson(
            toolName = "search_tools",
            argumentsJson = """{"query":"anything"}""",
        )
        assertFalse(result.contains("\"ok\":true"))
        assertTrue(result.contains("not configured"))
    }

    @Test
    fun searchMissingQueryReturnsError() = runBlocking {
        val registry = ToolProviderRegistry(listOf(naviProvider()))
        val workspace = VirtualWorkspace(
            fileSystem = singleRootFs(),
            mcpRegistry = registry,
        )
        val result = workspace.executeJson(
            toolName = "search_tools",
            argumentsJson = """{}""",
        )
        assertFalse(result.contains("\"ok\":true"))
        assertTrue(result.contains("Missing query"))
    }

    @Test
    fun searchResultsIncludeSchemaPath() = runBlocking {
        val registry = ToolProviderRegistry(listOf(naviProvider()))
        val workspace = VirtualWorkspace(
            fileSystem = singleRootFs(),
            mcpRegistry = registry,
        )
        val result = workspace.executeJson(
            toolName = "search_tools",
            argumentsJson = """{"query":"导航 搜索"}""",
            context = MaterializerContext(runId = "test-search"),
        )
        assertTrue("should include schemaPath", result.contains("schemaPath"))
        assertTrue("schemaPath should contain /mcp/tools/", result.contains("/mcp/tools/"))
    }

    @Test
    fun maxResultsLimitsOutput() = runBlocking {
        val registry = ToolProviderRegistry(listOf(naviProvider()))
        val workspace = VirtualWorkspace(
            fileSystem = singleRootFs(),
            mcpRegistry = registry,
        )
        val result = workspace.executeJson(
            toolName = "search_tools",
            argumentsJson = """{"query":"搜索","max_results":1}""",
            context = MaterializerContext(runId = "test-search"),
        )
        // Count occurrences of "originalName" — should be at most 1
        val count = result.split("\"originalName\"").size - 1
        assertTrue("at most 1 result, got $count", count <= 1)
    }

    @Test
    fun searchActivatesMatchedTools() = runBlocking {
        val registry = ToolProviderRegistry(listOf(naviProvider()))
        val store = ToolActivationStore()
        val workspace = VirtualWorkspace(
            fileSystem = singleRootFs(),
            mcpRegistry = registry,
            activationStore = store,
        )
        workspace.executeJson(
            toolName = "search_tools",
            argumentsJson = """{"query":"周边搜索 停车场"}""",
            context = MaterializerContext(runId = "test-activate"),
        )
        val activated = store.activated("test-activate")
        assertTrue("should have activated at least one tool", activated.isNotEmpty())
        assertTrue(
            "AroundSearch should be activated",
            activated.any { it.originalToolName.contains("AroundSearch") },
        )
    }

    @Test
    fun activatedMatchesAreMarkedCallableWithoutASchemaPathToReread() = runBlocking {
        val registry = ToolProviderRegistry(listOf(naviProvider()))
        val store = ToolActivationStore()
        val workspace = VirtualWorkspace(fileSystem = singleRootFs(), mcpRegistry = registry, activationStore = store)
        val result = workspace.executeJson(
            toolName = "search_tools",
            argumentsJson = """{"query":"周边搜索 停车场"}""",
            context = MaterializerContext(runId = "test-callable"),
        )
        val matches = kotlinx.serialization.json.Json.parseToJsonElement(result)
            .let { it as kotlinx.serialization.json.JsonObject }["matches"] as kotlinx.serialization.json.JsonArray
        val activatedNames = store.activated("test-callable").map { it.toolName }.toSet()
        assertTrue(matches.isNotEmpty())
        for (match in matches.map { it as kotlinx.serialization.json.JsonObject }) {
            val name = (match["name"] as kotlinx.serialization.json.JsonPrimitive).content
            if (name in activatedNames) {
                assertEquals("true", match["callable"].toString())
                assertFalse("callable match must not point at schema.json", match.containsKey("schemaPath"))
            } else {
                assertTrue(match.containsKey("schemaPath"))
            }
        }
    }

    @Test
    fun everyMatchIsActivatedSoLaterRequestsFindItInTheToolList() = runBlocking {
        val tools = (1..8).map { ToolDescriptor("command_ac_Setting$it", "空调设置 $it", """{"type":"object"}""") }
        val store = ToolActivationStore()
        val workspace = VirtualWorkspace(
            fileSystem = singleRootFs(),
            mcpRegistry = ToolProviderRegistry(listOf(TestProvider("tools_json", tools))),
            activationStore = store,
        )
        workspace.executeJson(
            toolName = "search_tools",
            argumentsJson = """{"query":"空调设置","max_results":8}""",
            context = MaterializerContext(runId = "test-all-matches"),
        )

        assertEquals(8, store.activated("test-all-matches").size)
    }

    @Test
    fun searchWithoutActivationStoreStillReturnsResults() = runBlocking {
        val registry = ToolProviderRegistry(listOf(naviProvider()))
        val workspace = VirtualWorkspace(
            fileSystem = singleRootFs(),
            mcpRegistry = registry,
        )
        val result = workspace.executeJson(
            toolName = "search_tools",
            argumentsJson = """{"query":"周边搜索"}""",
            context = MaterializerContext(runId = "test-no-store"),
        )
        assertTrue(result.contains("\"ok\":true"))
        assertTrue(result.contains("AroundSearch"))
    }

    // --- helpers ---

    private fun singleRootFs(): cc.noharry.agent.vfs.VfsFileSystem {
        val tmp = java.io.File.createTempFile("search-tools-test", "").apply { delete(); mkdirs() }
        return cc.noharry.agent.vfs.VfsFileSystem.singleFileRoot(tmp)
    }

    private fun naviProvider(): McpClientProvider = TestProvider(
        serverId = "tools_json",
        tools = listOf(
            ToolDescriptor("command_navi_KeyWordSearch", "用关键字搜索导航POI信息", """{"type":"object"}"""),
            ToolDescriptor("command_navi_AroundSearch", "周边搜索导航POI信息", """{"type":"object"}"""),
            ToolDescriptor("command_navi_AlongWordSearch", "沿途搜索poi信息", """{"type":"object"}"""),
            ToolDescriptor("command_navi_CalRoute", "路线偏好设置并刷新路线", """{"type":"object"}"""),
        ),
    )

    private fun musicProvider(): McpClientProvider = TestProvider(
        serverId = "tools_json",
        tools = listOf(
            ToolDescriptor("command_music_PlaySearchMusic", "搜索并播放歌曲", """{"type":"object"}"""),
            ToolDescriptor("command_music_CancelCollect", "取消收藏歌曲", """{"type":"object"}"""),
        ),
    )

    private class TestProvider(
        override val serverId: String,
        private val tools: List<ToolDescriptor>,
    ) : McpClientProvider {
        override val providerConcurrency: ProviderConcurrencyDeclaration =
            ProviderConcurrencyDeclaration.serialized()
        override suspend fun initialize(context: ToolInitializeContext): ToolInitializeResult =
            ToolInitializeResult.connected()
        override suspend fun listTools(): List<ToolDescriptor> = tools
        override suspend fun callTool(
            toolName: String,
            argumentsJson: String,
            context: ToolCallContext,
        ): ToolCallResult = ToolCallResult(success = true, resultJson = "{}")
    }
}

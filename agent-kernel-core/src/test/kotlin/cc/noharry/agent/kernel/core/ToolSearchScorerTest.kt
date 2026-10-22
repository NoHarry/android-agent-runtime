@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSearchScorerTest {
    @Test
    fun chineseSearchMatchesAcrossDifferentCharacterOffsets() {
        val tools = listOf(tool("poi_search", "可搜索地点名称"), tool("music_play", "播放音乐"))
        assertEquals("poi_search", rankMcpTools(tools, "搜索", 1).single().tool.originalToolName)
    }

    @Test
    fun mixedChineseAndAcronymQueriesKeepBothSearchTerms() {
        val tools = listOf(tool("poi_search", "查询目的地"), tool("music_play", "播放音乐"))
        assertEquals("poi_search", rankMcpTools(tools, "POI地址", 1).single().tool.originalToolName)
        assertEquals("poi_search", rankMcpTools(tools, "POI", 1).single().tool.originalToolName)
    }

    @Test
    fun exactToolIdentityOutranksReferencesInOtherToolDescriptions() {
        val tools = listOf(
            tool("navigation_route_calculate", "使用已确认终点坐标计算路线。".repeat(20)),
            tool("navigation_route_refresh", "navigation_route_calculate"),
        )
        for (query in listOf("navigation_route_calculate", "mcp__test__navigation_route_calculate")) {
            assertEquals("navigation_route_calculate", rankMcpTools(tools, query, 1).single().tool.originalToolName)
        }
        assertTrue(rankMcpTools(tools, "", 5).isEmpty())
    }

    private fun tool(name: String, description: String): McpToolRef = McpToolRef(
        provider = provider, serverId = "test", normalizedServerId = "test", originalToolName = name,
        normalizedToolName = name, qualifiedName = "mcp__test__$name",
        descriptor = ToolDescriptor(name = name, description = description, inputSchemaJson = "{}"),
    )

    private val provider = object : McpClientProvider {
        override val serverId = "test"
        override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
        override suspend fun listTools() = emptyList<ToolDescriptor>()
        override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext) =
            ToolCallResult(success = true, resultJson = "{}")
    }
}

@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.tools
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

/** Ranking on a slice of Bothy's real catalog, where the old scorer went wrong. */
class ToolSearchRealCatalogTest {
    private fun tool(server: String, name: String, description: String) = McpToolRef(
        provider = object : cc.noharry.agent.kernel.api.ToolProvider {
            override val providerId = server
            override suspend fun listTools() = emptyList<cc.noharry.agent.kernel.api.ToolDescriptor>()
            override suspend fun callTool(toolName: String, argumentsJson: String, context: cc.noharry.agent.kernel.api.ToolCallContext) =
                cc.noharry.agent.kernel.api.ToolCallResult(false, "{}")
        },
        serverId = server, normalizedServerId = server, originalToolName = name, normalizedToolName = name,
        qualifiedName = mcpQualifiedName(server, name),
        descriptor = cc.noharry.agent.kernel.api.ToolDescriptor(name, description, """{"type":"object"}"""),
    )

    private val tools = listOf(
        tool("home", "get_home_status", "查看虚拟小家现在的状态：每个房间的温度、湿度、灯、窗帘和空调，门锁，扫地机，当前场景。"),
        tool("home", "set_light", "开关房间的灯、调亮度和色调。给了亮度或色调就会开灯。"),
        tool("nav", "start_navigation", "规划一条从车现在的位置出发、经过若干途经点到终点的路线（最多 16 个途经点）。depart 必填：用户说导航去、出发、走吧时为 true，只是问路线时为 false。终点和途经点用 search_places 拿到的坐标。"),
        tool("nav", "stop_navigation", "退出当前导航。"),
        tool("nav", "get_route", "当前导航：终点、还有多远多久、预计几点到，每个途经点还有多远多久，已经过了哪些。"),
    )

    @org.junit.Test fun aOneCharacterQueryFindsTheWordItIsIn() {
        org.junit.Assert.assertEquals("set_light", rankMcpTools(tools, "灯", 1).single().tool.originalToolName)
    }

    @org.junit.Test fun singleCharactersInALongerQueryAddNoNoise() {
        // 点 in 我有点冷 must not find every 途经点.
        org.junit.Assert.assertTrue(rankMcpTools(tools, "我有点冷", 3).isEmpty())
    }

    @org.junit.Test fun aDetailedDescriptionIsNotBuriedUnderAOneLiner() {
        org.junit.Assert.assertEquals("start_navigation", rankMcpTools(tools, "导航去公司", 1).single().tool.originalToolName)
    }
}

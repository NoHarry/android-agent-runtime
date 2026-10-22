@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration

import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.ToolDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDomainIndexerTest {

    @Test
    fun groupsCommandPrefixedToolsIntoDomains() {
        val tools = listOf(
            toolRef("command_navi_KeyWordSearch", "用关键字搜索导航POI信息"),
            toolRef("command_navi_AroundSearch", "周边搜索导航POI信息"),
            toolRef("command_music_PlaySearchMusic", "搜索并播放歌曲"),
            toolRef("command_music_CancelCollect", "取消收藏歌曲"),
        )
        val index = ToolDomainIndexer.index(tools)
        val domainIds = index.domains.map { it.id }
        assertTrue("navi domain expected", domainIds.contains("navi"))
        assertTrue("music domain expected", domainIds.contains("music"))
        val navi = index.domains.first { it.id == "navi" }
        assertEquals(2, navi.toolCount)
    }

    @Test
    fun groupsMcpQualifiedToolsByServerId() {
        val tools = listOf(
            toolRef("get_weather", qualifiedName = "mcp__device__get_weather", description = "天气查询"),
            toolRef("get_traffic", qualifiedName = "mcp__device__get_traffic", description = "路况查询"),
        )
        val index = ToolDomainIndexer.index(tools)
        val device = index.domains.first { it.id == "device" }
        assertEquals(2, device.toolCount)
    }

    @Test
    fun unknownPrefixedToolsFallIntoOtherDomain() {
        val tools = listOf(
            toolRef("randomToolName", description = "does stuff"),
        )
        val index = ToolDomainIndexer.index(tools)
        val other = index.domains.first { it.id == "other" }
        assertEquals(1, other.toolCount)
    }

    @Test
    fun domainsSortedByToolCountDescending() {
        val tools = listOf(
            toolRef("command_music_Play", "play"),
            toolRef("command_navi_Search1", "search1"),
            toolRef("command_navi_Search2", "search2"),
            toolRef("command_navi_Search3", "search3"),
        )
        val index = ToolDomainIndexer.index(tools)
        assertEquals("navi", index.domains.first().id)
        assertEquals(3, index.domains.first().toolCount)
    }

    @Test
    fun promptSectionListsDomainsWithCounts() {
        val tools = listOf(
            toolRef("command_navi_KeyWordSearch", "用关键字搜索导航POI信息"),
            toolRef("command_music_Play", "播放歌曲"),
        )
        val section = ToolDomainIndexer.index(tools).toPromptSection()
        assertTrue(section.contains("navi (1 tools)"))
        assertTrue(section.contains("music (1 tools)"))
        assertTrue(section.contains("search_tools"))
    }

    @Test
    fun emptyToolsProduceEmptySection() {
        val section = ToolDomainIndexer.index(emptyList()).toPromptSection()
        assertTrue(section.isEmpty())
    }

    @Test
    fun aBackendNamedByAreaIsSummarizedByItsAreas() {
        val names = listOf(
            "system_application_app_launch", "system_application_app_exit", "system_application_systemapp_catalog_query",
            "system_volume_set", "system_volume_adjust",
            "navigation_poi_search", "navigation_poi_suggestion_search", "navigation_map_open", "navigation_route_calculate",
            "media_music_playByKeyword", "media_music_search", "media_video_search", "media_radio_play",
            "home_heater_power_set", "home_heater_mode_set", "home_lamp_ambient_set", "home_ac_power_set",
            "device_aiot_discovery_start",
        )
        val tools = names.map { toolRef(it, qualifiedName = "mcp__device__$it", description = "取消当前进行中的 AiOT 设备发现扫描。") }
        val device = ToolDomainIndexer.index(tools).domains.single()
        assertEquals(
            "system 5 (application/volume) · navigation 4 (poi/map/route) · media 4 (music/video/radio) · " +
                "home 4 (heater/lamp/ac) · device 1 (aiot)",
            device.summary,
        )
        assertTrue(ToolDomainIndexer.index(tools).toPromptSection().contains("- device (18 tools): system 5 (application/volume)"))
    }

    @Test
    fun toolNamesLedByVerbsKeepTheDescriptionSummary() {
        val names = listOf("get_weather", "get_forecast", "get_alerts", "set_unit", "set_location", "set_alarm")
        val tools = names.map { toolRef(it, qualifiedName = "mcp__weather__$it", description = "查询天气预报") }
        assertEquals("查询天气预报", ToolDomainIndexer.index(tools).domains.single().summary)
    }

    @Test
    fun chineseDescriptionUsedAsSummary() {
        val tools = listOf(
            toolRef("command_navi_AroundSearch", "周边搜索导航POI信息"),
        )
        val index = ToolDomainIndexer.index(tools)
        val navi = index.domains.first { it.id == "navi" }
        assertTrue("summary should contain Chinese description", navi.summary.contains("周边搜索"))
    }

    private fun toolRef(
        originalName: String,
        description: String = "",
        qualifiedName: String? = null,
    ): McpToolRef {
        val normalized = originalName.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
        val qualified = qualifiedName ?: "mcp__tools_json__$normalized"
        val provider = object : McpClientProvider {
            override val providerConcurrency: ProviderConcurrencyDeclaration =
                ProviderConcurrencyDeclaration.serialized()
            override val serverId = "tools_json"
            override suspend fun listTools() = emptyList<ToolDescriptor>()
            override suspend fun callTool(
                toolName: String,
                argumentsJson: String,
                context: cc.noharry.agent.kernel.api.ToolCallContext,
            ) = cc.noharry.agent.kernel.api.ToolCallResult(success = true, resultJson = "{}")
        }
        return McpToolRef(
            provider = provider,
            serverId = "tools_json",
            normalizedServerId = "tools_json",
            originalToolName = originalName,
            normalizedToolName = normalized,
            qualifiedName = qualified,
            descriptor = ToolDescriptor(
                name = originalName,
                description = description,
                inputSchemaJson = """{"type":"object"}""",
            ),
        )
    }
}

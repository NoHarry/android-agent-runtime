package cc.noharry.agent.appserver.tools

import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.server.OwnToolServer
import cc.noharry.agent.appserver.server.PluginAdditions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which tools are a service's: not a running plugin's tool server that says it is none (cards, the screen) or is the
 * main agent's alone (plans, a hand-off to the user's agent).
 */
class ServiceToolsTest {
    @Test
    fun aServicesToolIsAnyMcpToolButTheServersOwnAndHandOffs() {
        assertTrue(isServiceTool("mcp__amap__route_plan"))
        assertTrue(isServiceTool("mcp__memory__user_memory"))
        assertTrue(isServiceTool("mcp__tools_json__get_weather"))
        assertFalse(isServiceTool("web_search"))
        // Not while no plugin names them.
        assertTrue(isServiceTool("mcp__app_ui__show_card"))
        AppHost.install(
            additions = PluginAdditions(
                ownToolServers = mapOf(
                    "app_ui" to OwnToolServer(mainAgentOnly = true),
                    "android_use" to OwnToolServer(service = false, worksDevice = true),
                    "plans" to OwnToolServer(mainAgentOnly = true),
                    "experimental_a2a*" to OwnToolServer(mainAgentOnly = true),
                ),
            ),
        )
        try {
            assertFalse(isServiceTool("mcp__app_ui__show_card"))
            assertFalse(isServiceTool("mcp__android_use__android_action"))
            assertTrue(isServiceTool("mcp__memory__user_memory"))
            assertFalse(isServiceTool("mcp__plans__plan_manage"))
            assertFalse(isServiceTool("mcp__experimental_a2a_grok__send"))
        } finally {
            AppHost.install(additions = PluginAdditions.NONE)
        }
    }
}

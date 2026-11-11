package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.mcp.DEBUG_MCP_PROVIDER_TOOLS_JSON
import cc.noharry.agent.appserver.mcp.defaultMcpProviders
import cc.noharry.agent.appserver.model.resolveStreamEnabled
import cc.noharry.agent.appserver.protocol.view.LIVE_MODEL_LABEL
import cc.noharry.agent.appserver.protocol.view.SIMULATED_MODEL_LABEL
import cc.noharry.agent.appserver.server.PluginAdditions
import cc.noharry.agent.appserver.server.PluginToolServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSessionDefaultsTest {
    @Test
    fun consoleDefaultsToLiveModelMode() {
        assertFalse(DEFAULT_USE_SIMULATED_MODEL)
        assertEquals("live model", LIVE_MODEL_LABEL)
        assertEquals("simulated MCP", SIMULATED_MODEL_LABEL)
    }

    @Test
    fun consoleDefaultsToStreamMode() {
        assertTrue(DEFAULT_STREAM_ENABLED)
        assertTrue(resolveStreamEnabled(emptyMap()))
        assertFalse(resolveStreamEnabled(mapOf("AGENT_RUNTIME_LIVE_PROVIDER_STREAM" to "0")))
        assertTrue(resolveStreamEnabled(mapOf("AGENT_RUNTIME_MODEL_PROVIDER_STREAM" to "1")))
    }

    @Test
    fun consoleDefaultsEveryFixedMcpProviderOffButThoseOnAtFirst() {
        assertEquals(listOf(DEBUG_MCP_PROVIDER_TOOLS_JSON), defaultMcpProviders().map { it.id })
        val servers = listOf(false, true).map { on -> PluginToolServer("tools_$on", "tools", { "" }, onAtFirst = on) { _, _ -> error("rows only") } }
        AppHost.install(additions = PluginAdditions(toolServers = servers))
        val providers = try {
            defaultMcpProviders().associateBy { it.id }
        } finally {
            AppHost.install(additions = PluginAdditions.NONE)
        }

        assertFalse(providers.getValue(DEBUG_MCP_PROVIDER_TOOLS_JSON).enabled)
        assertFalse(providers.getValue("tools_false").enabled)
        assertTrue(providers.getValue("tools_true").enabled)
    }
}

package cc.noharry.agent.device.appserver

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.mcp.DEBUG_MCP_PROVIDER_TOOLS_JSON
import cc.noharry.agent.appserver.mcp.loadMcpProviders
import cc.noharry.agent.appserver.protocol.ServerFeatures
import cc.noharry.agent.appserver.server.AppServerConfig
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.server.PluginDiscovery
import cc.noharry.agent.appserver.server.PluginHost
import cc.noharry.agent.appserver.server.PluginSwitches
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The device's calendar, notifications and media come with the plugin, which the module's manifest brings to an app that
 * has the app server, as rows of the MCP list a person switches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DeviceToolsPluginTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun freshSwitches() {
        app.getSharedPreferences("app_server_mcp_provider_config", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun bare() = AppServerTesting.reset()

    @Test
    fun theManifestBringsThePluginUnlessTheHostLeavesItOut() {
        assertEquals(listOf<AppServerPlugin>(DeviceToolsPlugin), PluginDiscovery.find(app, AppServerConfig(name = "test")))
        assertEquals(emptyList<AppServerPlugin>(), PluginDiscovery.find(app, AppServerConfig(name = "test", ignoredPlugins = setOf("device-tools"))))
    }

    @Test
    fun withoutThePluginThereAreNoDeviceRows() {
        assertEquals(listOf(DEBUG_MCP_PROVIDER_TOOLS_JSON), loadMcpProviders(app).map { it.id })
    }

    @Test
    fun runningItListsTheThreeAfterTheConfiguredServersAsTheyWere() = runBlocking {
        val host = PluginHost(app, listOf(DeviceToolsPlugin), AllOn).apply { startAll() }
        AppServerTesting.running(host.additions)
        assertTrue(ServerFeatures.DEVICE_MEDIA in host.additions.offers)
        val rows = loadMcpProviders(app)
        assertEquals(
            listOf(DEBUG_MCP_PROVIDER_TOOLS_JSON, DEVICE_CALENDAR_TOOL_SERVER, DEVICE_NOTIFICATIONS_TOOL_SERVER, DEVICE_MEDIA_TOOL_SERVER),
            rows.map { it.id },
        )
        assertEquals(listOf("Tools JSON", "Device Calendar", "Device Notifications", "Device Media"), rows.map { it.label })
        // Calendar and notifications ask for permissions, so they wait for the person; media is on from the start.
        assertEquals(listOf(false, false, false, true), rows.map { it.enabled })
        // A switch saved before the plugin existed still reads the same.
        app.getSharedPreferences("app_server_mcp_provider_config", Context.MODE_PRIVATE).edit()
            .putBoolean("mcp_provider_device_calendar", true).putBoolean("mcp_provider_device_media", false).commit()
        assertEquals(listOf(false, true, false, false), loadMcpProviders(app).map { it.enabled })
        val servers = host.additions.toolServers
        assertEquals(listOf(DEVICE_MEDIA_TOOL_SERVER), servers.filter { it.worksDevice }.map { it.id })
        assertEquals(listOf(DEVICE_MEDIA_TOOL_SERVER), servers.filter { it.notificationAccess != null }.map { it.id })
        // Each lists its tools for the settings page, the media ones with the playback hints around them.
        servers.forEach { server -> assertTrue(server.id, server.provider(app) { it }.listTools().isNotEmpty()) }
        assertTrue(servers.single { it.id == DEVICE_MEDIA_TOOL_SERVER }.provider(app) { it } is MediaToolHints)
    }

    @Test
    fun itSaysAMediaVolumeReadOnlyReadsAndLeavesEveryOtherCallToItsTool() {
        val host = PluginHost(app, listOf(DeviceToolsPlugin), AllOn).apply { startAll() }
        val acts = { tool: String, arguments: String -> host.additions.acts(DEVICE_MEDIA_TOOL_SERVER, tool, arguments) }
        assertEquals(false, acts("media.volume", """{"action":"get"}"""))
        listOf("""{"action":"set","percent":30}""", """{"action":"up"}""", """{"action":"down"}""", """{"action":"GET"}""", "{}", "not json")
            .forEach { assertNull(it, acts("media.volume", it)) }
        // The other media tools say themselves whether they act (media.status only reads).
        assertNull(acts("media.status", "{}"))
        assertNull(acts("media.control", """{"action":"get"}"""))
        assertNull(host.additions.acts(DEVICE_CALENDAR_TOOL_SERVER, "calendar.events", "{}"))
    }

    private object AllOn : PluginSwitches {
        override fun isOn(id: String): Boolean = true

        override fun set(id: String, on: Boolean) = Unit
    }
}

@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.console.fixtureProfile
import cc.noharry.agent.appserver.console.fixtureProviderConfig
import cc.noharry.agent.appserver.console.noopDiagnostics
import cc.noharry.agent.appserver.model.ScriptedModel
import cc.noharry.agent.appserver.server.PluginAdditions
import cc.noharry.agent.appserver.server.PluginToolServer
import cc.noharry.agent.appserver.server.PluginTools
import cc.noharry.agent.appserver.trajectory.appSessionEventLogRoot
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A plugin's tools in a conversation that called them while the plugin was off: the runtime refuses the call as
 * `TOOL_UNAVAILABLE`, in its words, and once the plugin runs again the same conversation calls them straight away, as
 * before. The same for a plugin's tool server. A conversation that lost the tools it found (the process ended) calls
 * them again without searching, as its earlier turns show them; the scripted model searches for a tool it only named
 * in its own words. Each turn is a runtime of its own, as the server builds them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ToolServerComesBackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shelf = Shelf()
    private val searches = mutableListOf<String>()
    private val script = ScriptedModel { on("count") { call("shelf", "count"); answer { it.single() } } }
    private val plugin = PluginAdditions(tools = listOf(PluginTools(false) { _, _ -> listOf(shelf) }))

    @Before
    fun fresh() {
        context.getSharedPreferences("app_server_mcp_provider_config", Context.MODE_PRIVATE).edit().clear().commit()
        RuntimeHistories.clearCache()
    }

    @After
    fun bare() {
        AppHost.install(additions = PluginAdditions.NONE, simulatedModel = null)
        RuntimeHistories.clearCache()
    }

    @Test
    fun aPluginsToolWorksAgainInTheConversationThatCalledItWhileItWasOff() {
        val session = session("plugin-off-on")
        assertEquals(COUNTED, turn(session, plugin))
        // What the model reads, word for word.
        assertEquals(UNAVAILABLE, turn(session, PluginAdditions.NONE))
        assertEquals(COUNTED, turn(session, plugin))
        // Found once, in the first turn: the conversation kept it through the turn it was off.
        assertEquals(1, searches.size)
        assertEquals(2, shelf.calls)
    }

    @Test
    fun aPluginsToolServerComesBackAfterThePluginRestarts() {
        val session = session("tool-server-off-on")
        val server = PluginAdditions(toolServers = listOf(PluginToolServer("shelf", "Shelf", { "Shelf" }, onAtFirst = true) { _, wrap -> wrap(shelf) }))
        assertEquals(COUNTED, turn(session, server))
        assertTrue(turn(session, PluginAdditions.NONE).contains("\"code\":\"TOOL_UNAVAILABLE\""))
        assertEquals(COUNTED, turn(session, server))
        assertEquals(1, searches.size)
        assertEquals(2, shelf.calls)
    }

    @Test
    fun aConversationThatLostTheToolsItFoundCallsThemWithoutSearching() {
        val session = session("process-ended")
        assertEquals(COUNTED, turn(session, plugin))
        // The process ended: the tools the conversation found are gone, its history is not, and it shows them.
        forgetSessionTools(session)
        assertEquals(COUNTED, turn(session, plugin))
        assertEquals(1, searches.size)
        assertEquals(2, shelf.calls)
    }

    @Test
    fun aToolOnlyNamedInTheScriptsOwnWordsIsSearchedFor() {
        val session = session("never-found")
        assertTrue(turn(session, PluginAdditions.NONE).contains("mcp__shelf__count is not among the tools"))
        assertEquals(COUNTED, turn(session, plugin))
        assertEquals(2, searches.size)
    }

    private fun session(id: String): String = id.also { appSessionEventLogRoot(context, it).deleteRecursively() }

    /** One turn of [session] with [plugins] running: what the model answered, which is what the count call told it. */
    private fun turn(session: String, plugins: PluginAdditions): String = runBlocking {
        AppHost.install(additions = plugins, simulatedModel = { Searches(script, searches) })
        buildRuntime(
            context, fixtureProviderConfig(), fixtureProfile(), false, true, appWorkspaceRoot(context),
            appSessionEventLogRoot(context, session), "", null, noopDiagnostics(context), emptyList(), sessionId = session,
        ).use { it.resumeThread(session).run("count them").finalResponse }
    }

    /** [delegate], noting each search it makes. */
    private class Searches(private val delegate: ModelClient, private val seen: MutableList<String>) : ModelClient {
        override fun complete(request: ModelRequest): Flow<ModelEvent> = delegate.complete(request).onEach { event ->
            if (event is ModelEvent.ToolCall && event.name == "search_tools") seen += event.argumentsJson
        }
    }

    private class Shelf : ToolProvider {
        var calls = 0
        override val providerId = "shelf"

        override suspend fun listTools() = listOf(
            ToolDescriptor("count", "Counts the items on the shelf.", """{"type":"object","properties":{}}""", effectKind = "READ_ONLY"),
        )

        override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
            calls++
            return ToolCallResult(success = true, resultJson = COUNTED)
        }
    }

    private companion object {
        const val COUNTED = """{"items":3}"""
        const val UNAVAILABLE = """{"ok":false,"code":"TOOL_UNAVAILABLE","tool":"mcp__shelf__count","message":"This tool is """ +
            """not available now: the tool server or plugin that provides it is switched off, cannot be reached, or was """ +
            """removed. Searching for it or calling it again will not help until it is back; tell the person.",""" +
            """"retryable":false}"""
    }
}

@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.console.fixtureProfile
import cc.noharry.agent.appserver.console.fixtureProviderConfig
import cc.noharry.agent.appserver.console.noopDiagnostics
import cc.noharry.agent.appserver.mcp.loadMcpProviders
import cc.noharry.agent.appserver.model.ProviderAdapter
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.model.SearchBackend
import cc.noharry.agent.appserver.model.WebLookup
import cc.noharry.agent.appserver.trajectory.appSessionEventLogRoot
import cc.noharry.agent.runtime.WebFallbackPage
import cc.noharry.agent.runtime.WebSearchResult
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a host that turns nothing on offers the model: only the tools the protocol needs. Each [BuiltInFeatures] switch
 * adds its own tools, read from the first model request of a real run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BareHostToolsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun freshSettings() {
        listOf("app_server_basic_tools", "app_server_subagents").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After
    fun bare() {
        AppHost.install(builtIns = BuiltInFeatures.NONE, simulatedModel = null, providerAdapters = emptyList())
        SearchBackend.set(context, fixtureProfile().id, false)
    }

    @Test
    fun aBareHostOffersOnlyWhatTheProtocolNeeds() {
        assertEquals(PROTOCOL_TOOLS, offered(BuiltInFeatures.NONE))
        // A service that can search changes nothing while the host leaves the web off.
        withLookup { assertEquals(PROTOCOL_TOOLS, offered(BuiltInFeatures.NONE)) }
    }

    @Test
    fun eachSwitchAddsItsOwnTools() {
        assertEquals(PROTOCOL_TOOLS + setOf("write_file", "edit_file"), offered(BuiltInFeatures(fileWriting = true)))
        assertEquals(PROTOCOL_TOOLS + setOf("web_fetch"), offered(BuiltInFeatures(web = true)))
        withLookup { assertEquals(PROTOCOL_TOOLS + setOf("web_fetch", "web_search"), offered(BuiltInFeatures(web = true))) }
        assertEquals(PROTOCOL_TOOLS + setOf("spawn_agent", "wait_agent", "cancel_agent"), offered(BuiltInFeatures(subagents = true)))
        // All on is what every host got before these were switches (exec_js switched off at first).
        val all = BuiltInFeatures(fileWriting = true, web = true, scripts = true, subagents = true)
        assertEquals(PROTOCOL_TOOLS + setOf("write_file", "edit_file", "web_fetch", "spawn_agent", "wait_agent", "cancel_agent"), offered(all))
        // exec_js comes with scripts, still switched off on the basic tools page until the person turns it on.
        assertEquals(PROTOCOL_TOOLS, offered(BuiltInFeatures(scripts = true)))
        AppHost.install(builtIns = BuiltInFeatures(scripts = true))
        saveBasicTool(context, "exec_js", true)
        assertEquals(PROTOCOL_TOOLS + "exec_js", offered(BuiltInFeatures(scripts = true)))
    }

    @Test
    fun aToolOfAFeatureLeftOffHasNoRowAndCannotBeSwitchedOn() {
        AppHost.install(builtIns = BuiltInFeatures.NONE)
        assertEquals(BASIC_ROWS, loadBasicTools(context).map { it.name }.toSet())
        listOf("write_file", "web_fetch", "exec_js", "spawn_agent").forEach { name ->
            assertEquals("Unknown basic tool.", runCatching { saveBasicTool(context, name, true) }.exceptionOrNull()?.message)
        }
        assertEquals(PROTOCOL_TOOLS, offered(BuiltInFeatures.NONE))
        AppHost.install(builtIns = BuiltInFeatures(fileWriting = true, web = true, scripts = true, subagents = true))
        assertEquals(BASIC_ROWS + ALL_SWITCHED, loadBasicTools(context).map { it.name }.toSet())
    }

    /** The native tools the first model request of a run offers, with [features] on. */
    private fun offered(features: BuiltInFeatures): Set<String> = runBlocking {
        val model = FirstRequest()
        AppHost.install(builtIns = features, simulatedModel = { model })
        val sessionId = "bare-host"
        appSessionEventLogRoot(context, sessionId).deleteRecursively()
        val runtime = buildRuntime(
            context, fixtureProviderConfig(), fixtureProfile(), false, true, appWorkspaceRoot(context),
            appSessionEventLogRoot(context, sessionId), "", null, noopDiagnostics(context), loadMcpProviders(context),
            sessionId = sessionId,
        )
        try {
            runtime.resumeThread(sessionId).run("hello")
        } finally {
            runtime.close()
        }
        model.tools.toSet()
    }

    /** With a service that can search and fetch through [fixtureProfile] for [block]. */
    private fun withLookup(block: () -> Unit) {
        AppHost.install(providerAdapters = listOf(LookupAdapter))
        SearchBackend.set(context, fixtureProfile().id, true)
        try {
            block()
        } finally {
            AppHost.install(providerAdapters = emptyList())
            SearchBackend.set(context, fixtureProfile().id, false)
        }
    }

    private class FirstRequest : ModelClient {
        var tools: List<String> = emptyList()

        override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
            if (tools.isEmpty()) tools = request.tools.map { it.name }
            emit(ModelEvent.ToolCall("finish", "finish", """{"response":"done","outcome":"success"}"""))
            emit(ModelEvent.Completed)
        }
    }

    private object LookupAdapter : ProviderAdapter {
        override fun lookup(context: Context, config: ProviderConfig, profile: ProviderProfile): WebLookup = object : WebLookup {
            override val label: String = "lookup.test"

            override suspend fun search(query: String, count: Int): List<WebSearchResult> = emptyList()

            override suspend fun fetch(url: String): WebFallbackPage? = null
        }
    }

    private companion object {
        /** What the protocol needs: finding and calling the host's tools, asking, the todo list, finishing, reading. */
        val PROTOCOL_TOOLS = setOf("search_tools", "call_tool", "finish", "ask", "ask_async", "update_todo", "list_dir", "read_file", "grep")

        /** The basic tools page of a bare host: what the protocol needs but call_tool, which has no switch. */
        val BASIC_ROWS = PROTOCOL_TOOLS - "call_tool"

        val ALL_SWITCHED = setOf("write_file", "edit_file", "web_fetch", "web_search", "exec_js", "spawn_agent", "wait_agent", "cancel_agent")
    }
}

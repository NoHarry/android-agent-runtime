@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.console.fixtureProfile
import cc.noharry.agent.appserver.console.fixtureProviderConfig
import cc.noharry.agent.appserver.console.noopDiagnostics
import cc.noharry.agent.appserver.mcp.loadMcpProviders
import cc.noharry.agent.appserver.model.loadModelImageInput
import cc.noharry.agent.appserver.server.PluginAdditions
import cc.noharry.agent.appserver.server.PluginToolServer
import cc.noharry.agent.appserver.skills.RuntimeSkill
import cc.noharry.agent.appserver.trajectory.appSessionEventLogRoot
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a plugin gives each run beside its backend tools: its tool servers the person switched on, built with the host's
 * wrapper ([PluginToolServer]), and tools that depend on what the run is ([PluginAdditions.runTools]); a skill that
 * needs operating the screen is read only in a run given the `android_use` server that way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PluginToolMountTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val built = mutableListOf<String>()
    private val wrapped = mutableListOf<String>()

    @Before
    fun freshSwitches() {
        context.getSharedPreferences("app_server_mcp_provider_config", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun bare() = AppHost.install(additions = PluginAdditions.NONE, runs = RunAddOns.NONE, skills = { emptyList() }, simulatedModel = null)

    @Test
    fun onlyTheToolServersSwitchedOnAreBuiltWithTheHostsWrapper() {
        AppHost.install(additions = PluginAdditions(toolServers = listOf(server("dev_on", true), server("dev_off", false))), runs = Wrapping())
        run(loadMcpProviders(context))
        assertEquals(listOf("dev_on"), built)
        assertTrue("dev_on" in wrapped)
        // A plugin started after the run's rows were read: its saved switch decides.
        built.clear()
        context.getSharedPreferences("app_server_mcp_provider_config", Context.MODE_PRIVATE).edit().putBoolean("mcp_provider_dev_off", true).commit()
        run(emptyList())
        assertEquals(listOf("dev_on", "dev_off"), built)
    }

    @Test
    fun runToolsSeeTheRunAndTheScreenSkillWaitsForThem() {
        val seen = mutableListOf<RunFacts>()
        val screen = Named("android_use")
        AppHost.install(skills = { SCREEN_SKILL })
        assertFalse(run(emptyList()).contains(SKILL_TEXT))
        AppHost.install(additions = PluginAdditions(runTools = listOf { facts -> seen += facts; listOf(screen) }))
        assertTrue(run(emptyList()).contains(SKILL_TEXT))
        assertEquals(1, seen.size)
        // Whether its model reads pictures, as the model's settings say.
        assertEquals(loadModelImageInput(context, fixtureProviderConfig().editState(fixtureProfile())), seen.single().imageInput)
        assertFalse(seen.single().child)
    }

    private fun server(id: String, on: Boolean) = PluginToolServer(id, id, { id }, onAtFirst = on) { _, wrap ->
        built += id
        wrap(Named(id))
    }

    /** The first model request's system prompt and messages, of a run built with [mcpProviders] as its rows. */
    private fun run(mcpProviders: List<cc.noharry.agent.appserver.protocol.McpProviderState>): String = runBlocking {
        var seen = ""
        AppHost.install(simulatedModel = {
            object : ModelClient {
                override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
                    if (seen.isEmpty()) seen = request.system.orEmpty() + request.messages.joinToString("\n") { it.content }
                    emit(ModelEvent.ToolCall("finish", "finish", """{"response":"done","outcome":"success"}"""))
                    emit(ModelEvent.Completed)
                }
            }
        })
        val runtime = buildRuntime(
            context, fixtureProviderConfig(), fixtureProfile(), false, true, appWorkspaceRoot(context),
            appSessionEventLogRoot(context, "plugin-tools"), "", null, noopDiagnostics(context), mcpProviders,
        )
        try {
            runtime.startThread().run("hello")
        } finally {
            runtime.close()
        }
        seen
    }

    private inner class Wrapping : RunAddOns {
        override fun toolWrapper(context: Context, sessionContext: String, simulated: Boolean): (ToolProvider) -> ToolProvider =
            { provider -> provider.also { wrapped += it.providerId } }
    }


    private class Named(override val providerId: String) : ToolProvider {
        override suspend fun listTools(): List<ToolDescriptor> = emptyList()

        override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult =
            ToolCallResult(success = false, resultJson = "{}")
    }

    private companion object {
        const val SKILL_TEXT = "Tap what the person names."
        val SCREEN_SKILL = listOf(
            RuntimeSkill("screen", "Screen", "Operating the screen", "", enabled = true, preload = true, requiresToolServer = "android_use", text = SKILL_TEXT),
        )
    }
}

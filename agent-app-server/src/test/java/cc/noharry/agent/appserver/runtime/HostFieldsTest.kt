@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.console.fixtureProfile
import cc.noharry.agent.appserver.console.fixtureProviderConfig
import cc.noharry.agent.appserver.console.noopDiagnostics
import cc.noharry.agent.appserver.model.ModelPreset
import cc.noharry.agent.appserver.model.PREFS_NAME
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ScriptedModel
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.trajectory.appSessionEventLogRoot
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.HookDecision
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.spi.ToolUse
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
 * A simple host's plain fields reach each run: its rules open the host's part of the system prompt, its hooks gate its
 * tools, and the model service its build carries answers a new installation until the person picks another.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HostFieldsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun freshSettings() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun bare() = AppHost.install(
        tools = { emptyList() }, rules = emptyList(), hooks = emptyList(), runs = RunAddOns.NONE, modelPreset = null, simulatedModel = null,
    )

    @Test
    fun theHostsRulesComeBeforeTheRulesThatReadTheRun() {
        var system = ""
        val model = object : ModelClient {
            override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
                system = request.system.orEmpty()
                emit(ModelEvent.ToolCall("finish", "finish", """{"response":"ok"}"""))
                emit(ModelEvent.Completed)
            }
        }
        AppHost.install(rules = listOf(RULE), runs = object : RunAddOns {
            override fun systemRules(facts: RunFacts) = listOf(RUN_RULE)
        }, simulatedModel = { model })
        run("hello")
        assertTrue(system, system.indexOf(RULE) in 0 until system.indexOf(RUN_RULE))
    }

    @Test
    fun theHostsHooksGateItsTools() {
        val drawer = Drawer()
        AppHost.install(tools = { listOf(drawer) }, hooks = listOf(KeepShut), simulatedModel = {
            ScriptedModel {
                otherwise {
                    call("drawer", "open")
                    answer { results -> results.single() }
                }
            }
        })
        val answer = run("open the drawer")
        assertFalse(drawer.opened)
        assertTrue(answer, answer.contains("kept shut"))
    }

    @Test
    fun theBuildsModelAnswersANewInstallationUntilThePersonPicksAnother() {
        AppHost.install(modelPreset = ModelPreset("https://models.example/v1", "example-chat", KEY, label = "Example"))
        val fresh = ProviderConfig.load(context)
        assertEquals("preset", fresh.activeProfile.id)
        assertEquals("Example", fresh.activeProfile.displayLabel)
        assertEquals(ProviderEndpoint.CHAT_COMPLETIONS, fresh.activeProfile.endpoint)
        assertTrue(fresh.isReady(fresh.activeProfile))
        assertFalse(fresh.editState(fresh.activeProfile).toString().contains(KEY))
        assertFalse(AppHost.modelPreset.toString().contains(KEY))
        val mine = ProviderConfig.saveNewProfile(context, "Mine", ModelProvider.OPENAI, ProviderEndpoint.CHAT_COMPLETIONS, "k", "m", "https://mine.example/v1")
        assertEquals("Mine", mine.activeProfile.displayLabel)
        assertEquals("Mine", ProviderConfig.load(context).activeProfile.displayLabel)
        assertTrue(ProviderConfig.load(context).profiles.any { it.id == "preset" })
    }

    private fun run(text: String): String = runBlocking {
        val session = "host-fields"
        appSessionEventLogRoot(context, session).deleteRecursively()
        RuntimeHistories.clearCache()
        buildRuntime(
            context, fixtureProviderConfig(), fixtureProfile(), false, true, appWorkspaceRoot(context),
            appSessionEventLogRoot(context, session), "", null, noopDiagnostics(context), emptyList(), sessionId = session,
        ).use { it.resumeThread(session).run(text).finalResponse }
    }

    private class Drawer : ToolProvider {
        var opened = false
        override val providerId = "drawer"

        override suspend fun listTools() = listOf(ToolDescriptor("open", "Opens the drawer.", """{"type":"object","properties":{}}"""))

        override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
            opened = true
            return ToolCallResult(success = true, resultJson = "{}")
        }
    }

    private object KeepShut : RuntimeHook {
        override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
            if (call.toolName == "mcp__drawer__open") HookDecision.Deny(reason = "kept shut") else HookDecision.Allow()
    }

    private companion object {
        const val RULE = "Keep answers to one sentence."
        const val RUN_RULE = "A rule that reads the run."
        const val KEY = "sk-secret-of-the-build"
    }
}

@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.ApprovalPolicy
import cc.noharry.agent.ThreadOptions
import cc.noharry.agent.TurnStatus
import cc.noharry.agent.appserver.console.fixtureProfile
import cc.noharry.agent.appserver.console.fixtureProviderConfig
import cc.noharry.agent.appserver.console.noopDiagnostics
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.RuntimeHistories
import cc.noharry.agent.appserver.runtime.appWorkspaceRoot
import cc.noharry.agent.appserver.runtime.buildRuntime
import cc.noharry.agent.appserver.trajectory.appSessionEventLogRoot
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scripted model speaks the model's side of a real run for a host's script: it finds the host's tool, calls it
 * with the script's arguments, answers from its result, and in the session's next turn calls a tool found before
 * without searching again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScriptedModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shelf = Shelf()
    private val script = ScriptedModel {
        on(Regex("""put (\w+) away""")) { found ->
            call("shelf", "put_away", mapOf("item" to found.groupValues[1]))
            answer { results -> "Done: " + JSONObject(results.single()).getString("stored") }
        }
        on("count") {
            call("shelf", "count")
            call("shelf", "count")
            answer { results -> "Counted ${results.size} times: ${results.first()}" }
        }
        otherwise { said -> answer("Nothing for $said") }
    }

    @After
    fun bare() {
        AppHost.install(tools = { emptyList() }, simulatedModel = null)
        RuntimeHistories.clearCache()
    }

    @Test
    fun callsTheHostsToolsAndAnswersFromTheirResults() = runBlocking {
        val session = "scripted"
        appSessionEventLogRoot(context, session).deleteRecursively()
        AppHost.install(tools = { listOf(shelf) }, simulatedModel = { script })
        runtime(session).use { runtime ->
            // put_away acts, so it asks first: the conversation allows all, as a host with no screen does.
            runtime.resumeThread(session, ThreadOptions(approvalPolicy = ApprovalPolicy.APPROVE_ALL))
            val first = runtime.resumeThread(session).run("please put cups away")
            assertEquals(TurnStatus.COMPLETED, first.status)
            assertEquals("Done: cups", first.finalResponse)
            assertEquals(listOf("put_away:{\"item\":\"cups\"}"), shelf.calls)
            // The tool was found in the first turn: the second calls it straight away, twice, then answers.
            val second = runtime.resumeThread(session).run("count them")
            assertEquals("Counted 2 times: {\"items\":1}", second.finalResponse)
            assertEquals("Nothing for hello", runtime.resumeThread(session).run("hello").finalResponse)
        }
    }

    @Test
    fun aToolNoProviderGivesEndsTheTurnSayingSo() = runBlocking {
        val session = "scripted-missing"
        appSessionEventLogRoot(context, session).deleteRecursively()
        AppHost.install(tools = { emptyList() }, simulatedModel = { script })
        val result = runtime(session).use { it.resumeThread(session).run("put cups away") }
        assertTrue(result.finalResponse, result.finalResponse.contains("mcp__shelf__put_away is not among the tools"))
    }

    private fun runtime(session: String): AgentRuntime = buildRuntime(
        context, fixtureProviderConfig(), fixtureProfile(), false, true, appWorkspaceRoot(context),
        appSessionEventLogRoot(context, session), "", null, noopDiagnostics(context), emptyList(), sessionId = session,
    )

    private class Shelf : ToolProvider {
        val calls = mutableListOf<String>()
        override val providerId = "shelf"

        override suspend fun listTools() = listOf(
            ToolDescriptor("put_away", "Puts an item away on the shelf.", """{"type":"object","properties":{"item":{"type":"string"}},"required":["item"]}"""),
            ToolDescriptor("count", "Counts the items on the shelf.", """{"type":"object","properties":{}}""", effectKind = "READ_ONLY"),
        )

        override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
            calls += "$toolName:$argumentsJson"
            val result = if (toolName == "put_away") JSONObject().put("stored", JSONObject(argumentsJson).getString("item")) else JSONObject().put("items", 1)
            return ToolCallResult(success = true, resultJson = result.toString())
        }
    }
}

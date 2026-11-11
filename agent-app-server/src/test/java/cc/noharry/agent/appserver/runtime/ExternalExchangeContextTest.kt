@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.console.*
import cc.noharry.agent.appserver.protocol.SessionTurn
import cc.noharry.agent.appserver.session.SessionStore
import cc.noharry.agent.appserver.session.AppSessionJson
import cc.noharry.agent.appserver.session.SESSIONS_DIR
import cc.noharry.agent.appserver.trajectory.appSessionEventLogRoot
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.model.*
import cc.noharry.agent.vfs.VfsReadResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExternalExchangeContextTest {
    @get:Rule val temporary = TemporaryFolder()

    @After fun resetHost() {
        RuntimeHistories.clearCache()
        AppHost.install(simulatedModel = null, runs = RunAddOns.NONE)
    }

    @Test fun companionAndIdleReminderAreVisibleToBothModelAndActionGate() = runBlocking {
        for (scene in listOf("companion", "plan")) {
            val result = runFollowUp(listOf(turn("external", scene, "Navigate to the next fuel station?")))
            assertTrue(result.modelInput.contains("Navigate to the next fuel station?"))
            assertTrue(result.modelInput.contains("Latest external exchange"))
            assertEquals("Navigate to the next fuel station?", result.previousQuestion)
        }
    }

    @Test fun sdkTurnsAreNotRestatedAndAnOlderExternalOfferDoesNotCrossTheLatestBoundary() = runBlocking {
        val sdk = runFollowUp(listOf(turn("sdk", "app", "SDK question?")))
        assertFalse(sdk.modelInput.contains("Latest external exchange"))
        val laterFailure = runFollowUp(listOf(
            turn("old", "companion", "Old offer?"), turn("new", "app", "Failed now", "FAILED"),
        ))
        assertFalse(laterFailure.modelInput.contains("Old offer?"))
        assertNull(laterFailure.previousQuestion)
    }

    @Test fun oversizedExternalTurnReadsItsCompleteRecordThroughTheProductionWorkspace() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workspace = temporary.newFolder()
        val store = SessionStore(File(workspace, SESSIONS_DIR))
        val session = "external/session with spaces"
        val expected = turn("current", "companion", "Navigate? " + "y".repeat(7_000)).copy(
            sessionId = session, userText = "x".repeat(7_000) + " original request",
        )
        store.appendTurn(turn("old", "companion", "Old offer?").copy(sessionId = session))
        store.appendTurn(expected)
        store.appendTurn(turn("current", "companion", "Other session offer?").copy(sessionId = "other"))
        val previous = requireNotNull(store.prepareRunInput(session, "yes").second)
        val prompt = previous.externalContext(canReadHistory = true)
        assertTrue(prompt.contains("\"turnId\":\"current\""))
        assertTrue(prompt.contains("\"requiresHistoryRead\":true"))
        assertFalse(prompt.contains("x".repeat(100)))
        val data = Json.parseToJsonElement(prompt.lineSequence().elementAt(1)).jsonObject
        val path = data.getValue("historyPath").jsonPrimitive.content
        val read = workspaceVfs(context, workspace, emptyList()).read(path, 0, 32 * 1024) as VfsReadResult.Found
        val record = read.text.lineSequence().mapNotNull(AppSessionJson::decodeTurn).single { it.turnId == previous.turnId }
        assertEquals(expected, record)
        assertFalse(read.text.contains("Other session offer?"))
        // Without read_file the turn still runs, with the start of the request and the end of the reply inline.
        val inline = previous.externalContext(canReadHistory = false)
        assertTrue(inline.toByteArray(Charsets.UTF_8).size < 7 * 1024)
        assertTrue(inline.contains("\"requiresHistoryRead\":false"))
        val shortened = Json.parseToJsonElement(inline.lineSequence().elementAt(1)).jsonObject
        assertTrue(shortened.getValue("assistant").jsonPrimitive.content.endsWith("y".repeat(10)))
        Unit
    }

    private suspend fun runFollowUp(turns: List<SessionTurn>): ObservedContext {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = "external-context-${UUID.randomUUID()}"
        val store = SessionStore(temporary.newFolder())
        turns.forEach { store.appendTurn(it.copy(sessionId = id)) }
        val model = CapturingContextModel()
        var question: String? = null
        AppHost.install(simulatedModel = { model }, runs = object : RunAddOns {
            override fun systemRules(facts: RunFacts): List<String> { question = facts.previousQuestion; return emptyList() }
        })
        val (input, previous) = store.prepareRunInput(id, "yes")
        buildRuntime(context, fixtureProviderConfig(), fixtureProfile(), false, true, appWorkspaceRoot(context),
            appSessionEventLogRoot(context, id), "", null, noopDiagnostics(context), emptyList(), sessionId = id,
            userInput = input, previousExchange = previous).use { runtime -> runtime.resumeThread(id).run(input) }
        return ObservedContext(model.requests.single().messages.joinToString("\n") { it.content }, question)
    }

    private fun turn(id: String, scene: String, reply: String, status: String = "COMPLETED") = SessionTurn(
        id, id, id, "session", scene, "ask", 1, 2, status, reply, emptyList(), completionOutcome = "SUCCEEDED",
    )
}

private data class ObservedContext(val modelInput: String, val previousQuestion: String?)

private class CapturingContextModel : ModelClient {
    override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
    val requests = mutableListOf<ModelRequest>()
    override fun complete(request: ModelRequest) = flowOf(
        ModelEvent.ToolCall("finish", "finish", """{"response":"done","outcome":"success"}"""), ModelEvent.Completed,
    ).also { requests += request }
}

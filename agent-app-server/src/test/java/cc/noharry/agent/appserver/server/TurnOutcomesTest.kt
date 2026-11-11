package cc.noharry.agent.appserver.server

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.ScriptedModel
import cc.noharry.agent.appserver.protocol.ApprovalDecision
import cc.noharry.agent.appserver.protocol.ClientCapabilities
import cc.noharry.agent.appserver.protocol.ClientInfo
import cc.noharry.agent.appserver.protocol.ClientRequests
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.RunOutcome
import cc.noharry.agent.appserver.protocol.ServerNotifications
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.TurnCompleted
import cc.noharry.agent.appserver.protocol.TurnStartParams
import cc.noharry.agent.appserver.protocol.client.ServerClient
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * How a turn ends, through the protocol as a host sees it (the `turn/completed` notification's outcome and what the
 * transcript shows last): an answer the model gave as `partial` or `blocked` is an answer, not a failed run, and one that
 * asks the person ends as a question does; a run that gave no answer, or claimed a success with a required action the
 * person refused, fails. (Its own SDK level: the server's singletons are fresh.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TurnOutcomesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var client: ServerClient

    @Test
    fun aPartialOrBlockedAnswerIsAnAnswerAndRealFailuresStayFailed() {
        start()
        expect("finish success", RunOutcome.COMPLETED, "Done.")
        expect("finish partial", RunOutcome.PARTIAL, PARTIAL)
        expect("finish blocked", RunOutcome.BLOCKED, BLOCKED)
        // A blocked reply that asks the person ends as a question does, its event checks passing.
        expect("finish blocked asking", RunOutcome.COMPLETED, ASKING)
        // The scripted model's search does not find the tool: it says so, blocked (the sample whose tool was off).
        expect("list my habits", RunOutcome.BLOCKED, "mcp__habit_list__list_habits is not among the tools search_tools found")
        // No answer at all: the runtime fails the run.
        expect("finish blank", RunOutcome.FAILED, "INVALID_FINISH_RESPONSE", role = TranscriptRole.ERROR)
        // A required action the person refused, under a claimed success: the runtime fails the run.
        expect("lock the door", RunOutcome.FAILED, "REQUIRED_ACTION_UNRESOLVED", role = TranscriptRole.ERROR)
    }

    /** A turn of its own conversation for [said]: it ends [outcome], the transcript's last word by [role] showing [shown]. */
    private fun expect(said: String, outcome: RunOutcome, shown: String, role: TranscriptRole = TranscriptRole.ASSISTANT) {
        onMain { client.request(ClientRequests.THREAD_START, Empty) }
        val (ended, last) = turn(said)
        assertEquals("outcome of \"$said\"", outcome, ended)
        assertEquals("who spoke last in \"$said\"", role, last?.role)
        assertTrue("last words of \"$said\": ${last?.content}", last?.content.orEmpty().startsWith(shown))
    }

    private fun start() {
        AppServer.install(
            app,
            AppServerConfig(name = "outcomes-test", tools = { listOf(Door) }, simulatedModel = { Model }, simulatedBackend = true),
        )
        client = ServerClient(AppServer.connect(app), ClientInfo("outcomes-test"), ClientCapabilities(approvals = true), scope = scope)
        client.approvals = { ApprovalDecision.DENY }
        onMain { client.connect() }
    }

    private fun turn(said: String): Pair<RunOutcome?, ConversationItem.MessageItem?> = onMain {
        val ended = scope.async(start = CoroutineStart.UNDISPATCHED) {
            client.notifications.first { it.method == ServerNotifications.TURN_COMPLETED.name }
        }
        client.request(ClientRequests.TURN_START, TurnStartParams(said))
        val completed = Json { ignoreUnknownKeys = true }.decodeFromJsonElement(TurnCompleted.serializer(), ended.await().params!!)
        val last = client.state.value.items.filterIsInstance<ConversationItem.MessageItem>()
            .lastOrNull { it.role == TranscriptRole.ERROR || it.role == TranscriptRole.ASSISTANT && it.final }
        completed.result?.outcome to last
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> onMain(block: suspend () -> T): T {
        val running = scope.async { block() }
        val until = System.currentTimeMillis() + 20_000L
        while (!running.isCompleted && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5))
            Thread.sleep(5)
        }
        return running.getCompleted()
    }

    private companion object {
        const val PARTIAL = "Route set to the airport; the stop at the bakery could not be added."
        const val BLOCKED = "The habit list is turned off, so I cannot list your habits."
        const val ASKING = "I cannot reach the car yet. Which car should I use?"
    }

    /** A door whose locking is required once called, and that the person refuses (the client denies every approval). */
    private object Door : ToolProvider {
        override val providerId = "door"
        override suspend fun listTools() = listOf(
            ToolDescriptor("lock", "Locks the door.", """{"type":"object","properties":{}}""", effectKind = "MUTATING", completionImpact = "REQUIRED_ON_CALL"),
        )
        override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext) =
            ToolCallResult(success = true, resultJson = "{}")
    }

    /** Finishes as the person's words say ("finish partial"), or plays its script for the rest. */
    private object Model : ModelClient {
        private val script = ScriptedModel {
            on("list my habits") { call("habit_list", "list_habits"); answer { it.single() } }
            on("lock the door") { call("door", "lock"); answer("Locked.") }
        }

        override fun complete(request: ModelRequest): Flow<ModelEvent> {
            val said = request.messages.last { it.role == "user" && !it.content.startsWith("From the app") }.content
            val (response, outcome) = when {
                "finish success" in said -> "Done." to "success"
                "finish partial" in said -> PARTIAL to "partial"
                "finish blocked asking" in said -> ASKING to "blocked"
                "finish blocked" in said -> BLOCKED to "blocked"
                "finish blank" in said -> "" to "partial"
                else -> return script.complete(request)
            }
            return flow {
                emit(ModelEvent.ToolCall("t-${UUID.randomUUID()}", "finish", buildJsonObject { put("response", response); put("outcome", outcome) }.toString()))
                emit(ModelEvent.Completed)
            }
        }
    }
}

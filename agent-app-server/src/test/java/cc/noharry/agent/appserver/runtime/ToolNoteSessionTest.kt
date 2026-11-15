@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.Approval
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.ThreadItem
import cc.noharry.agent.appserver.console.fixtureProfile
import cc.noharry.agent.appserver.console.fixtureProviderConfig
import cc.noharry.agent.appserver.console.noopDiagnostics
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.session.projectSdkItem
import cc.noharry.agent.appserver.trajectory.appSessionEventLogRoot
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.HookDecision
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.spi.ToolSource
import cc.noharry.agent.spi.ToolUse
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.spi.model.ModelRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
 * The host's note on tool calls ([RunAddOns.toolNote]) in the server's own runs, each turn a runtime of its own as the
 * server builds them: the session keeps the calls as the model wrote them, so a later turn sends them back byte for
 * byte, after a restart too, while the tool, the gates, the approval and the conversation's items read them without it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ToolNoteSessionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val car = Car()
    private val gated = mutableListOf<String>()

    @Before
    fun install() {
        RuntimeHistories.clearCache()
        AppHost.install(
            runs = NoteRuns, tools = { listOf(car) }, hooks = listOf(Gate(gated)), unaskedTools = setOf(SET_CLIMATE),
        )
    }

    @After
    fun reset() {
        AppHost.install(runs = RunAddOns.NONE, tools = { emptyList() }, hooks = emptyList(), unaskedTools = emptySet(), simulatedModel = null)
        RuntimeHistories.clearCache()
    }

    @Test
    fun laterTurnsSendTheCallWithItsNoteAsTheTurnThatMadeItDidAfterARestartToo() {
        val session = session("note-restart")
        val first = Rounds(search("set climate"), callTool("c7", NOTED), finish())
        val events = turn(session, first, "Cool it down.")
        // Within the turn, the request after the call shows the model its call as it wrote it.
        val madeIt = first.requests.last().messages.call("c7")
        assertEquals(NOTED, madeIt.toolArgumentsJson)
        // The tool got its own arguments; the host, the note under the call as it started (as a voice asks for it).
        assertEquals(listOf("set_climate $BARE"), car.calls)
        val started = events.toolStarted(SET_CLIMATE)
        assertEquals(BARE, started.arguments)
        assertEquals(NOTE, ToolNotes.of(started.tool, started.arguments.orEmpty()))

        val next = Rounds(finish())
        turn(session, next, "Thanks.")
        // The process ends: the conversation's history and the tools it found are gone from memory, its log is not.
        RuntimeHistories.clearCache()
        forgetSessionTools(session)
        val restarted = Rounds(finish())
        turn(session, restarted, "And now?")

        assertEquals(madeIt, next.requests.single().messages.call("c7"))
        assertEquals(madeIt, restarted.requests.single().messages.call("c7"))
        assertTrue(restarted.requests.single().messages.containsAll(first.requests.last().messages.calls()))
    }

    @Test
    fun theGatesTheApprovalAndTheConversationsItemReadTheCallWithoutTheNote() {
        val session = session("note-approval")
        val model = Rounds(search("unlock car"), callTool("c1", UNLOCK_NOTED), finish())
        val approvals = mutableListOf<Approval>()
        val events = turn(session, model, "Unlock the car.") { approvals += it }

        assertEquals(UNLOCK_BARE, approvals.single().arguments)
        assertTrue(gated.contains(UNLOCK_BARE) && gated.none { "say" in it })
        assertEquals(listOf("unlock $UNLOCK_BARE"), car.calls)
        val started = events.toolStarted(UNLOCK)
        val item = projectSdkItem(started, 1, ConfirmationMode.ASK_EVERY_TIME, null) as ConversationItem.ToolCallItem
        assertEquals(UNLOCK_BARE, item.argumentsJson)
        // Asked and approved, the call is still kept as the model wrote it.
        assertEquals(UNLOCK_NOTED, model.requests.last().messages.call("c1").toolArgumentsJson)
    }

    @Test
    fun aNoteNeverLandsOnAnotherConversationsCallWithTheSameId() {
        val noted = session("note-ids-noted")
        val plain = session("note-ids-plain")
        val notedCall = setClimate(21, "Making it cooler.")
        val plainCall = setClimate(21, note = null)
        turn(noted, Rounds(search("set climate"), callTool("call:0", notedCall), finish()), "Cool it down.")
        // A service that numbers its calls from zero in every conversation: the same id, tool and arguments, no note.
        turn(plain, Rounds(search("set climate"), callTool("call:0", plainCall), finish()), "Cool it down.")
        val nextPlain = Rounds(finish())
        val nextNoted = Rounds(finish())
        turn(plain, nextPlain, "Thanks.")
        turn(noted, nextNoted, "Thanks.")

        assertEquals(plainCall, nextPlain.requests.single().messages.call("call:0").toolArgumentsJson)
        assertEquals(notedCall, nextNoted.requests.single().messages.call("call:0").toolArgumentsJson)
    }

    @Test
    fun aToolReachedThroughCallToolWithAPropertyOfTheNotesNameGetsItAsItsInput() {
        val session = session("note-owned")
        val model = Rounds(search("announce"), callTool("c1", ANNOUNCE), finish())
        turn(session, model, "Say hi to the driver.")

        // Its schema requires the property and refuses any other: the call ran with it.
        assertEquals(listOf("announce $ANNOUNCE_ARGUMENTS"), car.calls)
        assertNull(ToolNotes.of(ANNOUNCE_TOOL, ANNOUNCE_ARGUMENTS))
        assertEquals(ANNOUNCE, model.requests.last().messages.call("c1").toolArgumentsJson)
    }

    private fun session(id: String): String = id.also { appSessionEventLogRoot(context, it).deleteRecursively() }

    /** One turn of [session] with [model], approving each approval after [onApproval] reads it: its events. */
    private fun turn(session: String, model: ModelClient, input: String, onApproval: (Approval) -> Unit = {}): List<ThreadEvent> =
        runBlocking {
            AppHost.install(simulatedModel = { model })
            buildRuntime(
                context, fixtureProviderConfig(), fixtureProfile(), false, true, appWorkspaceRoot(context),
                appSessionEventLogRoot(context, session), "", null, noopDiagnostics(context), emptyList(), sessionId = session,
            ).use { runtime ->
                val events = mutableListOf<ThreadEvent>()
                runtime.resumeThread(session).runStreamed(input).collect { event ->
                    events += event
                    ((event as? ThreadEvent.ItemStarted)?.item as? Approval)?.let { onApproval(it); it.approve() }
                }
                events
            }
        }

    private fun List<ThreadEvent>.toolStarted(tool: String): ThreadItem.ToolCall =
        mapNotNull { (it as? ThreadEvent.ItemStarted)?.item as? ThreadItem.ToolCall }.single { it.tool == tool }

    private fun List<ModelMessage>.call(id: String): ModelMessage = single { it.role == "assistant" && it.toolCallId == id }

    private fun List<ModelMessage>.calls(): List<ModelMessage> = filter { it.toolCallId != null }

    /** Answers each request with the next of [rounds], then finishes; keeps the requests as the model got them. */
    private class Rounds(vararg rounds: ModelEvent.ToolCall) : ModelClient {
        private val left = ArrayDeque(rounds.toList())
        val requests = mutableListOf<ModelRequest>()

        override fun complete(request: ModelRequest): Flow<ModelEvent> {
            requests += request
            return flowOf(left.removeFirstOrNull() ?: finish(), ModelEvent.Completed)
        }
    }

    private object NoteRuns : RunAddOns {
        override fun toolNote(): ToolNote = ToolNote("say", "One sentence for the person.") { it != "finish" }
    }

    /** Notes the arguments of every backend call, as the server's gate on the tools that act reads them. */
    private class Gate(private val seen: MutableList<String>) : RuntimeHook {
        override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
            if (call.source == ToolSource.BACKEND) synchronized(seen) { seen += call.argumentsJson }
            return HookDecision.Allow()
        }
    }

    /** Its schemas refuse arguments they do not declare; unlock acts, so it asks first. */
    private class Car : ToolProvider {
        val calls = mutableListOf<String>()
        override val providerId = "car"

        override suspend fun listTools() = listOf(
            ToolDescriptor("set_climate", "Sets the cabin temperature.", schema("temperature", "number")),
            ToolDescriptor("unlock", "Unlocks the car's doors.", schema("door", "string")),
            ToolDescriptor("announce", "Announces a line in the cabin.", schema("say", "string")),
        )

        override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
            synchronized(calls) { calls += "$toolName $argumentsJson" }
            return ToolCallResult(success = true, resultJson = """{"ok":true}""")
        }

        private fun schema(name: String, type: String) =
            """{"type":"object","properties":{"$name":{"type":"$type"}},"required":["$name"],"additionalProperties":false}"""
    }

    private companion object {
        const val SET_CLIMATE = "mcp__car__set_climate"
        const val UNLOCK = "mcp__car__unlock"
        const val ANNOUNCE_TOOL = "mcp__car__announce"
        const val NOTE = "Cooling it down."
        const val BARE = """{"temperature":22}"""
        const val NOTED = """{"name":"$SET_CLIMATE","arguments":$BARE,"say":"$NOTE"}"""
        const val UNLOCK_BARE = """{"door":"driver"}"""
        const val UNLOCK_NOTED = """{"name":"$UNLOCK","arguments":{"door":"driver","say":"Unlocking your door."}}"""
        const val ANNOUNCE_ARGUMENTS = """{"say":"Hi"}"""
        const val ANNOUNCE = """{"name":"$ANNOUNCE_TOOL","arguments":$ANNOUNCE_ARGUMENTS}"""

        /** A call_tool call of set_climate with [temperature] and, unless null, [note]. */
        fun setClimate(temperature: Int, note: String?): String {
            val say = note?.let { ",\"say\":\"$it\"" }.orEmpty()
            return """{"name":"$SET_CLIMATE","arguments":{"temperature":$temperature}$say}"""
        }

        var calls = 0
        fun search(query: String) = ModelEvent.ToolCall("s${++calls}", "search_tools", """{"query":"$query"}""")
        fun callTool(id: String, arguments: String) = ModelEvent.ToolCall(id, "call_tool", arguments)
        fun finish() = ModelEvent.ToolCall("f${++calls}", "finish", """{"response":"Done.","outcome":"success"}""")
    }
}

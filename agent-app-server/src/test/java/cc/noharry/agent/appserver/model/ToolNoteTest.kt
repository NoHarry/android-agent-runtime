@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.model

import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.RunAddOns
import cc.noharry.agent.appserver.runtime.ToolNote
import cc.noharry.agent.appserver.runtime.ToolNotes
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.spi.model.ModelTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The host's note field on tool calls: the model reads and fills it, the session keeps the calls as the model wrote
 * them, no tool receives it ([AppModelClient.forTool]) and the host gets what was written. Each test writes notes of
 * its own, as [ToolNotes] is kept for the process.
 */
class ToolNoteTest {
    private val note = ToolNote("say", "One sentence for the user.") { it != "finish" }

    @Before fun install() = AppHost.install(runs = NoteRuns(note))

    @After fun reset() = AppHost.install(runs = RunAddOns.NONE)

    @Test fun theModelReadsTheFieldOnTheToolsThatTakeItAndItsCallReachesTheRuntimeAsWritten() = runBlocking {
        val written = ModelEvent.ToolCall("c1", "set_climate", """{"say":"Warming it up.","temperature":24}""")
        val model = RecordingModel(written)
        val tools = listOf(ModelTool("set_climate", OBJECT_SCHEMA), ModelTool("finish", OBJECT_SCHEMA), ModelTool("odd", "[]"))

        val call = AppModelClient(model).complete(ModelRequest(emptyList(), tools)).toList().filterIsInstance<ModelEvent.ToolCall>().single()

        val read = model.requests.single().tools.associate { it.name to it.schemaJson }
        assertTrue(read.getValue("set_climate").contains("\"say\"") && read.getValue("set_climate").contains("temperature"))
        assertFalse(read.getValue("finish").contains("\"say\""))
        assertEquals("[]", read.getValue("odd"))
        // The runtime records it so and takes the note off at the tool boundary.
        assertEquals(written, call)
    }

    @Test fun theToolGetsTheCallWithoutTheNoteAndTheHostGetsTheNote() {
        val call = ModelEvent.ToolCall("c1", "set_climate", """{"say":"Heat coming up.","temperature":24}""")

        assertEquals("""{"temperature":24}""", client().forTool(call, call, OBJECT_SCHEMA))

        // The host asks by the call as the runtime then starts it, whatever the order of its keys.
        assertEquals("Heat coming up.", ToolNotes.of("set_climate", """{ "temperature": 24 }"""))
        assertNull(ToolNotes.of("set_climate", """{"temperature":25}"""))
    }

    @Test fun aNoteOnACallToolCallOrInsideTheArgumentsItPassesGoesToTheHostUnderTheToolItNames() {
        val client = client()
        val outside = ModelEvent.ToolCall("c1", "call_tool", """{"name":"mcp__car__set_seat","arguments":{"level":2},"say":"Heating your seat."}""")
        val inside = ModelEvent.ToolCall("c2", "call_tool", """{"name":"mcp__car__set_fan","arguments":{"speed":3,"say":"Fan up."}}""")
        val seat = ModelEvent.ToolCall("c1", "mcp__car__set_seat", """{"level":2}""")
        val fan = ModelEvent.ToolCall("c2", "mcp__car__set_fan", """{"speed":3,"say":"Fan up."}""")

        assertEquals("""{"level":2}""", client.forTool(seat, outside, schema("level")))
        assertEquals("""{"speed":3}""", client.forTool(fan, inside, schema("speed")))

        assertEquals("Heating your seat.", ToolNotes.of("mcp__car__set_seat", """{"level":2}"""))
        assertEquals("Fan up.", ToolNotes.of("mcp__car__set_fan", """{"speed":3}"""))
    }

    @Test fun aToolWhoseOwnSchemaHasTheFieldKeepsItAsItsInput() {
        val client = client()
        val speak = ModelEvent.ToolCall("c1", "speak", """{"say":"Hello there."}""")
        // Reached through call_tool, as the runtime then gives the schema of the tool it names.
        val announce = ModelEvent.ToolCall("c2", "mcp__car__announce", """{"say":"Hi"}""")
        val wrapper = ModelEvent.ToolCall("c2", "call_tool", """{"name":"mcp__car__announce","arguments":{"say":"Hi"},"say":"Announcing."}""")

        assertEquals(speak.argumentsJson, client.forTool(speak, speak, schema("say")))
        assertEquals(announce.argumentsJson, client.forTool(announce, wrapper, schema("say")))

        assertNull(ToolNotes.of("speak", speak.argumentsJson))
        // What the model wrote on the call_tool call is still the note.
        assertEquals("Announcing.", ToolNotes.of("mcp__car__announce", announce.argumentsJson))
    }

    @Test fun aListedToolThatHasAPropertyOfTheNotesNameGetsItAsItsInput() = runBlocking {
        val speaker = Speaker()
        val model = RecordingModel(
            ModelEvent.ToolCall("s1", "search_tools", """{"query":"announce"}"""),
            ModelEvent.ToolCall("c1", "mcp__speaker__announce", """{"say":"Doors closing."}"""),
            ModelEvent.ToolCall("f1", "finish", """{"response":"Done.","outcome":"success"}"""),
            oneARound = true,
        )
        val client = AppModelClient(model)

        AgentRuntimeBuilder.create().model(client).toolArguments(client).tools(speaker).build().use { it.startThread().run("Say it.") }

        assertEquals(listOf("""{"say":"Doors closing."}"""), speaker.calls)
        // The model read the tool's own property, not the note's.
        assertFalse(model.requests.last().tools.single { it.name == "mcp__speaker__announce" }.schemaJson.contains(note.description))
        assertNull(ToolNotes.of("mcp__speaker__announce", """{"say":"Doors closing."}"""))
    }

    @Test fun aFieldThatHoldsNoWordsIsTakenOffAllTheSame() {
        val call = ModelEvent.ToolCall("c1", "set_climate", """{"temperature":19,"say":7}""")

        assertEquals("""{"temperature":19}""", client().forTool(call, call, OBJECT_SCHEMA))
        assertNull(ToolNotes.of("set_climate", """{"temperature":19}"""))
    }

    @Test fun theCallsAreReadWithTheNoteTheirRequestOffered() {
        val offered = client()
        AppHost.install(runs = RunAddOns.NONE)
        val notOffered = client()
        val call = ModelEvent.ToolCall("c1", "set_climate", """{"temperature":17,"say":"Colder now."}""")

        // The host turns the note on and off while the models answer.
        AppHost.install(runs = NoteRuns(note))
        assertEquals(call.argumentsJson, notOffered.forTool(call, call, OBJECT_SCHEMA))
        AppHost.install(runs = RunAddOns.NONE)
        assertEquals("""{"temperature":17}""", offered.forTool(call, call, OBJECT_SCHEMA))
        assertEquals("Colder now.", ToolNotes.of("set_climate", """{"temperature":17}"""))
    }

    @Test fun requestsSendTheModelsEarlierCallsAsTheSessionLoggedThem() = runBlocking {
        val model = RecordingModel()
        val history = listOf(
            ModelMessage("assistant", "", toolCallId = "c1", toolName = "set_climate", toolArgumentsJson = """{"temperature":24,"say":"Warming it up."}"""),
            ModelMessage("tool", "{}", toolCallId = "c1", toolName = "set_climate"),
            ModelMessage("assistant", "", toolCallId = "c0", toolName = "set_climate", toolArgumentsJson = """{"temperature":20}"""),
        )

        AppModelClient(model).complete(ModelRequest(history)).toList()

        assertEquals(history, model.requests.single().messages)
    }

    @Test fun aHostWithoutANoteLeavesRequestsAndCallsAsTheyAre() = runBlocking {
        AppHost.install(runs = RunAddOns.NONE)
        val written = ModelEvent.ToolCall("c1", "set_climate", """{"say":"x","temperature":24}""")
        val model = RecordingModel(written)
        // A session logged with a note before the host turned it off is sent as logged.
        val logged = listOf(ModelMessage("assistant", "", toolCallId = "c0", toolName = "set_climate", toolArgumentsJson = """{"say":"y","temperature":21}"""))
        val request = ModelRequest(logged, listOf(ModelTool("set_climate", OBJECT_SCHEMA)))
        val client = AppModelClient(model)

        val call = client.complete(request).toList().filterIsInstance<ModelEvent.ToolCall>().single()

        assertEquals(request, model.requests.single())
        assertEquals(written, call)
        assertEquals(written.argumentsJson, client.forTool(call, call, OBJECT_SCHEMA))
    }

    /** A client that has sent a request with the installed host's note, as one has before its model writes calls. */
    private fun client(): AppModelClient =
        AppModelClient(RecordingModel()).also { runBlocking { it.complete(ModelRequest(emptyList())).toList() } }

    private class NoteRuns(private val note: ToolNote) : RunAddOns {
        override fun toolNote(): ToolNote = note
    }

    /** Answers with [events], all in each round, or with one of them a round when [oneARound]. */
    private class RecordingModel(private vararg val events: ModelEvent, private val oneARound: Boolean = false) : ModelClient {
        val requests = mutableListOf<ModelRequest>()
        override fun complete(request: ModelRequest): Flow<ModelEvent> {
            requests += request
            val round = if (oneARound) listOfNotNull(events.getOrNull(requests.size - 1)) else events.toList()
            return flowOf(*round.toTypedArray(), ModelEvent.Completed)
        }
    }

    /** A tool whose own input is a property named like the note. */
    private class Speaker : ToolProvider {
        val calls = mutableListOf<String>()
        override val providerId = "speaker"

        override suspend fun listTools() = listOf(ToolDescriptor("announce", "Says a line in the cabin.", schema("say")))

        override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
            synchronized(calls) { calls += argumentsJson }
            return ToolCallResult(success = true, resultJson = """{"ok":true}""")
        }
    }

    private companion object {
        const val OBJECT_SCHEMA = """{"type":"object","properties":{"temperature":{"type":"number"}}}"""

        /** A schema whose only, required property is [name], refusing any other. */
        fun schema(name: String) =
            """{"type":"object","properties":{"$name":{"type":"string"}},"required":["$name"],"additionalProperties":false}"""
    }
}

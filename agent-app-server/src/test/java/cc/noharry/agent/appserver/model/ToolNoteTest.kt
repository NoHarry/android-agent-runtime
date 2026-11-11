@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.model

import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.RunAddOns
import cc.noharry.agent.appserver.runtime.ToolNote
import cc.noharry.agent.appserver.runtime.ToolNotes
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

/** The host's note field on tool calls: the model reads and fills it, no tool receives it, the host gets what was written. */
class ToolNoteTest {
    private val note = ToolNote("say", "One sentence for the user.") { it != "finish" }

    @Before fun install() = AppHost.install(runs = object : RunAddOns {
        override fun toolNote(): ToolNote = note
    })

    @After fun reset() = AppHost.install(runs = RunAddOns.NONE)

    @Test fun theModelReadsTheFieldOnTheToolsThatTakeItAndNoToolReceivesIt() = runBlocking {
        val model = RecordingModel(ModelEvent.ToolCall("c1", "set_climate", """{"say":"Warming it up.","temperature":24}"""))
        val client = AppModelClient(model)
        val tools = listOf(ModelTool("set_climate", OBJECT_SCHEMA), ModelTool("finish", OBJECT_SCHEMA), ModelTool("odd", "[]"))

        val call = client.complete(ModelRequest(emptyList(), tools)).toList().filterIsInstance<ModelEvent.ToolCall>().single()

        val read = model.requests.single().tools.associate { it.name to it.schemaJson }
        assertTrue(read.getValue("set_climate").contains("\"say\"") && read.getValue("set_climate").contains("temperature"))
        assertFalse(read.getValue("finish").contains("\"say\""))
        assertEquals("[]", read.getValue("odd"))
        assertEquals("""{"temperature":24}""", call.argumentsJson)
        // The host asks by the call as the runtime then starts it, whatever the order of its keys.
        assertEquals("Warming it up.", ToolNotes.of("set_climate", """{ "temperature": 24 }"""))
        assertNull(ToolNotes.of("set_climate", """{"temperature":25}"""))
    }

    @Test fun laterRequestsShowTheModelItsCallWithTheNoteItWrote() = runBlocking {
        val model = RecordingModel(ModelEvent.ToolCall("c1", "set_climate", """{"temperature":24,"say":"Warming it up."}"""))
        val client = AppModelClient(model)
        client.complete(ModelRequest(emptyList(), listOf(ModelTool("set_climate", OBJECT_SCHEMA)))).toList()
        val history = listOf(
            ModelMessage("assistant", "", toolCallId = "c1", toolName = "set_climate", toolArgumentsJson = """{"temperature":24}"""),
            ModelMessage("tool", "{}", toolCallId = "c1", toolName = "set_climate"),
            ModelMessage("assistant", "", toolCallId = "c0", toolName = "set_climate", toolArgumentsJson = """{"temperature":20}"""),
        )

        client.complete(ModelRequest(history)).toList()

        val sent = model.requests.last().messages
        assertTrue(sent[0].toolArgumentsJson!!.contains("Warming it up."))
        assertEquals(history.drop(1), sent.drop(1))
    }

    @Test fun aHostWithoutANoteLeavesRequestsAndCallsAsTheyAre() = runBlocking {
        AppHost.install(runs = RunAddOns.NONE)
        val model = RecordingModel(ModelEvent.ToolCall("c1", "set_climate", """{"say":"x","temperature":24}"""))
        val request = ModelRequest(emptyList(), listOf(ModelTool("set_climate", OBJECT_SCHEMA)))

        val call = AppModelClient(model).complete(request).toList().filterIsInstance<ModelEvent.ToolCall>().single()

        assertEquals(request, model.requests.single())
        assertEquals("""{"say":"x","temperature":24}""", call.argumentsJson)
    }

    private class RecordingModel(private vararg val events: ModelEvent) : ModelClient {
        val requests = mutableListOf<ModelRequest>()
        override fun complete(request: ModelRequest): Flow<ModelEvent> {
            requests += request
            return flowOf(*events, ModelEvent.Completed)
        }
    }

    private companion object {
        const val OBJECT_SCHEMA = """{"type":"object","properties":{"temperature":{"type":"number"}}}"""
    }
}

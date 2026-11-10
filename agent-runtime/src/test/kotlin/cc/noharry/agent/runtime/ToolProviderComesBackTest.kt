@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.TurnResult
import cc.noharry.agent.TurnStatus
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A conversation found a tool; a turn runs without its provider (switched off), then one with it again. The turn
 * without it is refused in words that say so, and the turn with it calls the tool without searching, also when the
 * process restarted between turns. Each turn is a runtime of its own, sharing storage, as an app builds them; another
 * provider stays mounted throughout, unless a test says otherwise.
 */
class ToolProviderComesBackTest {
    @Test fun searchModeCallsTheToolAgainOnceItsProviderIsBack() { offAndOn(BackendTools.SEARCH, restarts = false) }

    @Test fun searchModeKeepsTheToolAcrossProcessRestarts() { offAndOn(BackendTools.SEARCH, restarts = true) }

    @Test fun directModePutsTheToolBackWhereItWasInTheToolList() {
        val requests = offAndOn(BackendTools.DIRECT, restarts = false)
        val before = requests.last { it.input == FIRST }.tools
        val after = requests.first { it.input == THIRD }.tools
        assertTrue(after.any { it.name == COUNT })
        assertEquals(before, after)
    }

    @Test fun nativeDeferredModeToo() { offAndOn(BackendTools.NATIVE_DEFERRED, restarts = false) }

    @Test fun nativeDeferredModeAcrossProcessRestarts() { offAndOn(BackendTools.NATIVE_DEFERRED, restarts = true) }

    @Test fun withNoProviderLeftAtAllTheToolIsUnavailableToo() {
        for (mode in BackendTools.entries) for (restarts in listOf(false, true)) {
            if (mode == BackendTools.DIRECT && restarts) continue
            offAndOn(mode, restarts, otherProvider = false)
        }
    }

    @Test fun searchModeRequestsEachStartWithThePreviousOne() {
        for (restarts in listOf(false, true)) {
            val requests = offAndOn(BackendTools.SEARCH, restarts).map { it.request }
            requests.zipWithNext().forEachIndexed { index, (before, after) ->
                assertEquals("request ${index + 2}: tools", before.tools, after.tools)
                assertEquals("request ${index + 2}: messages", before.messages, after.messages.take(before.messages.size))
            }
        }
    }

    /** Runs the three turns and checks what each answered; the model's requests, each with the turn's input. */
    private fun offAndOn(mode: BackendTools, restarts: Boolean, otherProvider: Boolean = true): List<SeenRequest> = runBlocking {
        val root = threadRoot()
        val model = ShelfModel(viaCallTool = mode == BackendTools.SEARCH)
        val shelf = Shelf()
        val sameProcess = SessionToolActivations()
        suspend fun turn(input: String, withShelf: Boolean): TurnResult =
            turn(root, model, mode, if (restarts) SessionToolActivations() else sameProcess, input,
                listOfNotNull(shelf.provider.takeIf { withShelf }, lamp.takeIf { otherProvider }))

        assertEquals(COUNTED, turn(FIRST, withShelf = true).finalResponse)
        val refused = Json.parseToJsonElement(turn(SECOND, withShelf = false).finalResponse).jsonObject
        assertEquals("TOOL_UNAVAILABLE", refused.getValue("code").jsonPrimitive.content)
        assertEquals(COUNT, refused.getValue("tool").jsonPrimitive.content)
        assertEquals(UNAVAILABLE_MESSAGE, refused.getValue("message").jsonPrimitive.content)
        assertEquals(COUNTED, turn(THIRD, withShelf = true).finalResponse)
        assertEquals("found once, in the first turn", 1, model.searches)
        assertEquals(2, shelf.calls)
        model.seen
    }

    private suspend fun turn(
        root: File, model: ShelfModel, mode: BackendTools, activations: SessionToolActivations, input: String,
        providers: List<ToolProvider>,
    ): TurnResult {
        model.input = input
        val builder = threadBuilder(model, root).settings(LoopSettings(backendTools = mode)).sessionToolActivations(activations)
        return builder.tools(providers).build().use { runtime ->
            runtime.resumeThread(THREAD).run(input).also { assertEquals(TurnStatus.COMPLETED, it.status) }
        }
    }

    /** Counts the shelf: searches for the tool unless the conversation found it before, then answers with what it said. */
    private class ShelfModel(private val viaCallTool: Boolean) : ModelClient {
        override val providerConcurrency = ProviderConcurrencyDeclaration.threadSafe()
        val seen = mutableListOf<SeenRequest>()
        var searches = 0
        @Volatile var input = ""

        override fun complete(request: ModelRequest): Flow<ModelEvent> {
            seen += SeenRequest(input, request.copy(messages = request.messages.toList(), tools = request.tools.toList()))
            val last = request.messages.last()
            val id = "call-${seen.size}"
            val call = when {
                last.role == "tool" && last.toolName != "search_tools" ->
                    ModelEvent.ToolCall(id, "finish", buildJsonObject { put("response", last.content) }.toString())
                request.messages.any { it.role == "tool" && it.toolName == "search_tools" } -> count(id)
                else -> ModelEvent.ToolCall(id, "search_tools", """{"query":"count the shelf"}""").also { searches++ }
            }
            return flowOf(call, ModelEvent.Completed)
        }

        private fun count(id: String) =
            if (viaCallTool) ModelEvent.ToolCall(id, "call_tool", """{"name":"$COUNT","arguments":{}}""")
            else ModelEvent.ToolCall(id, COUNT, "{}")
    }

    private val lamp = functionTools("lamp") { tool("toggle", "Turns the reading lamp on or off.") { """{"on":true}""" } }

    private class Shelf {
        var calls = 0
        val provider = functionTools("shelf") {
            tool("count", "Counts the items on the shelf.", readOnly = true) { calls++; COUNTED }
        }
    }

    data class SeenRequest(val input: String, val request: ModelRequest) {
        val tools get() = request.tools
    }

    private companion object {
        const val THREAD = "shelf-talk"
        const val COUNT = "mcp__shelf__count"
        const val COUNTED = """{"items":3}"""
        const val FIRST = "How many items are on the shelf?"
        const val SECOND = "And now?"
        const val THIRD = "And now, again?"
        const val UNAVAILABLE_MESSAGE = "This tool is not available now: the tool server or plugin that provides it is " +
            "switched off, cannot be reached, or was removed. Searching for it or calling it again will not help until " +
            "it is back; tell the person."
    }
}

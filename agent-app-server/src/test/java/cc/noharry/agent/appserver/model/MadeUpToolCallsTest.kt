@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.model

import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.spi.model.ModelTool
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MadeUpToolCallsTest {
    private val request = ModelRequest(
        messages = listOf(
            ModelMessage("system", "Tool domains (use search_tools to discover specific tools by keyword):\n- home (7 tools): 开关房间的空调"),
            ModelMessage("user", "把客厅温度调到25度"),
            ModelMessage("user", "From the app, not typed by the user: context for the request above.\n现在：2026-10-31 周六 16:32"),
            ModelMessage(
                "user",
                "From the app, not typed by the user: tools ready for this request. They are not in your tool list; " +
                    "call them with call_tool(name, arguments).\n- mcp__cabin__set_climate: 开关空调",
            ),
        ),
        tools = listOf("search_tools", "call_tool", "finish", "update_todo").map { ModelTool(it, "{}") },
    )

    @Test
    fun gemmasMadeUpToolGoesOnAsASearchForWhatTheUserAsked() = runBlocking {
        // The call Gemma 4 E4B made on the tablet (2026-10-31) after its spoken line, before it had searched.
        val madeUp = ModelEvent.ToolCall("c1", "home__set_temperature", """{"room":"客厅","temperature":25}""")
        val spoken = ModelEvent.TextDelta("好的，马上为您把客厅温度调到25度。\n")

        val events = run(spoken, madeUp, ModelEvent.Completed)

        assertEquals(
            listOf(
                spoken,
                ModelEvent.ToolCall("c1", "search_tools", """{"query":"把客厅温度调到25度 home set temperature"}"""),
                ModelEvent.Completed,
            ),
            events,
        )
    }

    @Test
    fun offeredToolsAndBackendToolsTheRequestNamesPassUnchanged() = runBlocking {
        val calls = listOf(
            ModelEvent.ToolCall("a", "call_tool", """{"name":"mcp__home__set_climate","arguments":{}}"""),
            ModelEvent.ToolCall("b", "mcp__cabin__set_climate", """{"temperature":22}"""),
            ModelEvent.ToolCall("c", "web_search", """{"query":"天气"}"""),
        )

        assertEquals(calls, run(*calls.toTypedArray()))
    }

    @Test
    fun aBackendToolCalledEarlierInTheSessionPasses() = runBlocking {
        val earlier = request.copy(
            messages = request.messages + ModelMessage("assistant", "", toolCallId = "x", toolName = "mcp__home__get_home_status"),
        )
        val call = ModelEvent.ToolCall("d", "mcp__home__get_home_status", "{}")

        assertEquals(listOf(call), listOf(call).asFlow().withMadeUpCallsSearched(earlier).toList())
    }

    @Test
    fun oneSearchPerResponse() = runBlocking {
        val events = run(
            ModelEvent.ToolCall("e", "home__set_temperature", "{}"),
            ModelEvent.ToolCall("f", "home__get_status", "{}"),
            ModelEvent.Completed,
        )

        assertEquals(listOf("search_tools"), events.filterIsInstance<ModelEvent.ToolCall>().map { it.name })
    }

    @Test
    fun withoutSearchToolsTheCallStaysForTheRuntimeToRefuse() = runBlocking {
        val call = ModelEvent.ToolCall("g", "home__set_temperature", "{}")

        assertEquals(listOf(call), listOf(call).asFlow().withMadeUpCallsSearched(request.copy(tools = emptyList())).toList())
    }

    private suspend fun run(vararg events: ModelEvent): List<ModelEvent> = events.asList().asFlow().withMadeUpCallsSearched(request).toList()
}

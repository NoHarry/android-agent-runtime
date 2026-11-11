@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.model

import cc.noharry.agent.spi.model.ModelEvent
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextToolCallsTest {
    private val tools = setOf("finish", "ask", "call_tool", "search_tools")

    @Test
    fun gemmasWrittenFinishGoesOnAsTheFinishCall() = runBlocking {
        // The deltas Gemma 4 E4B streamed on the tablet (2026-10-31), so the reply was read out as 「finish」.
        val deltas = listOf(
            "finish", "(", "response", "=\"", "客厅", "的", "温度", "已经", "设置", "到", "2", "5", "度", "了", "，",
            "空调", "正在", "运行", "中", "。", "\",", " outcome", "=\"", "success", "\")",
        )

        val events = run(deltas.map(ModelEvent::TextDelta) + ModelEvent.Completed)

        assertEquals(
            listOf(
                ModelEvent.ToolCall("id-1", "finish", """{"response":"客厅的温度已经设置到25度了，空调正在运行中。","outcome":"success"}"""),
                ModelEvent.Completed,
            ),
            events,
        )
    }

    @Test
    fun aSpokenLineBeforeTheWrittenCallStillStreams() = runBlocking {
        val events = run(listOf(ModelEvent.TextDelta("我把温度调好了。\n"), ModelEvent.TextDelta("finish(response='好了。')"), ModelEvent.Completed))

        assertEquals(
            listOf(
                ModelEvent.TextDelta("我把温度调好了。\n"),
                ModelEvent.ToolCall("id-1", "finish", """{"response":"好了。"}"""),
                ModelEvent.Completed,
            ),
            events,
        )
    }

    @Test
    fun aCallWrittenStraightAfterTheSpokenLineGoesOnAsTheCall() = runBlocking {
        // As Gemma 4 E4B wrote it on the tablet (2026-10-31): no line break between its spoken line and the call.
        val deltas = listOf(
            "我已经", "把空调", "调到", "25度了。", "finish", "(", "response", "=\"", "已将车内空调温度设置为25摄氏度。",
            "\",", " outcome", "=\"", "success", "\")",
        )

        val events = run(deltas.map(ModelEvent::TextDelta) + ModelEvent.Completed)

        assertEquals("我已经把空调调到25度了。", events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(
            listOf(
                ModelEvent.ToolCall("id-1", "finish", """{"response":"已将车内空调温度设置为25摄氏度。","outcome":"success"}"""),
                ModelEvent.Completed,
            ),
            events.filterNot { it is ModelEvent.TextDelta },
        )
    }

    @Test
    fun aNameInsideAWordOrProseAboutACallStaysText() = runBlocking {
        for (text in listOf("okfinish(response=\"x\")", "调用 finish(response) 来结束。")) {
            val events = run(listOf(ModelEvent.TextDelta(text), ModelEvent.Completed))

            assertEquals(text, events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
            assertEquals(ModelEvent.Completed, events.last())
        }
    }

    @Test
    fun replyTextPassesUnchanged() = runBlocking {
        val deltas = listOf("fi", "ne，", "空调调好了。\n", "ask ", "me later", "\nsearch")

        val events = run(deltas.map(ModelEvent::TextDelta) + ModelEvent.Completed)

        assertEquals(deltas.joinToString(""), events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(ModelEvent.Completed, events.last())
    }

    @Test
    fun aWrittenCallThatDoesNotParseStaysText() = runBlocking {
        val events = run(listOf(ModelEvent.TextDelta("finish(response=\"没写完"), ModelEvent.Completed))

        assertEquals(listOf(ModelEvent.TextDelta("finish(response=\"没写完"), ModelEvent.Completed), events)
    }

    @Test
    fun realToolCallsAndUsagePassThrough() = runBlocking {
        val call = ModelEvent.ToolCall("x", "search_tools", """{"query":"空调"}""")
        val usage = ModelEvent.UsageDelta(inputTokens = 10)

        assertEquals(listOf(call, usage, ModelEvent.Completed), run(listOf(call, usage, ModelEvent.Completed)))
    }

    @Test
    fun literalsBecomeJson() {
        val calls = parseWrittenCalls(
            """call_tool(name="mcp__home__set_climate", arguments={"temperature": 25, 'mode': 'heat', "on": True, "fan": None, "zones": [1, 2.5]})""" +
                "\nask(question='哪个房间？')",
            tools,
        )!!

        assertEquals(listOf("call_tool", "ask"), calls.map { it.first })
        assertEquals(
            Json.parseToJsonElement("""{"name":"mcp__home__set_climate","arguments":{"temperature":25,"mode":"heat","on":true,"fan":null,"zones":[1,2.5]}}"""),
            calls[0].second,
        )
        assertEquals(JsonObject(emptyMap()), parseWrittenCalls("finish()", tools)!!.single().second)
    }

    @Test
    fun onlyOfferedToolsWithKeywordArgumentsCount() {
        assertNull(parseWrittenCalls("""set_climate(temperature=25)""", tools))
        assertNull(parseWrittenCalls("""finish("好了")""", tools))
        assertNull(parseWrittenCalls("""finish(response="好了") 然后呢""", tools))
    }

    private suspend fun run(events: List<ModelEvent>): List<ModelEvent> {
        var next = 0
        return events.asFlow().withTextToolCalls(tools) { "id-${++next}" }.toList()
    }
}

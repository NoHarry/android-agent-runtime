@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.AgentEvent
import cc.noharry.agent.spi.SessionEventPayload
import cc.noharry.agent.spi.reliability.RuntimeBlocker
import cc.noharry.agent.spi.reliability.RuntimeResult
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import cc.noharry.agent.spi.reliability.SuggestedNextAction
import cc.noharry.agent.runtime.thread.ThreadEventProjection
import cc.noharry.agent.kernel.core.trace.NativePublicEventBuffer
import cc.noharry.agent.runtime.DefaultTraceRedactor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ThreadEventProjectionTest {
    @Test fun completeSnapshotsAndUsageSurviveIgnoredDeltas() = runBlocking {
        val events = project(
            AgentEvent.Started("run"), AgentEvent.ThinkingDelta("run", "Think"),
            AgentEvent.TextDelta("run", "Working"),
            AgentEvent.NativeToolCallStarted("run", "mcp__device_media__play", "{\"track\":\"12345678901\"}", "call"),
            AgentEvent.NetworkToolActivity("run", "mcp__device_media__play", "allowed", "call", "example.org", 200),
            AgentEvent.NativeToolCallFinished("run", "mcp__device_media__play", "{\"ok\":true}", "call"),
            AgentEvent.TokenUsageDelta("run", 10, 2), AgentEvent.TokenUsageDelta("run", 3, 4),
            AgentEvent.TextDelta("run", "All "), AgentEvent.TextDelta("run", "done"),
            AgentEvent.ResultAvailable(RuntimeResult("run", RuntimeRunStatus.COMPLETED, "All done")),
            AgentEvent.Finished("run"),
        )
        lifecycle(events)
        val result = (events.last() as ThreadEvent.TurnCompleted).result
        assertEquals("All done", result.finalResponse)
        assertEquals(Usage(13, 6), result.usage)
        val tool = result.items.filterIsInstance<ThreadItem.ToolCall>().single()
        assertEquals("device_media", tool.server)
        assertEquals("{\"track\":\"12345678901\"}", tool.arguments)
        assertEquals(200, tool.network?.statusCode)
        val messages = result.items.filterIsInstance<ThreadItem.AgentMessage>()
        assertEquals(listOf("Working", "All done"), messages.map { it.text })
        assertEquals(listOf(MessagePhase.COMMENTARY, MessagePhase.FINAL), messages.map { it.phase })
        assertEquals("Think", result.items.filterIsInstance<ThreadItem.Reasoning>().single().text)
    }

    @Test fun failureCompletesEveryStartedItemExactlyOnce() = runBlocking {
        val events = project(
            AgentEvent.NativeToolCallStarted("run", "exec_js", "{}", "one"),
            AgentEvent.NativeToolCallStarted("run", "mcp__other__read", "{}", "two"),
            AgentEvent.ExecOutputDelta("run", "partial output"),
            AgentEvent.Failed("run", "MODEL_LOOP_TIMEOUT", "Timed out", true),
            AgentEvent.Failed("run", "MODEL_LOOP_FAILED", "late duplicate", false),
        )
        lifecycle(events)
        assertEquals("MODEL_LOOP_TIMEOUT", (events.last() as ThreadEvent.TurnFailed).error.code)
        val tools = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }.filterIsInstance<ThreadItem.ToolCall>()
        assertEquals(2, tools.size)
        assertTrue(tools.all { it.status == ItemStatus.FAILED })
        assertEquals("partial output", tools.first().output)
    }

    @Test fun blockingQuestionCompletesTheTurnAndKeepsTheRequest() = runBlocking {
        val events = project(AgentEvent.RuntimeBlocked(RuntimeBlocker(
            "run", "WAITING_USER_INPUT", "Which room?", SuggestedNextAction.ASK_USER,
        )))
        lifecycle(events)
        val result = (events.last() as ThreadEvent.TurnCompleted).result
        assertEquals(TurnStatus.WAITING_FOR_ANSWER, result.status)
        assertEquals("Which room?", (result.pending as Question).text)
        assertFalse((result.pending as Question).async)
    }

    @Test fun unansweredAsyncQuestionExpiresBeforeTurnCompletion() = runBlocking {
        val events = project(
            AgentEvent.UserInteraction("run", SessionEventPayload.UserInteraction("QUESTION", "PENDING", questionId = "q", text = "Preference?")),
            AgentEvent.ResultAvailable(RuntimeResult("run", RuntimeRunStatus.COMPLETED, "Done")),
        )
        lifecycle(events)
        val question = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }.filterIsInstance<Question>().single()
        assertTrue(question.async)
        assertEquals(ItemStatus.DECLINED, question.status)
    }

    @Test fun sameNamedConcurrentCallsKeepTheirOwnArgumentsAndResults() = runBlocking {
        val events = project(
            AgentEvent.NativeToolCallStarted("run", "mcp__test__read", "{\"id\":1}", "one"),
            AgentEvent.NativeToolCallStarted("run", "mcp__test__read", "{\"id\":2}", "two"),
            AgentEvent.NativeToolCallFinished("run", "mcp__test__read", "second", "two"),
            AgentEvent.NativeToolCallFinished("run", "mcp__test__read", "first", "one"),
            AgentEvent.ResultAvailable(RuntimeResult("run", RuntimeRunStatus.COMPLETED, "Done")),
        )
        lifecycle(events)
        val items = (events.last() as ThreadEvent.TurnCompleted).result.items.filterIsInstance<ThreadItem.ToolCall>()
        assertEquals(listOf("first", "second"), items.map { it.output })
    }

    @Test fun receivedAnswerIsNotDeclinedBeforeTheModelConsumesIt() = runBlocking {
        val question = SessionEventPayload.UserInteraction("QUESTION", "PENDING", questionId = "q", text = "Preference?")
        val events = project(
            AgentEvent.UserInteraction("run", question),
            AgentEvent.UserInteraction("run", question.copy(state = "ANSWER_RECEIVED")),
            AgentEvent.UserInteraction("run", question.copy(state = "ANSWERED")),
            AgentEvent.ResultAvailable(RuntimeResult("run", RuntimeRunStatus.COMPLETED, "Done")),
        )
        lifecycle(events)
        val completed = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }.filterIsInstance<Question>()
        assertEquals(listOf(ItemStatus.COMPLETED), completed.map { it.status })
    }

    @Test fun finalResponseWhitespaceDoesNotDuplicateTheMessage() = runBlocking {
        val events = project(
            AgentEvent.TextDelta("run", "\nAll done\n"),
            AgentEvent.ResultAvailable(RuntimeResult("run", RuntimeRunStatus.COMPLETED, "All done")),
        )
        lifecycle(events)
        val message = (events.last() as ThreadEvent.TurnCompleted).result.items.filterIsInstance<ThreadItem.AgentMessage>().single()
        assertEquals(MessagePhase.FINAL, message.phase)
        assertEquals("All done", message.text)
    }

    @Test fun inputFactsPreserveWholeSteeringMessagesAndDeduplicate() = runBlocking {
        val full = "a".repeat(150) + "\n  Keep this spacing"
        val fact = SessionEventPayload.UserInteraction("INPUT", "RECEIVED", inputRequestId = "input", text = full)
        val events = project(
            AgentEvent.UserInteraction("run", fact), AgentEvent.UserInteraction("run", fact),
            AgentEvent.SteeringAccepted("run", "input", full.take(120)),
            AgentEvent.UserInteraction("run", fact.copy(state = "CONSUMED")),
            AgentEvent.ResultAvailable(RuntimeResult("run", RuntimeRunStatus.COMPLETED, "Done")),
        )
        lifecycle(events)
        val input = (events.last() as ThreadEvent.TurnCompleted).result.items.filterIsInstance<ThreadItem.UserMessage>().single { it.steer }
        assertEquals(full, (input.input.single() as InputPart.Text).text)
    }

    @Test fun actualLoopBufferPreservesFailedToolPayloads() = runBlocking {
        val buffer = NativePublicEventBuffer("run", DefaultTraceRedactor)
        buffer.recordStarted("call", "mcp__test__read", "{\"number\":\"12345678901\"}")
        buffer.recordFinished("call", "mcp__test__read", "{\"ok\":false,\"code\":\"NOT_FOUND\"}")
        val events = project(*buffer.snapshot().toTypedArray(), AgentEvent.ResultAvailable(RuntimeResult("run", RuntimeRunStatus.COMPLETED, "Done")))
        lifecycle(events)
        val tool = (events.last() as ThreadEvent.TurnCompleted).result.items.filterIsInstance<ThreadItem.ToolCall>().single()
        assertEquals(ItemStatus.FAILED, tool.status)
        assertEquals("{\"number\":\"12345678901\"}", tool.arguments)
        assertEquals("{\"ok\":false,\"code\":\"NOT_FOUND\"}", tool.output)
    }

    private suspend fun project(vararg input: AgentEvent): List<ThreadEvent> {
        val events = mutableListOf<ThreadEvent>()
        val projection = ThreadEventProjection("thread", "turn", TurnInput.text("hello"), { error("Unexpected approval") }, events::add)
        input.forEach { projection.accept(it) }
        return events
    }

    private fun lifecycle(events: List<ThreadEvent>) {
        assertTrue(events.first() is ThreadEvent.TurnStarted)
        assertEquals(1, events.count { it is ThreadEvent.TurnCompleted || it is ThreadEvent.TurnFailed })
        val open = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        events.forEach { event ->
            when (event) {
                is ThreadEvent.ItemStarted -> { assertTrue(seen.add(event.item.id)); assertTrue(open.add(event.item.id)) }
                is ThreadEvent.ItemCompleted -> assertTrue(open.remove(event.item.id))
                is ThreadEvent.ItemDelta -> assertTrue(event.itemId in open)
                is ThreadEvent.ItemUpdated -> assertTrue(event.item.id in open)
                is ThreadEvent.TurnCompleted, is ThreadEvent.TurnFailed -> assertTrue(open.isEmpty())
                else -> Unit
            }
        }
        assertTrue(open.isEmpty())
    }
}

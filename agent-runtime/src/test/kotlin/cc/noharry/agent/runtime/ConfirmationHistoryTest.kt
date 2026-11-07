@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*

import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.HookDecision
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolMatcher
import cc.noharry.agent.spi.ToolPrefetchProvider
import cc.noharry.agent.spi.ToolPrefetchRequest
import cc.noharry.agent.spi.ToolPrefetchTrace
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.spi.ToolUse
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the model sees of a call that waited for the user's confirmation: approved, failed, declined or withdrawn. */
class ConfirmationHistoryTest {
    private class Door(private val result: String = """{"ok":true,"committed":true}""") : ToolProvider, ToolPrefetchProvider {
        override val providerId = "car"
        override val providerConcurrency = ProviderConcurrencyDeclaration.threadSafe()
        val calls = mutableListOf<String>()
        override suspend fun listTools() = listOf(
            ToolDescriptor("unlock_door", "解锁一扇车门。", """{"type":"object"}""", effectKind = "MUTATING"),
        )
        override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
            calls += toolName
            return ToolCallResult(true, result)
        }
        override fun localToolNames(request: ToolPrefetchRequest) = request.tools.map { it.name }
        override val remotePrefetchEnabled = false
        override suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace) = emptyList<String>()
    }

    private object AskFirst : RuntimeHook {
        override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
            if (context.approvedConfirmation) HookDecision.Allow() else HookDecision.RequireConfirmation("door", "开门吗？")
    }

    /** Plays one scripted round per request. */
    private class Script(private val rounds: List<suspend FlowCollector<ModelEvent>.() -> Unit>) : ModelClient {
        override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
        val seen = mutableListOf<ModelRequest>()
        override fun complete(request: ModelRequest): Flow<ModelEvent> {
            seen += request
            val round = rounds[seen.size - 1]
            return flow { round() }
        }
    }

    private val unlock: suspend FlowCollector<ModelEvent>.() -> Unit = {
        emit(ModelEvent.ThinkingDelta("用户要开主驾门。", signature = "sig-1"))
        emit(ModelEvent.TextDelta("我来开门。"))
        emit(ModelEvent.ToolCall("call_1", "mcp__car__unlock_door", """{"door":"driver"}"""))
        emit(ModelEvent.Completed)
    }
    private val answer: suspend FlowCollector<ModelEvent>.() -> Unit = {
        emit(ModelEvent.TextDelta("好的。")); emit(ModelEvent.Completed)
    }

    private fun runtime(client: ModelClient, door: Door) = AgentRuntimeBuilder.create()
        .modelClient(client).toolProvider(door).sessionHistory(SessionHistory()).sessionToolActivations(SessionToolActivations())
        .hook(AskFirst, ToolMatcher.toolName("mcp__car__unlock_door"))
        .build()

    private suspend fun AgentThread.collectTurn(
        turnId: String,
        input: String,
        respond: suspend (Approval) -> Unit,
    ): List<ThreadEvent> {
        val events = mutableListOf<ThreadEvent>()
        runStreamed(input, TurnOptions(turnId = turnId)).collect { event ->
            events += event
            if (event is ThreadEvent.ItemStarted && event.item is Approval) respond(event.item as Approval)
        }
        assertTurnEvents(events)
        return events
    }

    @Test fun anApprovedCallResumesWithItsThinkingSignatureAndWordsAndNoHandoffNote() = runBlocking<Unit> {
        val script = Script(listOf(unlock, answer))
        runtime(script, Door()).use { runtime ->
            runtime.startThread(ThreadOptions(threadId = "s1")).collectTurn("r1", "开主驾车门") { it.approve() }
            val resumed = script.seen[1].messages
            val call = resumed.single { it.role == "assistant" && it.toolCallId == "call_1" }
            assertEquals("sig-1", call.thinkingSignature)
            assertEquals("我来开门。", call.assistantText)
            assertEquals("开主驾车门", resumed.first().content)
            assertTrue(resumed.none { it.role == "system" })
        }
    }

    @Test fun theApprovalSaysWhichCallWaitsAndWithWhatArguments() = runBlocking<Unit> {
        val slowly = object : RuntimeHook {
            override suspend fun onPreToolUse(call: ToolUse, context: HookContext) =
                HookDecision.Allow(updatedArgumentsJson = """{"door":"driver","speed":"slow"}""")
        }
        AgentRuntimeBuilder.create().modelClient(Script(listOf(unlock, answer))).toolProvider(Door())
            .hook(slowly, ToolMatcher.toolName("mcp__car__unlock_door"))
            .hook(AskFirst, ToolMatcher.toolName("mcp__car__unlock_door"))
            .build().use { runtime ->
                val thread = runtime.startThread(ThreadOptions(threadId = "s1"))
                thread.collectTurn("r1", "开主驾车门") { pending ->
                    assertEquals("开门吗？", pending.reason)
                    assertEquals("mcp__car__unlock_door", pending.tool)
                    assertEquals("""{"door":"driver","speed":"slow"}""", pending.arguments)
                    assertEquals(pending, thread.pendingRequests().single())
                    pending.approve()
                }
            }
    }

    @Test fun aFailedApprovedCallEndsTheTurnAndTheNextTurnSeesIt() = runBlocking<Unit> {
        val script = Script(listOf(unlock, answer))
        runtime(script, Door("""{"ok":false,"code":"DOOR_JAMMED"}""")).use { runtime ->
            val thread = runtime.startThread(ThreadOptions(threadId = "s1"))
            val events = thread.collectTurn("r1", "开主驾车门") { it.approve() }
            assertEquals("DOOR_JAMMED", (events.last() as ThreadEvent.TurnFailed).error.code)
            thread.run("门开了吗")
            val next = script.seen[1].messages
            assertTrue(next.any { it.role == "tool" && it.content.contains("DOOR_JAMMED") })
            assertTrue(next.any { it.role == "user" && it.content.contains("ended without an answer") })
        }
    }

    @Test fun theCurrentAndNextTurnKnowTheUserDeclined() = runBlocking<Unit> {
        val script = Script(listOf(unlock, answer, answer))
        val door = Door()
        runtime(script, door).use { runtime ->
            val thread = runtime.startThread(ThreadOptions(threadId = "s1"))
            thread.collectTurn("r1", "开主驾车门") { it.deny() }
            thread.run("门开了吗")
            assertTrue(door.calls.isEmpty())
            for (request in script.seen.drop(1)) {
                assertTrue(request.messages.any { it.role == "assistant" && it.toolCallId == "call_1" && it.thinkingSignature == "sig-1" })
                assertTrue(request.messages.any { it.role == "tool" && it.content.contains("CONFIRMATION_DENIED") })
            }
            assertEquals("门开了吗", script.seen.last().messages.last().content)
        }
    }

    @Test fun theNextTurnKnowsTheApprovalWasWithdrawn() = runBlocking<Unit> {
        val script = Script(listOf(unlock, answer))
        runtime(script, Door()).use { runtime ->
            val thread = runtime.startThread(ThreadOptions(threadId = "s1"))
            thread.collectTurn("r1", "开主驾车门") { thread.interrupt() }
            thread.run("门开了吗")
            assertTrue(script.seen[1].messages.any { it.role == "tool" && it.content.contains("CONFIRMATION_CANCELLED") })
        }
    }

    @Test fun whileApprovalWaitsTheSessionShowsTheCallAsWaiting() = runBlocking<Unit> {
        val history = SessionHistory()
        AgentRuntimeBuilder.create().modelClient(Script(listOf(unlock))).toolProvider(Door()).sessionHistory(history)
            .sessionToolActivations(SessionToolActivations()).hook(AskFirst, ToolMatcher.toolName("mcp__car__unlock_door"))
            .build().use { runtime ->
                val thread = runtime.startThread(ThreadOptions(threadId = "s1"))
                thread.collectTurn("r1", "开主驾车门") {
                    val waiting = history.messages("s1")
                    assertTrue(waiting.any { it.role == "assistant" && it.toolCallId == "call_1" })
                    assertTrue(waiting.last().content.contains("awaiting_confirmation"))
                    thread.interrupt()
                }
            }
    }
}

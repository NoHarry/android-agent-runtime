@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.CancelPendingConfirmationRequest
import cc.noharry.agent.kernel.api.ConfirmationChoice
import cc.noharry.agent.kernel.api.ConfirmationDecision
import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolMatcher
import cc.noharry.agent.kernel.api.ToolPrefetchProvider
import cc.noharry.agent.kernel.api.ToolPrefetchRequest
import cc.noharry.agent.kernel.api.ToolPrefetchTrace
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
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

    private suspend fun cc.noharry.agent.kernel.api.AgentRuntime.pause(runId: String, input: String) =
        run(AgentRequest(runId, "s1", input)).toList().filterIsInstance<AgentEvent.ConfirmationRequired>().single().pending

    @Test fun anApprovedCallResumesWithItsThinkingSignatureAndWordsAndNoHandoffNote() = runBlocking {
        val script = Script(listOf(unlock, answer))
        val runtime = runtime(script, Door())
        val pending = runtime.pause("r1", "开主驾车门")
        runtime.resumeConfirmation(ConfirmationDecision(pending.pendingId, "d1", ConfirmationChoice.APPROVE))

        val resumed = script.seen[1].messages
        val call = resumed.single { it.role == "assistant" && it.toolCallId == "call_1" }
        assertEquals("sig-1", call.thinkingSignature)
        assertEquals("我来开门。", call.assistantText)
        assertEquals("开主驾车门", resumed.first().content)
        assertTrue(resumed.none { it.role == "system" })
    }

    @Test fun aFailedApprovedCallEndsTheRunAndTheNextRunSeesIt() = runBlocking {
        val script = Script(listOf(unlock, answer))
        val runtime = runtime(script, Door("""{"ok":false,"code":"DOOR_JAMMED"}"""))
        val pending = runtime.pause("r1", "开主驾车门")
        runtime.resumeConfirmation(ConfirmationDecision(pending.pendingId, "d1", ConfirmationChoice.APPROVE))
        runtime.run(AgentRequest("r2", "s1", "门开了吗")).toList()

        assertEquals(RuntimeRunStatus.FAILED, runtime.getRuntimeResult("r1")?.status)
        val next = script.seen[1].messages
        assertTrue(next.any { it.role == "tool" && it.content.contains("DOOR_JAMMED") })
        assertTrue(next.any { it.role == "user" && it.content.contains("ended without an answer") })
    }

    @Test fun theNextRunKnowsTheUserDeclined() = runBlocking {
        val script = Script(listOf(unlock, answer))
        val door = Door()
        val runtime = runtime(script, door)
        val pending = runtime.pause("r1", "开主驾车门")
        runtime.resumeConfirmation(ConfirmationDecision(pending.pendingId, "d1", ConfirmationChoice.DENY))
        runtime.run(AgentRequest("r2", "s1", "门开了吗")).toList()

        assertTrue(door.calls.isEmpty())
        val next = script.seen[1].messages
        assertEquals("开主驾车门", next.first().content)
        assertTrue(next.any { it.role == "assistant" && it.toolCallId == "call_1" && it.thinkingSignature == "sig-1" })
        assertTrue(next.any { it.role == "tool" && it.content.contains("USER_DECLINED") })
        assertEquals("门开了吗", next.last().content)
    }

    @Test fun theNextRunKnowsTheConfirmationWasWithdrawn() = runBlocking {
        val script = Script(listOf(unlock, answer))
        val runtime = runtime(script, Door())
        val pending = runtime.pause("r1", "开主驾车门")
        runtime.cancelPendingConfirmation(CancelPendingConfirmationRequest(pending.pendingId, "c1"))
        runtime.run(AgentRequest("r2", "s1", "门开了吗")).toList()

        assertTrue(script.seen[1].messages.any { it.role == "tool" && it.content.contains("CONFIRMATION_CANCELLED") })
    }

    @Test fun whileTheConfirmationWaitsTheSessionShowsTheCallAsWaiting() = runBlocking {
        val history = SessionHistory()
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(Script(listOf(unlock))).toolProvider(Door()).sessionHistory(history)
            .sessionToolActivations(SessionToolActivations())
            .hook(AskFirst, ToolMatcher.toolName("mcp__car__unlock_door"))
            .build()
        runtime.pause("r1", "开主驾车门")

        val waiting = history.messages("s1")
        assertTrue(waiting.any { it.role == "assistant" && it.toolCallId == "call_1" })
        assertTrue(waiting.last().content.contains("awaiting_confirmation"))
    }
}

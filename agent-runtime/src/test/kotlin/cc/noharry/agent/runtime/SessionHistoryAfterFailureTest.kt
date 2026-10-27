@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolPrefetchProvider
import cc.noharry.agent.kernel.api.ToolPrefetchRequest
import cc.noharry.agent.kernel.api.ToolPrefetchTrace
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelClientException
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a session's next run sees of a run that failed or was cancelled, and of what the model said beside its calls. */
class SessionHistoryAfterFailureTest {
    private class Car : ToolProvider, ToolPrefetchProvider {
        override val providerId = "car"
        override val providerConcurrency = ProviderConcurrencyDeclaration.threadSafe()
        val started = CompletableDeferred<Unit>()
        override suspend fun listTools() = listOf(
            ToolDescriptor("set_seat_heat", "设置座椅加热档位。", """{"type":"object"}""", effectKind = "MUTATING"),
            ToolDescriptor("slow_task", "要做很久的事。", """{"type":"object"}""", effectKind = "MUTATING"),
        )
        override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
            if (toolName == "slow_task") {
                started.complete(Unit)
                awaitCancellation()
            }
            return ToolCallResult(true, """{"ok":true,"committed":true}""")
        }
        override fun localToolNames(request: ToolPrefetchRequest) = request.tools.map { it.name }
        override val remotePrefetchEnabled = false
        override suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace) = emptyList<String>()
    }

    /** Plays one scripted round per request; a round may end by throwing. */
    private class Script(private val rounds: List<suspend kotlinx.coroutines.flow.FlowCollector<ModelEvent>.() -> Unit>) : ModelClient {
        override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
        val seen = mutableListOf<ModelRequest>()
        override fun complete(request: ModelRequest): Flow<ModelEvent> {
            seen += request
            val round = rounds[seen.size - 1]
            return flow { round() }
        }
    }

    private fun runtime(client: ModelClient, car: Car, history: SessionHistory, activations: SessionToolActivations) =
        AgentRuntimeBuilder.create().modelClient(client).toolProvider(car).sessionHistory(history)
            .sessionToolActivations(activations).build()

    private val answer: suspend kotlinx.coroutines.flow.FlowCollector<ModelEvent>.() -> Unit = {
        emit(ModelEvent.TextDelta("好的。")); emit(ModelEvent.Completed)
    }

    @Test fun aFailedRunKeepsTheCallsItRanAndEndsWithANoticeNotTheError() = runBlocking {
        val history = SessionHistory(); val activations = SessionToolActivations(); val car = Car()
        val script = Script(listOf({
            emit(ModelEvent.ToolCall("call_1", "mcp__car__set_seat_heat", """{"level":2}"""))
            emit(ModelEvent.UsageDelta(outputTokens = 3)) // the call runs here
            throw ModelClientException(RuntimeErrorCode.PROVIDER_RESPONSE_MALFORMED, recoverable = true, message = "broken stream")
        }, answer))
        runtime(script, car, history, activations).run(AgentRequest("r1", "s1", "开座椅加热")).toList()
        runtime(script, car, history, activations).run(AgentRequest("r2", "s1", "刚才怎么样了")).toList()

        assertEquals(2, script.seen.size) // the failed round was not retried: its call had run
        val second = script.seen[1].messages
        assertTrue(second.any { it.role == "assistant" && it.toolName == "mcp__car__set_seat_heat" })
        assertTrue(second.any { it.role == "tool" && it.content.contains("committed") })
        assertTrue(second.none { it.role == "assistant" && it.content.contains("broken stream") })
        assertTrue(second.any { it.role == "user" && it.content.contains("ended without an answer (PROVIDER_RESPONSE_MALFORMED)") })
    }

    @Test fun aCancelledRunIsInTheNextRunsHistoryWithItsCallClosed() = runBlocking {
        val history = SessionHistory(); val activations = SessionToolActivations(); val car = Car()
        val script = Script(listOf({
            emit(ModelEvent.ToolCall("call_1", "mcp__car__slow_task", "{}"))
            emit(ModelEvent.Completed)
        }, answer))
        val job = launch { runtime(script, car, history, activations).run(AgentRequest("r1", "s1", "预热座舱")).toList() }
        car.started.await()
        job.cancelAndJoin()
        runtime(script, car, history, activations).run(AgentRequest("r2", "s1", "刚才怎么样了")).toList()

        val second = script.seen[1].messages
        assertEquals("预热座舱", second.first().content)
        assertTrue(second.any { it.role == "tool" && it.content.contains("TOOL_CALL_CANCELLED") })
        assertTrue(second.any { it.role == "user" && it.content.contains("was cancelled before it finished") })
    }

    @Test fun whatTheModelSaidBesideItsCallReachesTheNextRequest() = runBlocking {
        val history = SessionHistory(); val activations = SessionToolActivations(); val car = Car()
        val script = Script(listOf({
            emit(ModelEvent.TextDelta("帮你开到二档。"))
            emit(ModelEvent.ToolCall("call_1", "mcp__car__set_seat_heat", """{"level":2}"""))
            emit(ModelEvent.Completed)
        }, answer))
        runtime(script, car, history, activations).run(AgentRequest("r1", "s1", "开座椅加热")).toList()

        val call = script.seen[1].messages.single { it.role == "assistant" && it.toolCallId == "call_1" }
        assertEquals("帮你开到二档。", call.assistantText)
    }
}

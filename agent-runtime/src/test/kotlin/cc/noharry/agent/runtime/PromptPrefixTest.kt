@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every request of a session starts with the one before it, so the provider's prompt cache keeps its prefix. */
class PromptPrefixTest {
    /** Calls the car's tool on a run's first round and answers on the next. */
    private class Model : ModelClient {
        override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
        val seen = mutableListOf<ModelRequest>()
        override fun complete(request: ModelRequest): Flow<ModelEvent> {
            seen += request
            val afterTool = request.messages.last().role == "tool"
            return flow {
                if (afterTool) {
                    emit(ModelEvent.TextDelta("空调 24 度。"))
                } else {
                    emit(ModelEvent.ToolCall("call_${seen.size}", "mcp__car__get_climate", "{}"))
                }
                emit(ModelEvent.Completed)
            }
        }
    }

    @Test
    fun eachRequestStartsWithThePreviousOne() = runBlocking {
        val model = Model()
        val car = functionTools("car") { tool("get_climate", "读空调。", readOnly = true) { """{"temperature":24}""" } }
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(model)
            .toolProvider(car)
            .workspaceRoot(Files.createTempDirectory("prompt-prefix").toFile())
            .sessionHistory(SessionHistory())
            .sessionToolActivations(SessionToolActivations())
            .runContext("当前时间 08:00")
            .build()
        runtime.run(AgentRequest("r1", "s1", "空调多少度")).toList()
        runtime.run(AgentRequest("r2", "s1", "现在呢")).toList()

        assertEachStartsWithThePrevious(model.seen)
    }

    @Test
    fun aRuntimePerRunWithItsOwnContextKeepsThePrefixToo() = runBlocking {
        // Bothy builds a runtime for each run, shares the history, and gives each run the time it starts at.
        val model = Model()
        val history = SessionHistory()
        val activations = SessionToolActivations()
        val workspace = Files.createTempDirectory("prompt-prefix-per-run").toFile()
        for ((run, input, now) in listOf(Triple("r1", "空调多少度", "08:00"), Triple("r2", "现在呢", "08:05"))) {
            val car = functionTools("car") { tool("get_climate", "读空调。", readOnly = true) { """{"temperature":24}""" } }
            val runtime = AgentRuntimeBuilder.create()
                .modelClient(model)
                .toolProvider(car)
                .workspaceRoot(workspace)
                .sessionHistory(history)
                .sessionToolActivations(activations)
                .deferredBackendTools()
                .runContext("当前时间 $now")
                .build()
            runtime.run(AgentRequest(run, "s1", input)).toList()
            runtime.close()
        }

        assertEachStartsWithThePrevious(model.seen)
    }

    private fun assertEachStartsWithThePrevious(requests: List<ModelRequest>) {
        assertEquals(4, requests.size)
        requests.zipWithNext().forEachIndexed { index, (before, after) ->
            val step = "request ${index + 2}"
            assertEquals("$step: system", before.system, after.system)
            assertEquals("$step: tools", before.tools, after.tools)
            assertEquals("$step: messages", before.messages, after.messages.take(before.messages.size))
        }
    }
}

@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.INVALID_FINISH_RESPONSE_CODE
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishArgumentValidationRuntimeTest {
    @Test fun embeddedBlockedOutcomeCannotBecomeLegacySuccess() = runBlocking {
        val nested = """{"response":"导航没有启动。","outcome":"blocked"}"""
        val bad = buildJsonObject { put("response", nested) }.toString()
        val runtime = AgentRuntimeBuilder.create().modelClient(FakeModelClient(eventsByRound = listOf(
            round(finishCall("nested", bad)), round(finishCall("corrected", nested)),
        ))).build()
        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        assertEquals(RuntimeCompletionOutcome.BLOCKED, result.completion.outcome)
        assertEquals("导航没有启动。", result.response)
        assertEquals(2, events.filterIsInstance<AgentEvent.NativeToolCallFinished>().count { it.toolName == "finish" })
    }

    @Test fun leakedOutcomeGetsOneCorrectionAndPreservesPartial() = runBlocking {
        val requests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create().modelClient(FakeModelClient(eventsByRound = listOf(
            round(finishCall("first", leakedArguments())),
            round(finishCall("fixed", """{"response":"已算路，沿途搜索失败。","outcome":"partial"}""")),
        ), seenRequests = requests)).build()
        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals(RuntimeCompletionOutcome.PARTIAL, result.completion.outcome)
        assertFalse(result.response.contains("parameter"))
        assertEquals(2, requests.size)
        assertTrue(requests[1].messages.any { it.role == "tool" && it.content.contains(INVALID_FINISH_RESPONSE_CODE) })
        assertEquals(2, events.filterIsInstance<AgentEvent.NativeToolCallFinished>().count { it.toolName == "finish" })
    }

    @Test fun repeatedMalformedShapeOrUnknownOutcomeFailsWithBothToolResults() = runBlocking {
        for (args in listOf("not-json", """{"response":"done","outcome":"unknown"}""", leakedArguments())) {
            val requests = mutableListOf<ModelRequest>()
            val runtime = AgentRuntimeBuilder.create().modelClient(FakeModelClient(eventsByRound = listOf(
                round(finishCall("first", args)), round(finishCall("again", args)),
            ), seenRequests = requests)).build()
            val events = runtime.run(agentRequest()).toList()
            val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
            assertEquals(RuntimeRunStatus.FAILED, result.status)
            assertEquals(INVALID_FINISH_RESPONSE_CODE, result.errorCode)
            assertEquals(2, requests.size)
            assertEquals(2, events.filterIsInstance<AgentEvent.NativeToolCallFinished>().count { it.toolName == "finish" })
        }
    }

    @Test fun implicitTextCannotBypassTheSameCorrectionBudget() = runBlocking {
        val runtime = AgentRuntimeBuilder.create().modelClient(FakeModelClient(eventsByRound = listOf(
            round(finishCall("bad", leakedArguments())),
            listOf(ModelEvent.SearchActivity("provider", "query", 1), ModelEvent.TextDelta(LEAK), ModelEvent.Completed),
        ))).build()
        val result = runtime.run(agentRequest()).toList().filterIsInstance<AgentEvent.ResultAvailable>().single().result
        assertEquals(RuntimeRunStatus.FAILED, result.status)
        assertEquals(INVALID_FINISH_RESPONSE_CODE, result.errorCode)
    }

    @Test fun hookRewrittenArgumentsAreValidatedBeforeFinish() = runBlocking {
        val runtime = AgentRuntimeBuilder.create().modelClient(FakeModelClient(eventsByRound = listOf(
            round(finishCall("first", """{"response":"done"}""")),
            round(finishCall("second", """{"response":"done"}""")),
        ))).hook(object : RuntimeHook {
            override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
                HookDecision.Allow(updatedArgumentsJson = """{"response":"done","outcome":false}""")
        }).build()
        val result = runtime.run(agentRequest()).toList().filterIsInstance<AgentEvent.ResultAvailable>().single().result
        assertEquals(RuntimeRunStatus.FAILED, result.status)
        assertEquals(INVALID_FINISH_RESPONSE_CODE, result.errorCode)
    }
}

private const val LEAK = "已算路，沿途搜索失败。</response>\n<parameter name=\"outcome\">partial"
private fun leakedArguments() = buildJsonObject { put("response", LEAK) }.toString()
private fun finishCall(id: String, args: String) = ModelEvent.ToolCall(id, "finish", args)
private fun round(event: ModelEvent) = listOf(event, ModelEvent.Completed)

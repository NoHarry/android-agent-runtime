@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.core.*

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.ToolSource
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeToolLoopStopHookTest {
    @Test
    fun stopHookCanBlockFinishAndModelContinues() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .hook(stopFinishUntilResponseContains("approved final"))
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall(
                                "finish-1",
                                "finish",
                                """{"response":"draft summary"}""",
                            ),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall("finish-2", "finish", """{"response":"approved final"}"""),
                            ModelEvent.Completed,
                        ),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        val result = runtime.run(agentRequest()).toList()
            .filterIsInstance<AgentEvent.ResultAvailable>()
            .single()
            .result
        val stopToolResult = seenRequests[1].messages.single { it.role == "tool" && it.toolName == "finish" }

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals("approved final", result.response)
        assertEquals(2, seenRequests.size)
        assertTrue(stopToolResult.content.contains("Stop hook blocked finish."))
        assertTrue(stopToolResult.content.contains("Return a complete final answer."))
        assertTrue(stopToolResult.content.contains(""""retryable":true"""))
    }
}

private fun stopFinishUntilResponseContains(token: String): RuntimeHook = object : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
        if (call.source == ToolSource.TERMINAL && call.toolName == "finish" && token !in call.argumentsJson) {
            HookDecision.BlockAndContinue(
                reason = "Stop hook blocked finish.",
                prompt = "Return a complete final answer.",
            )
        } else {
            HookDecision.Allow()
        }
}

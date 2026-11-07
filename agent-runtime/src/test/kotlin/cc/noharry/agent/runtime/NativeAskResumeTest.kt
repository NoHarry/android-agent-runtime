@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.spi.AgentEvent
import cc.noharry.agent.spi.AgentRequest
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.SessionEventType
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAskResumeTest {
    @Test fun askPausesAndReplyResumesTheSameRunWithoutExecEngineExposure() = runBlocking {
        val requests = mutableListOf<ModelRequest>()
        val model = object : ModelClient {
            override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
            override fun complete(request: ModelRequest) = run {
                requests += request
                flowOf(if (requests.size == 1) {
                    ModelEvent.ToolCall("ask-1", "ask", """{"question":"目标音量是多少？"}""")
                } else {
                    ModelEvent.ToolCall("finish-1", "finish", """{"response":"收到18"}""")
                })
            }
        }
        val runtime = AgentRuntimeBuilder.create().modelClient(model).buildEngine()
        try {
            val events = runtime.run(AgentRequest("q", "s", "设置音量")).toList()
            val run = events.filterIsInstance<AgentEvent.Started>().single().runId
            assertEquals(RuntimeRunStatus.WAITING_USER_INPUT, runtime.getRunState(run)?.status)
            assertEquals("目标音量是多少？", runtime.getOpenConfusion(run)?.question)
            val result = runtime.resumeUserInput(AgentRequest("reply", "s", "18", resumeRunId = run))
            assertEquals(run, result?.runId)
            assertEquals(RuntimeRunStatus.COMPLETED, result?.status)
            assertTrue(requests.last().messages.any { it.role == "user" && it.content == "18" })
            assertTrue(requests.all { r -> r.tools.any { it.name == "ask" } })
            assertFalse(requests.any { r -> r.tools.any { it.name == "exec_js" } })
            val log = runtime.replaySessionEvents("s", run)
            assertTrue(log.any { it.type == SessionEventType.TOOL_RESULT })
        } finally {
            runtime.close()
        }
    }

    @Test fun theQuestionGoesOutAsWritten() = runBlocking<Unit> {
        val question = "2^40 = 1099511627776, right? Reply to 13800138000 or lat=31.2304?"
        val model = object : ModelClient {
            override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
            override fun complete(request: ModelRequest) =
                flowOf(ModelEvent.ToolCall("ask-1", "ask", """{"question":"$question"}"""))
        }
        val runtime = AgentRuntimeBuilder.create().modelClient(model).buildEngine()
        try {
            val events = runtime.run(AgentRequest("q", "s", "Count.")).toList()
            val run = events.filterIsInstance<AgentEvent.Started>().single().runId

            assertEquals(question, runtime.getOpenConfusion(run)?.question)
            assertEquals(question, events.filterIsInstance<AgentEvent.RuntimeBlocked>().single().blocker.message)
            assertEquals(question, events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text })
        } finally {
            runtime.close()
        }
    }

    @Test fun invalidAskIsCorrectableAndDoesNotPause() = runBlocking {
        var rounds = 0
        val model = object : ModelClient {
            override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
            override fun complete(request: ModelRequest) = flowOf(
                if (rounds++ == 0) ModelEvent.ToolCall("bad", "ask", """{"question":"   "}""")
                else ModelEvent.ToolCall("end", "finish", """{"response":"已纠正"}"""),
            )
        }
        val runtime = AgentRuntimeBuilder.create().modelClient(model).buildEngine()
        try {
            runtime.run(AgentRequest("q", "s", "你好")).toList()
            assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRunState("q")?.status)
            assertEquals(2, rounds)
        } finally {
            runtime.close()
        }
    }
}

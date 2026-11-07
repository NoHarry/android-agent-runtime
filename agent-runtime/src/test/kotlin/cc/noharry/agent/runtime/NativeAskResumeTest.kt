@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.*
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.SessionEventType
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAskResumeTest {
    @Test fun askCompletesItsTurnAndAnExplicitReplyStartsANewTurnWithoutExecExposure() = runBlocking {
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
        val runtime = AgentRuntimeBuilder.create().modelClient(model).build()
        try {
            val thread = runtime.startThread(ThreadOptions(threadId = "s"))
            val question = thread.run("设置音量", TurnOptions(turnId = "q"))
            assertEquals(TurnStatus.WAITING_FOR_ANSWER, question.status)
            assertEquals("目标音量是多少？", (question.pending as Question).text)
            val result = thread.run(TurnInput(listOf(InputPart.Text("18")), answering = question.pending!!.id))
            assertTrue(result.turnId != question.turnId)
            assertEquals(TurnStatus.COMPLETED, result.status)
            assertTrue(requests.last().messages.any { it.role == "user" && it.content.contains("18") && it.content.contains("目标音量是多少？") })
            assertTrue(requests.all { r -> r.tools.any { it.name == "ask" } })
            assertFalse(requests.any { r -> r.tools.any { it.name == "exec_js" } })
            val log = runtime.diagnostics.replaySessionEvents("s", question.turnId)
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
        val runtime = AgentRuntimeBuilder.create().modelClient(model).build()
        try {
            val result = runtime.startThread().run("Count.")
            assertEquals(question, (result.pending as Question).text)
            assertEquals(question, result.finalResponse)
            assertEquals(question, result.items.filterIsInstance<Question>().single().text)
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
        val runtime = AgentRuntimeBuilder.create().modelClient(model).build()
        try {
            val result = runtime.startThread().run("你好")
            assertEquals(TurnStatus.COMPLETED, result.status)
            assertEquals(2, rounds)
        } finally {
            runtime.close()
        }
    }
}

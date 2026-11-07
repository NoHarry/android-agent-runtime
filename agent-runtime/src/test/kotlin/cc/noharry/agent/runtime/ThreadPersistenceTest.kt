@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.model.ModelEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ThreadPersistenceTest {
    @Test fun rememberedApprovalAndItemsSurviveRuntimeRecreation() = runBlocking {
        val root = threadRoot()
        approvalBuilder(root).build().use { runtime ->
            runtime.startThread(ThreadOptions(threadId = "remember", approvalPolicy = ApprovalPolicy.REMEMBER))
                .runStreamed("set temperature").collect {
                    if (it is ThreadEvent.ItemStarted && it.item is Approval) (it.item as Approval).approve()
                }
        }
        approvalBuilder(root).build().use { runtime ->
            assertEquals("remember", runtime.listThreads().single().id)
            val thread = runtime.resumeThread("remember")
            assertTrue(thread.items().any { it is Approval })
            assertTrue(thread.items().filterIsInstance<ThreadItem.ToolCall>().any { it.approvedBy == ApprovalSource.USER })
            val events = thread.runStreamed("set it again").toList()
            assertTurnEvents(events)
            assertFalse(events.any { it is ThreadEvent.ItemStarted && it.item is Approval })
            val tools = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }.filterIsInstance<ThreadItem.ToolCall>()
            assertTrue(tools.any { it.approvedBy == ApprovalSource.REMEMBERED })
        }
    }

    @Test fun questionAndModelHistorySurviveRestartAndAnswerStartsANewTurn() = runBlocking {
        val root = threadRoot()
        val ask = ThreadScriptModel(listOf(listOf(ModelEvent.ToolCall("ask", "ask", """{"question":"Which color?"}"""), ModelEvent.Completed)))
        var questionId = ""
        var firstTurn = ""
        threadBuilder(ask, root).build().use { runtime ->
            val result = runtime.startThread(ThreadOptions(threadId = "history", instructions = "THREAD_INSTRUCTION"))
                .run("choose a color")
            assertEquals(TurnStatus.WAITING_FOR_ANSWER, result.status)
            questionId = checkNotNull(result.pending).id
            firstTurn = result.turnId
        }
        val model = ThreadScriptModel(listOf(finishEvents("Blue selected")))
        threadBuilder(model, root).build().use { runtime ->
            val thread = runtime.resumeThread("history")
            assertEquals(questionId, thread.pendingRequests().single().id)
            val result = thread.run(TurnInput(listOf(InputPart.Text("blue")), answering = questionId))
            assertEquals(TurnStatus.COMPLETED, result.status)
            assertNotEquals(firstTurn, result.turnId)
            val messages = model.requests.first().messages
            assertTrue(messages.any { it.role == "tool" && it.toolName == "ask" && it.content.contains("waitingForUser") })
            assertTrue(messages.last { it.role == "user" }.content.contains("Which color?"))
            assertTrue(model.requests.first().system.orEmpty().contains("THREAD_INSTRUCTION"))
            assertTrue(thread.pendingRequests().isEmpty())
            assertTrue(thread.items(firstTurn).all { it.turnId == result.turnId })
        }
    }

    @Test fun longNewRequestAfterQuestionIsPassedUnchanged() = runBlocking {
        val model = ThreadScriptModel(listOf(
            listOf(ModelEvent.ToolCall("ask", "ask", """{"question":"Which color?"}"""), ModelEvent.Completed), finishEvents("New task"),
        ))
        threadBuilder(model).build().use { runtime ->
            val thread = runtime.startThread()
            thread.run("choose a color")
            val request = "Please ignore that question and instead calculate the total cost of this completely new shopping list."
            thread.run(request)
            assertEquals(request, model.requests.last().messages.last { it.role == "user" }.content)
        }
    }

    @Test fun turnIdReplayDoesNotRunModelAgainAndDeleteRemovesHistory() = runBlocking {
        val model = ThreadScriptModel(listOf(finishEvents("done")))
        threadBuilder(model).build().use { runtime ->
            val thread = runtime.startThread()
            val first = thread.run("hello", TurnOptions(turnId = "stable"))
            val second = thread.run("hello", TurnOptions(turnId = "stable"))
            assertEquals(first, second)
            assertEquals(1, model.requests.size)
            runtime.deleteThread(thread.id)
            assertTrue(runtime.listThreads().isEmpty())
        }
    }
}

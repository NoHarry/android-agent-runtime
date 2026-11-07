@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.SessionEventPayload
import cc.noharry.agent.spi.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class ThreadQuestionBindingTest {
    @Test fun replyingToFirstOfThreeQuestionsPreservesItsIdentityInModelInputAndEvents() = runBlocking {
        val model = ThreeQuestionModel()
        threadBuilder(model).build().use { runtime ->
            val thread = runtime.startThread()
            val collected = async { thread.runStreamed("plan a trip").toList() }
            model.waiting.await()
            val questions = thread.pendingRequests().filterIsInstance<Question>()
            assertEquals(3, questions.size)
            val first = questions.first()
            assertEquals("Destination?", first.text)
            assertEquals(SteerResult.ACCEPTED, thread.steer("Suzhou", first.id))
            assertEquals(SteerResult.QUESTION_NOT_FOUND, thread.steer("duplicate", first.id))
            model.release.complete(Unit)
            val events = withTimeout(5000) { collected.await() }
            assertTurnEvents(events)
            val reply = model.requests.last().messages.single { it.role == "user" && it.content.contains("Suzhou") }
            assertTrue(reply.content.contains("Destination?"))
            assertFalse(reply.content.contains("Budget?"))
            val completed = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }.filterIsInstance<Question>()
            assertEquals(ItemStatus.COMPLETED, completed.single { it.id == first.id }.status)
            assertTrue(completed.filter { it.id != first.id }.all { it.status == ItemStatus.DECLINED })
            val turnId = (events.first() as ThreadEvent.TurnStarted).turnId
            val facts = runtime.diagnostics.replaySessionEvents(thread.id, turnId).mapNotNull { it.payload as? SessionEventPayload.UserInteraction }
            val answered = facts.single { it.kind == "QUESTION" && it.state == "ANSWERED" }
            assertEquals(first.id.substringAfter(":question:"), answered.questionId)
        }
    }

    @Test fun customTurnIdContainingQuestionSeparatorStillBindsTheRequestedQuestion() = runBlocking {
        val model = ThreeQuestionModel()
        threadBuilder(model).build().use { runtime ->
            val thread = runtime.startThread()
            val turnId = "client:question:42"
            val collected = async { thread.runStreamed("plan", TurnOptions(turnId = turnId)).toList() }
            model.waiting.await()
            val first = thread.pendingRequests().filterIsInstance<Question>().first()
            assertEquals(SteerResult.ACCEPTED, thread.steer("Suzhou", first.id))
            model.release.complete(Unit)
            val events = withTimeout(5000) { collected.await() }
            assertTurnEvents(events)
            val reply = model.requests.last().messages.single { it.role == "user" && it.content.contains("Suzhou") }
            assertTrue(reply.content.contains("Answer to question $turnId/questions/0 (Destination?)"))
            val completed = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }.filterIsInstance<Question>()
            assertEquals(ItemStatus.COMPLETED, completed.single { it.id == first.id }.status)
        }
    }

    @Test fun staleUnknownAndTruncatedQuestionIdsDoNotPostInput() = runBlocking {
        val model = ThreeQuestionModel()
        threadBuilder(model).build().use { runtime ->
            val thread = runtime.startThread()
            val collected = async { thread.runStreamed("plan a trip").toList() }
            model.waiting.await()
            val first = thread.pendingRequests().filterIsInstance<Question>().first()
            assertEquals(SteerResult.QUESTION_NOT_FOUND, thread.steer("unknown", "unknown"))
            assertEquals(SteerResult.QUESTION_NOT_FOUND, thread.steer("truncated", first.id.substringAfter(":question:")))
            model.release.complete(Unit)
            collected.await()
            assertEquals(SteerResult.QUESTION_NOT_FOUND, thread.steer("stale", first.id))
            assertFalse(model.requests.any { request -> request.messages.any { it.role == "user" && it.content.contains("unknown") } })
            assertTrue(thread.pendingRequests().isEmpty())
        }
    }
}

private class ThreeQuestionModel : ModelClient {
    override val providerConcurrency = ProviderConcurrencyDeclaration.threadSafe()
    val waiting = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val requests = mutableListOf<ModelRequest>()
    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        requests += request.copy(messages = request.messages.toList())
        if (requests.size == 1) {
            emit(ModelEvent.ToolCall("questions", "ask_async", """{"questions":[{"title":"Destination?"},{"title":"Travel dates?"},{"title":"Budget?"}]}"""))
        } else {
            if (requests.size == 2) { waiting.complete(Unit); release.await() }
            emit(ModelEvent.ToolCall("finish-${requests.size}", "finish", """{"response":"done"}"""))
        }
        emit(ModelEvent.Completed)
    }
}

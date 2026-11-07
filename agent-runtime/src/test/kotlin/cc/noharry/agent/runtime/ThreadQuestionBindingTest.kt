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
    @Test fun ordinaryInputDoesNotAnswerAnyQuestionOrGetLostAfterAnExplicitAnswer() = runBlocking {
        val model = ThreeQuestionModel()
        threadBuilder(model).build().use { runtime ->
            val thread = runtime.startThread()
            val collected = async { thread.runStreamed("plan a trip").toList() }
            model.waiting.await()
            val questions = thread.pendingRequests().filterIsInstance<Question>()
            assertEquals(SteerResult.ACCEPTED, thread.steer("Walk through a park"))
            assertEquals(SteerResult.ACCEPTED, thread.steer("Generous", questions.last().id))
            assertEquals(SteerResult.ACCEPTED, thread.steer("And stop for tea", SteerOptions(requestId = "tea")))
            model.release.complete(Unit)
            val events = withTimeout(5000) { collected.await() }
            assertTurnEvents(events)
            val messages = model.requests.last().messages.filter { it.role == "user" }.map { it.content }
            assertTrue(messages.any { "Walk through a park" in it && "Answer to question" !in it })
            assertTrue(messages.any { "And stop for tea" in it && "Answer to question" !in it })
            assertTrue(messages.any { "Generous" in it && "Budget?" in it })
            val completed = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }.filterIsInstance<Question>()
            assertEquals(ItemStatus.COMPLETED, completed.single { it.id == questions.last().id }.status)
            assertTrue(completed.filter { it.id != questions.last().id }.all { it.status == ItemStatus.DECLINED })
        }
    }

    @Test fun hostRequestIdIsDeduplicatedEchoedAndRestoredAfterRestart() = runBlocking {
        val root = threadRoot()
        val model = ThreeQuestionModel()
        threadBuilder(model, root).build().use { runtime ->
            val thread = runtime.startThread(ThreadOptions(threadId = "request-id"))
            val collected = async { thread.runStreamed("plan a trip").toList() }
            model.waiting.await()
            val options = SteerOptions(requestId = "host-message")
            assertEquals(SteerResult.ACCEPTED, thread.steer("Take the scenic route", options))
            assertEquals(SteerResult.ACCEPTED, thread.steer("Take the scenic route", options))
            assertEquals(SteerResult.REQUEST_ID_CONFLICT, thread.steer("Use the highway", options))
            model.release.complete(Unit)
            val events = withTimeout(5000) { collected.await() }
            assertTurnEvents(events)
            val started = events.filterIsInstance<ThreadEvent.ItemStarted>().map { it.item }.filterIsInstance<ThreadItem.UserMessage>()
            val completed = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }.filterIsInstance<ThreadItem.UserMessage>()
            assertEquals("host-message", started.single { it.steer }.requestId)
            assertEquals(started.single { it.steer }, completed.single { it.steer })
            assertTrue(started.filter { !it.steer }.all { it.requestId == null })
        }
        threadBuilder(ThreadScriptModel(emptyList()), root).build().use { runtime ->
            val item = runtime.resumeThread("request-id").items().filterIsInstance<ThreadItem.UserMessage>().single { it.steer }
            assertEquals("host-message", item.requestId)
            assertEquals("Take the scenic route", (item.input.single() as InputPart.Text).text)
        }
    }

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

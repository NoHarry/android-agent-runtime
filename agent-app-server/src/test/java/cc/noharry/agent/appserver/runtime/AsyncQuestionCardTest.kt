@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)
package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.*
import cc.noharry.agent.appserver.protocol.view.questionCards
import cc.noharry.agent.appserver.trajectory.TrajectorySessionState
import cc.noharry.agent.appserver.trajectory.toTrajectoryRecord
import cc.noharry.agent.spi.*
import cc.noharry.agent.spi.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class AsyncQuestionCardTest {
    @Test fun protocolCardAnswersItsOwnQuestionInsteadOfTheLastQuestion() = runBlocking {
        val model = AskingModel()
        val runtime = AgentRuntime.builder().model(model).build()
        val request = RuntimeTurnRequest("turn", "session", "ask")
        val thread = request.thread(runtime)
        val registry = ActiveRunRegistry()
        val resources = ActiveRunResources(runtime::close, {})
        val record = ActiveRunRecord("session", "turn", "turn", runtime, resources, null, 1,
            recorders = multiSessionRecorders(request), request = request)
        registry.put(record)
        val job = launch(Dispatchers.Default) { thread.runStreamed("ask", request.options()).collect() }
        record.job = job
        try {
            withTimeout(5_000) { model.waiting.await() }
            val raw = runtime.diagnostics.replaySessionEvents("session", "turn")
            assertTrue(raw.any { it.runId != "turn" && it.requestId == "turn" })
            val trajectory = TrajectorySessionState("session")
            raw.forEach { trajectory.ensureAcceptedGroup(it, it.toTrajectoryRecord()) }
            val card = trajectory.toSnapshot(1).questionCards("turn").map { it.first }.first { it.title == "Destination?" }
            assertEquals(2, thread.pendingRequests().filterIsInstance<Question>().size)
            val result = postRunInput(registry, card.sessionId, card.runId, "answer", "Suzhou", card.id)
            assertEquals(AnswerRoute.Delivered, answerRoute(result, selected = true, running = true, ownsRun = true))
            val answers = runtime.diagnostics.replaySessionEvents("session", "turn")
                .mapNotNull { it.payload as? SessionEventPayload.UserInteraction }.filter { it.state == "ANSWER_RECEIVED" }
            assertEquals(listOf(card.id), answers.map { it.questionId })
            assertEquals(SteerResult.QUESTION_NOT_FOUND, postRunInput(registry, "session", "turn", "unknown", "wrong", "missing"))
            assertEquals(SteerResult.NOT_RUNNING, postRunInput(registry, "other", "turn", "wrong-session", "wrong", card.id))
        } finally {
            model.release.complete(Unit)
            job.cancelAndJoin()
            resources.close()
        }
    }
}

private class AskingModel : ModelClient {
    override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
    val waiting = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    private var calls = 0
    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        if (calls++ == 0) {
            emit(ModelEvent.ToolCall("ask", "ask_async", """{"questions":[{"title":"Destination?","options":["Suzhou","Shanghai"]},{"title":"Transport?","options":["Train","Car"]}]}"""))
        } else {
            waiting.complete(Unit)
            release.await()
            emit(ModelEvent.ToolCall("finish", "finish", """{"response":"done","outcome":"success"}"""))
        }
        emit(ModelEvent.Completed)
    }
}

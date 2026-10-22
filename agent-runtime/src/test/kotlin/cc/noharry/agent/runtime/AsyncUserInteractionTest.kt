@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.*
import cc.noharry.agent.kernel.api.model.*
import cc.noharry.agent.kernel.core.KernelRuntimeBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class AsyncUserInteractionTest {
    @Test fun facadeAndCoreContinueBeforeAnyAnswerAndConsumeReplyAfterTheCurrentRequest() = runBlocking {
        for (core in listOf(false, true)) {
            val model = QuestionModel(holdSecondRound = true)
            val runtime = runtime(model, core)
            val seen = mutableListOf<AgentEvent>()
            val job = async { runtime.run(AgentRequest("run", "session", "plan a trip")).collect { seen += it } }
            try {
                withTimeout(10_000) { model.secondRoundStarted.await() }
                assertEquals(2, model.calls)
                assertTrue(seen.any { it is AgentEvent.NativeToolCallFinished && it.toolName == "update_todo" })
                val pending = seen.filterIsInstance<AgentEvent.UserInteraction>().first().interaction
                val answer = PostUserInputRequest("run", "answer", "Suzhou", "session", pending.questionId)
                assertEquals(PostUserInputResult.Rejected("QUESTION_NOT_FOUND"), runtime.postUserInput(answer.copy(questionId = "wrong")))
                assertEquals(PostUserInputResult.Accepted, runtime.postUserInput(answer))
                assertEquals(PostUserInputResult.Duplicate, runtime.postUserInput(answer))
                model.releaseSecondRound.complete(Unit)
                withTimeout(10_000) { job.await() }
                assertEquals(3, model.calls)
                assertFalse(model.requests[1].messages.any { it.role == "user" && it.content.contains("Suzhou") })
                val last = model.requests.last().messages
                assertEquals(1, last.count { it.role == "user" && it.content.contains("Suzhou") && it.content.contains("Destination?") })
                // The model is told why a completed answer precedes new input, and that finish must be called again.
                val note = last.indexOfFirst { it.role == "user" && it.content.contains("previous finish response above was not delivered") }
                assertTrue(note >= 0 && last[note + 1].content.contains("Suzhou"))
                assertEquals("assistant", last[note - 1].role)
                // Withheld finish text is not published; only the delivered finish reaches TextDelta/TTS.
                assertEquals(1, seen.count { it is AgentEvent.TextDelta && it.text == "done" })
                val states = seen.filterIsInstance<AgentEvent.UserInteraction>().map { it.interaction.kind to it.interaction.state }
                for (expected in listOf("QUESTION" to "PENDING", "INPUT" to "RECEIVED", "QUESTION" to "ANSWER_RECEIVED", "INPUT" to "CONSUMED", "QUESTION" to "ANSWERED")) {
                    assertTrue("missing $expected in $states", expected in states)
                }
                assertTrue(states.indexOf("INPUT" to "RECEIVED") < states.indexOf("INPUT" to "CONSUMED"))
                assertEquals("done", runtime.getRuntimeResult("run")?.response)
                if (!core) assertConsumptionFollowsRequest(runtime.replaySessionEvents("session", "run"))
            } finally { job.cancelAndJoin(); runtime.close() }
        }
    }

    @Test fun lateAnswerAtTheRoundBudgetEdgeDeliversTheWithheldFinishInsteadOfFailing() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("async-budget-edge").toFile()
        val model = QuestionModel(holdSecondRound = true)
        val runtime = AgentRuntimeBuilder.create().modelClient(model).sessionEventLogRoot(root).loopBudget(maxModelRounds = 2).build()
        val seen = mutableListOf<AgentEvent>()
        val job = async { runtime.run(AgentRequest("run", "session", "plan")).collect { seen += it } }
        try {
            withTimeout(10_000) { model.secondRoundStarted.await() }
            val question = seen.filterIsInstance<AgentEvent.UserInteraction>().first().interaction
            assertEquals(PostUserInputResult.Accepted, runtime.postUserInput(PostUserInputRequest("run", "answer", "Suzhou", "session", question.questionId)))
            model.releaseSecondRound.complete(Unit)
            withTimeout(10_000) { job.await() }
            assertEquals(2, model.calls)
            val result = runtime.getRuntimeResult("run")
            assertEquals(cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus.COMPLETED, result?.status)
            assertEquals("done", result?.response)
            assertEquals(1, seen.count { it is AgentEvent.TextDelta && it.text == "done" })
            val facts = seen.filterIsInstance<AgentEvent.UserInteraction>().map { it.interaction }
            assertEquals("MODEL_LOOP_BUDGET_EXCEEDED", facts.single { it.kind == "INPUT" && it.state == "UNCONSUMED" }.reason)
            assertEquals("MODEL_LOOP_BUDGET_EXCEEDED", facts.single { it.kind == "QUESTION" && it.state == "EXPIRED" }.reason)
            assertFalse(facts.any { it.state == "CONSUMED" || it.state == "ANSWERED" })
        } finally { job.cancelAndJoin(); runtime.close() }
        val log = cc.noharry.agent.runtime.eventlog.FileBackedSessionEventLog(root).replay("session", "run")
        assertTrue(log.any { (it.payload as? SessionEventPayload.UserInteraction)?.state == "UNCONSUMED" })
        assertTrue(log.any { (it.payload as? SessionEventPayload.AssistantMessage)?.text?.contains("continuation unavailable") == true })
    }

    @Test fun noReplyNeverPausesTheRunOrPreventsCompletionOnEitherProfile() = runBlocking {
        for (core in listOf(false, true)) {
            val model = QuestionModel()
            val runtime = runtime(model, core)
            try {
                val seen = withTimeout(10_000) { runtime.run(AgentRequest("run", "session", "plan")).toList() }
                assertEquals(2, model.calls)
                assertEquals("done", runtime.getRuntimeResult("run")?.response)
                assertTrue(seen.any { it is AgentEvent.UserInteraction && it.interaction.state == "PENDING" })
                assertFalse(seen.any { it is AgentEvent.RuntimeBlocked })
                assertTrue(model.requests.all { req -> req.tools.none { it.name == "wait_for_user_input" } })
                assertTrue(model.requests.last().messages.any { it.role == "tool" && it.toolName == "ask_async" && it.content.contains("Do not wait") })
                // Expiry is visible on the live stream, not only in the canonical log.
                val expired = seen.filterIsInstance<AgentEvent.UserInteraction>().single { it.interaction.state == "EXPIRED" }.interaction
                assertEquals("COMPLETED", expired.reason)
                assertTrue(seen.indexOfFirst { it is AgentEvent.UserInteraction && it.interaction.state == "EXPIRED" } < seen.lastIndex)
                assertEquals(PostUserInputResult.RunNotActive, runtime.postUserInput(PostUserInputRequest("run", "late", "answer", "session", "run/ask-1/0")))
            } finally { runtime.close() }
        }
    }

    @Test fun cancellationWithAnUnansweredQuestionDoesNotStartAnotherRequestAndRecordsCancelled() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("async-cancel-log").toFile()
        val model = QuestionModel(holdSecondRound = true)
        val runtime = AgentRuntimeBuilder.create().modelClient(model).sessionEventLogRoot(root).build()
        val job = launch { runtime.run(AgentRequest("run", "session", "plan")).toList() }
        try {
            withTimeout(10_000) { model.secondRoundStarted.await() }
            assertEquals(PostUserInputResult.Accepted, runtime.postUserInput(PostUserInputRequest("run", "early", "Suzhou", "session", "run/ask-1/0")))
            withTimeout(10_000) { job.cancelAndJoin() }
            assertEquals(2, model.calls)
            assertEquals(PostUserInputResult.RunNotActive, runtime.postUserInput(PostUserInputRequest("run", "late", "answer")))
        } finally { job.cancelAndJoin(); runtime.close() }
        val facts = cc.noharry.agent.runtime.eventlog.FileBackedSessionEventLog(root).replay("session", "run")
            .mapNotNull { it.payload as? SessionEventPayload.UserInteraction }
        assertEquals("CANCELLED", facts.single { it.kind == "INPUT" && it.state == "UNCONSUMED" }.reason)
        assertEquals("CANCELLED", facts.single { it.kind == "QUESTION" && it.state == "EXPIRED" }.reason)
        assertEquals(1, facts.count { it.state == "EXPIRED" })
    }

    @Test fun unansweredQuestionExpiresAndCanonicalFactsSurviveClosingAndReopeningTheLog() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("async-question-log").toFile()
        val model = QuestionModel()
        val runtime = AgentRuntimeBuilder.create().modelClient(model).sessionEventLogRoot(root).build()
        try { runtime.run(AgentRequest("run", "session", "plan")).toList() } finally { runtime.close() }
        val reopened = cc.noharry.agent.runtime.eventlog.FileBackedSessionEventLog(root)
        val facts = reopened.replay("session", "run").mapNotNull { it.payload as? SessionEventPayload.UserInteraction }
        assertEquals(listOf("PENDING", "EXPIRED"), facts.filter { it.kind == "QUESTION" }.map { it.state })
        assertEquals(listOf("Suzhou", "Shanghai"), facts.first().options)
        assertEquals("Destination?", facts.first().text)
        assertEquals(2, model.calls)
    }

    private fun runtime(model: ModelClient, core: Boolean): AgentRuntime = if (!core) {
        AgentRuntimeBuilder.create().modelClient(model).build()
    } else KernelRuntimeBuilder().model(model).workspace(object : WorkspacePort {
        override suspend fun execute(toolName: String, argumentsJson: String, context: WorkspaceCallContext) = WorkspaceExecutionResult("""{"ok":true}""")
    }).build()

    private fun assertConsumptionFollowsRequest(records: List<SessionEvent>) {
        val consumed = records.single {
            (it.payload as? SessionEventPayload.UserInteraction)?.let { fact -> fact.kind == "INPUT" && fact.state == "CONSUMED" } == true
        }
        val request = records.last { it.type == SessionEventType.MODEL_REQUEST && it.sequence < consumed.sequence }
        val payload = request.payload as SessionEventPayload.ModelRequest
        assertTrue(payload.messages.toString().contains("Suzhou"))
        assertEquals(1, records.count { (it.payload as? SessionEventPayload.UserInteraction)?.let { fact -> fact.kind == "INPUT" && fact.state == "RECEIVED" } == true })
    }
}

private class QuestionModel(private val holdSecondRound: Boolean = false) : ModelClient {
    override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
    var calls = 0
    val secondRoundStarted = CompletableDeferred<Unit>()
    val releaseSecondRound = CompletableDeferred<Unit>()
    val requests = mutableListOf<ModelRequest>()
    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        requests += request.copy(messages = request.messages.toList())
        calls++
        when (calls) {
            1 -> {
                emit(ModelEvent.ToolCall("ask-1", "ask_async", """{"questions":[{"title":"Destination?","options":["Suzhou","Shanghai"]}]}"""))
                emit(ModelEvent.ToolCall("todo-1", "update_todo", """{"items":[{"text":"check route independently","status":"done"}]}"""))
            }
            else -> {
                secondRoundStarted.complete(Unit)
                if (calls == 2 && holdSecondRound) releaseSecondRound.await()
                emit(ModelEvent.ToolCall("finish-$calls", "finish", """{"response":"done"}"""))
            }
        }
        emit(ModelEvent.Completed)
    }
}

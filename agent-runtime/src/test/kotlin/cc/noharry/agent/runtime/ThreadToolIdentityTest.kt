package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.model.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class ThreadToolIdentityTest {
    @Test fun repeatedProviderCallIdAcrossRoundsKeepsBothToolItems() = runBlocking { readWithRepeatedIds(false) }

    @Test fun repeatedProviderCallIdWithinAReadBatchKeepsArgumentsAndResultsPaired() = runBlocking { readWithRepeatedIds(true) }

    @Test fun repeatedAsyncQuestionCallIdStillCreatesDistinctAnswerableQuestions() = runBlocking {
        val model = RepeatedQuestionModel()
        threadBuilder(model).build().use { runtime ->
            val thread = runtime.startThread()
            val collected = async { thread.runStreamed("plan").toList() }
            withTimeout(5000) { model.waiting.await() }
            val questions = thread.pendingRequests().filterIsInstance<Question>()
            assertEquals(listOf("Destination?", "Budget?"), questions.map { it.text })
            assertEquals(2, questions.map { it.id }.toSet().size)
            assertEquals(SteerResult.ACCEPTED, thread.steer("Suzhou", questions.first().id))
            assertEquals(SteerResult.ACCEPTED, thread.steer("Generous", questions.last().id))
            model.release.complete(Unit)
            val events = withTimeout(5000) { collected.await() }
            assertTurnEvents(events)
            val result = (events.last() as ThreadEvent.TurnCompleted).result
            assertEquals(TurnStatus.COMPLETED, result.status)
            assertTrue(result.items.filterIsInstance<Question>().all { it.status == ItemStatus.COMPLETED })
            val inputs = model.requests.last().messages.filter { it.role == "user" }.map { it.content }
            assertTrue(inputs.any { "Destination?" in it && "Suzhou" in it })
            assertTrue(inputs.any { "Budget?" in it && "Generous" in it })
        }
    }

    private suspend fun readWithRepeatedIds(batched: Boolean) {
        val root = threadRoot()
        val workspace = File(root, "workspace").apply { mkdirs() }
        File(workspace, "first.txt").writeText("first file contents")
        File(workspace, "second.txt").writeText("second file contents")
        val calls = listOf("first", "second").map { name ->
            ModelEvent.ToolCall("provider-reused", "read_file", """{"path":"/$name.txt"}""")
        }
        val rounds = if (batched) listOf(calls + ModelEvent.Completed) else calls.map { listOf(it, ModelEvent.Completed) }
        val model = ThreadScriptModel(rounds + listOf(finishEvents("done")))
        threadBuilder(model, root).build().use { runtime ->
            val events = runtime.startThread().runStreamed("read both files").toList()
            assertTurnEvents(events)
            val result = (events.last() as ThreadEvent.TurnCompleted).result
            assertEquals(TurnStatus.COMPLETED, result.status)
            val tools = result.items.filterIsInstance<ThreadItem.ToolCall>().filter { it.tool == "read_file" }
            assertEquals(2, tools.size)
            assertEquals(2, tools.map { it.id }.toSet().size)
            for ((index, name) in listOf("first", "second").withIndex()) {
                assertEquals(calls[index].argumentsJson, tools[index].arguments)
                assertTrue(tools[index].output.orEmpty().contains("$name file contents"))
                assertEquals(ItemStatus.COMPLETED, tools[index].status)
            }
            if (batched) {
                val messages = model.requests.last().messages.filter { it.toolName == "read_file" }
                assertEquals(2, messages.filter { it.role == "assistant" }.map { it.toolCallId }.toSet().size)
                assertEquals(messages.filter { it.role == "assistant" }.map { it.toolCallId }, messages.filter { it.role == "tool" }.map { it.toolCallId })
            }
        }
    }
}

private class RepeatedQuestionModel : ModelClient {
    override val providerConcurrency = ProviderConcurrencyDeclaration.threadSafe()
    val requests = mutableListOf<ModelRequest>()
    val waiting = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        requests += request.copy(messages = request.messages.toList())
        val question = when (requests.size) { 1 -> "Destination?"; 2 -> "Budget?"; else -> null }
        if (question != null) emit(ModelEvent.ToolCall("question-call", "ask_async", """{"questions":[{"title":"$question"}]}"""))
        else {
            if (requests.size == 3) { waiting.complete(Unit); release.await() }
            emit(ModelEvent.ToolCall("finish", "finish", """{"response":"done"}"""))
        }
        emit(ModelEvent.Completed)
    }
}

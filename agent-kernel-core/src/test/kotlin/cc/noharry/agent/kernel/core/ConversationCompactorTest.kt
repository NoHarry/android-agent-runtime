@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompactorTest {
    private val tools = listOf(ModelTool("finish", "{}"))
    private val context = MaterializerContext(sessionId = "session", runId = "run-3")

    @Test
    fun belowTheThresholdNothingHappens() = runBlocking {
        val model = ScriptedSummary("摘要")
        val history = historyWith(run("一", 400), run("二", 400))

        val result = compactor(model).compactIfNeeded("run-3", history, own("三"), "system", tools, 799, context, LoopSessionRecorder.noop())

        assertNull(result)
        assertTrue(model.requests.isEmpty())
    }

    @Test
    fun theOldestRunsBecomeOneCheckpointAndTheSummaryRequestRepeatsThePrefix() = runBlocking {
        val model = ScriptedSummary("## Requests and intent\n- 空调22度")
        val first = run("一", 400)
        val second = run("二", 400)
        val newest = run("三", 40)
        val history = historyWith(first, second, newest)
        val own = own("四")

        val result = compactor(model).compactIfNeeded("run-3", history, own, "system", tools, 900, context, LoopSessionRecorder.noop())

        // The request is the conversation's own prefix (system, tools, the oldest runs as sent) plus one instruction.
        val request = model.requests.single()
        assertEquals("system", request.system)
        assertEquals(tools, request.tools)
        assertEquals(first + second, request.messages.dropLast(1))
        assertEquals(CONVERSATION_COMPACTION_INSTRUCTION, request.messages.last().content)
        // The newest run stays as it was, after the checkpoint.
        val runs = history.earlierRuns("run-3")
        assertEquals(2, runs.size)
        val checkpoint = runs.first().single().content
        assertTrue(checkpoint.startsWith(CHECKPOINT_PREAMBLE))
        assertTrue(checkpoint.contains("<compacted-summary>\n## Requests and intent\n- 空调22度\n</compacted-summary>"))
        assertEquals(newest, runs.last())
        assertEquals(first.size + second.size, result?.compactedMessageCount)
        // One compaction per run: still over the threshold later in the run, it does not summarize again.
        assertNull(compactor(model).compactIfNeeded("run-3", history, own, "system", tools, 900, context, LoopSessionRecorder.noop()))
        assertEquals(1, model.requests.size)
    }

    @Test
    fun aSummaryThatIsNotSmallerOrFailsChangesNothing() = runBlocking {
        val history = historyWith(run("一", 10), run("二", 10))
        val before = history.earlierRuns("run-3")

        assertNull(compactor(ScriptedSummary("长".repeat(200))).compactIfNeeded("run-3", history, own("三"), "s", tools, 900, context, LoopSessionRecorder.noop()))
        assertNull(compactor(ScriptedSummary(null)).compactIfNeeded("run-3", history, own("三"), "s", tools, 900, context, LoopSessionRecorder.noop()))
        assertEquals(before, history.earlierRuns("run-3"))
    }

    @Test
    fun aConversationThatIsOnlyACheckpointIsNotSummarizedAgain() = runBlocking {
        val checkpoint = listOf(ModelMessage(role = "user", content = "$CHECKPOINT_PREAMBLE\n\n<compacted-summary>\nx\n</compacted-summary>"))
        val model = ScriptedSummary("y")

        assertNull(compactor(model).compactIfNeeded("run-3", historyWith(checkpoint), own("三"), "s", tools, 900, context, LoopSessionRecorder.noop()))
        assertTrue(model.requests.isEmpty())
    }

    private fun compactor(model: ModelClient) = ConversationCompactor(model, thresholdTokens = 800, retainTokens = 160) { 1L }

    private fun historyWith(vararg runs: List<ModelMessage>): SessionHistoryStore {
        val store = SessionHistoryStore()
        runs.forEachIndexed { index, run ->
            store.start("earlier-$index", "session")
            store.record("earlier-$index", run)
        }
        store.start("run-3", "session")
        return store
    }

    private fun run(text: String, repeat: Int) = listOf(
        ModelMessage(role = "user", content = text.repeat(repeat)),
        ModelMessage(role = "assistant", content = "好的"),
    )

    private fun own(text: String) = listOf(ModelMessage(role = "user", content = text))
}

private class ScriptedSummary(private val text: String?) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.serialized()
    val requests = mutableListOf<ModelRequest>()

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        requests += request
        if (text == null) error("provider failed")
        emit(ModelEvent.TextDelta(text))
        emit(ModelEvent.Completed)
    }
}

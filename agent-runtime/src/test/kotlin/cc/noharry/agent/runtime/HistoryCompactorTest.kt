@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.core.context.*
import cc.noharry.agent.kernel.core.loop.*
import cc.noharry.agent.kernel.core.memory.*
import cc.noharry.agent.kernel.core.plugin.*
import cc.noharry.agent.kernel.core.state.*
import cc.noharry.agent.kernel.core.tools.*
import cc.noharry.agent.kernel.core.trace.*
import cc.noharry.agent.runtime.run.RuntimeSessionRecorder
import cc.noharry.agent.runtime.workspace.VirtualWorkspace
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCompactorTest {
    @Test fun summaryInputKeepsArgumentsAndTypedResultBehindVerboseRawContent() = runBlocking {
        val arguments = """{"destination":{"name":"chosen","poiId":"poi-1","lon":12.3,"lat":45.6}}"""
        val raw = typedResultWithLargeContent("OK", false)
        val call = ModelMessage("assistant", "", toolCallId = "call-1", toolName = "mcp__provider__plan", toolArgumentsJson = arguments)
        val result = ModelMessage(role = "tool", content = raw, toolCallId = "call-1", toolName = "mcp__provider__plan")
        val model = SummaryModel("already planned")
        HistoryCompactor(model, budgetBytes = 1_000, minBatchMessages = 1).compact(
            listOf(ModelMessage("user", "keep the chosen destination"), call, result),
        )
        val source = requireNotNull(model.lastRequest).messages.single().content
        assertTrue(source.contains(arguments))
        assertTrue(source.contains("\"name\":\"OK\""))
        assertTrue(source.contains("\"poiId\":\"poi-1\""))
        assertFalse(source.contains("VERBOSE_RAW_CONTENT"))
        assertEquals(raw, result.content)
    }

    @Test fun fallbackRetainsArgumentsAndNonretryableBusinessRefusal() = runBlocking {
        val args = """{"action":"add"}"""
        val messages = listOf(ModelMessage("user", "request"),
            ModelMessage("assistant", "", toolCallId = "call-1", toolName = "mcp__provider__edit", toolArgumentsJson = args),
            ModelMessage(role = "tool", content = typedResultWithLargeContent("CONFLICT", true),
                toolCallId = "call-1", toolName = "mcp__provider__edit"))
        val result = HistoryCompactor(null, budgetBytes = 1_000, minBatchMessages = 1).compact(messages)
            as CompactionResult.Compacted
        val summary = result.messages.last().content
        assertTrue(summary.contains(args))
        assertTrue(summary.contains("CONFLICT"))
        assertTrue(summary.contains("\"retryable\":false"))
    }

    @Test fun compactionRequestAndStreamUseTheCanonicalRecorderWhenAvailable() = runBlocking {
        val requests = mutableListOf<ModelRequest>()
        val events = mutableListOf<ModelEvent>()
        val calls = mutableListOf<StoredModelCall>()
        val recorder = object : LoopSessionRecorder by LoopSessionRecorder.noop() {
            override fun recordModelRequest(context: MaterializerContext, request: ModelRequest) { requests += request }
            override fun recordModelStreamEvent(context: MaterializerContext, event: ModelEvent) { events += event }
            override fun recordModelCall(call: StoredModelCall) { calls += call }
        }
        val model = SummaryModel("summary")
        HistoryCompactor(model, budgetBytes = 1_000, minBatchMessages = 1).compact(
            listOf(ModelMessage("user", "request")) + toolMessages(4, 600),
            MaterializerContext("s", "r", "q"), recorder,
        )
        assertEquals(listOf(model.lastRequest), requests)
        assertEquals(listOf(ModelEvent.TextDelta("summary"), ModelEvent.Completed), events)
        assertEquals(SESSION_MODEL_CALL_SOURCE_COMPACTION, calls.single().source)
    }

    private fun typedResultWithLargeContent(status: String, isError: Boolean): String = buildJsonObject {
        put("result", buildJsonObject {
            put("content", "VERBOSE_RAW_CONTENT".repeat(1_000))
            put("isError", isError)
            put("structuredContent", buildJsonObject {
                put("data", buildJsonObject { put("poiId", "poi-1"); put("routeCount", 1) })
                put("status", buildJsonObject { put("name", status); put("retryable", false) })
            })
        })
    }.toString()

    @Test
    fun unchangedWhenHistoryIsWithinBudget() = runBlocking {
        val messages = listOf(
            ModelMessage(role = "user", content = "set AC to 22"),
            ModelMessage(role = "tool", toolName = "list_dir", content = "catalog"),
        )

        val result = HistoryCompactor(modelClient = null, budgetBytes = 10_000).compact(messages)

        assertEquals(messages, (result as CompactionResult.Unchanged).messages)
    }

    @Test
    fun configuredWindowWaitsForEightyPercentEvenWhenLegacyBytesAreExceeded() = runBlocking {
        val model = SummaryModel("summary")
        val messages = listOf(ModelMessage("user", "retain intent")) + toolMessages(4, 160_000)
        val compactor = HistoryCompactor(model, inputTokenThreshold = 800_000)
        for (input in listOf(null, 125_186, 799_999)) {
            assertEquals(messages, (compactor.compact(messages, latestUsageTokens = input) as CompactionResult.Unchanged).messages)
        }
        assertEquals(0, model.requestCount)
        assertTrue(compactor.compact(messages, latestUsageTokens = 800_000) is CompactionResult.Compacted)
    }

    @Test
    fun compactsWhenProviderUsageExceedsThresholdBeforeByteBudget() = runBlocking {
        val firstUser = ModelMessage(role = "user", content = "summarize compactly")
        val messages = listOf(firstUser) + toolMessages(count = 6, bytes = 40)
        val compactor = HistoryCompactor(
            modelClient = SummaryModel("usage summary"),
            budgetBytes = 100_000,
            minBatchMessages = 1,
            usageBudgetTokens = 100,
            usageThresholdRatio = 0.5,
        )

        val result = compactor.compact(messages, latestUsageTokens = 60) as CompactionResult.Compacted

        assertTrue(result.messages.contains(firstUser))
        assertTrue(result.messages.any { it.content.contains("usage summary") })
    }

    @Test
    fun usageBelowThresholdKeepsByteBudgetFallbackBehavior() = runBlocking {
        val messages = listOf(ModelMessage(role = "user", content = "small history")) +
            toolMessages(count = 2, bytes = 20)
        val compactor = HistoryCompactor(
            modelClient = SummaryModel("should not run"),
            budgetBytes = 100_000,
            minBatchMessages = 1,
            usageBudgetTokens = 100,
            usageThresholdRatio = 0.5,
        )

        val result = compactor.compact(messages, latestUsageTokens = 40)

        assertEquals(messages, (result as CompactionResult.Unchanged).messages)
    }

    @Test
    fun alreadyFoldedResultsStayByteIdenticalAcrossLaterCompactions() = runBlocking {
        val model = SummaryModel("summary")
        val compactor = HistoryCompactor(model, budgetBytes = 2_000, minBatchMessages = 1)
        val oldResult = ModelMessage(role = "tool", toolCallId = "call-1", toolName = "search_tools", content = "old-${"x".repeat(3_000)}")
        val first = listOf(
            ModelMessage(role = "user", content = "inspect then continue"),
            assistantToolCall("call-1", "search_tools"), oldResult,
            assistantToolCall("call-2", "read_file"),
            ModelMessage(role = "tool", toolCallId = "call-2", toolName = "read_file", content = "mid-${"y".repeat(1_500)}"),
        )
        val once = (compactor.compact(first) as CompactionResult.Compacted).messages
        val folded = once.single { it.toolCallId == "call-1" && it.role == "tool" }
        assertEquals(1, folded.content.split("foldedToolResult").size - 1)

        // A later round appends a new read-only result; call-2 now ages out and folds, call-1 must not be rewritten.
        val later = once + assistantToolCall("call-3", "read_file") +
            ModelMessage(role = "tool", toolCallId = "call-3", toolName = "read_file", content = "recent-${"z".repeat(500)}")
        val twice = (compactor.compact(later) as CompactionResult.Compacted)
        assertEquals(1, twice.droppedCount)
        assertEquals(folded, twice.messages.single { it.toolCallId == "call-1" && it.role == "tool" })
        assertTrue(twice.messages.all { it.content.split("foldedToolResult").size <= 2 })
        assertEquals(0, model.requestCount)
    }

    @Test
    fun foldsOldReadOnlyToolResultsBeforeLlmSummary() = runBlocking {
        val model = SummaryModel("should not run")
        val firstUser = ModelMessage(role = "user", content = "inspect then continue")
        val firstCall = assistantToolCall("call-1", "read_file")
        val firstResult = ModelMessage(
            role = "tool",
            toolCallId = "call-1",
            toolName = "read_file",
            content = "old-result-${"x".repeat(3_000)}",
        )
        val recentCall = assistantToolCall("call-2", "read_file")
        val recentResult = ModelMessage(
            role = "tool",
            toolCallId = "call-2",
            toolName = "read_file",
            content = "recent-state",
        )

        val result = HistoryCompactor(model, budgetBytes = 2_000, minBatchMessages = 1)
            .compact(listOf(firstUser, firstCall, firstResult, recentCall, recentResult)) as CompactionResult.Compacted

        assertEquals(0, model.requestCount)
        assertTrue(result.messages.contains(firstCall))
        assertTrue(result.messages.contains(recentCall))
        assertTrue(result.messages.any { it.toolCallId == "call-1" && it.content.contains("foldedToolResult") })
        assertTrue(result.messages.any { it.toolCallId == "call-2" && it.content == "recent-state" })
    }

    @Test
    fun nativeLoopPassesProviderUsageIntoCompactor() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val root = Files.createTempDirectory("usage-compaction-").toFile()
        root.resolve("note.txt").writeText("large context source")
        val loop = NativeToolLoop(
            modelClient = FakeModelClient(
                eventsByRound = listOf(
                    listOf(
                        ModelEvent.UsageDelta(inputTokens = 60),
                        ModelEvent.ToolCall("read-1", "read_file", """{"path":"/note.txt"}"""),
                        ModelEvent.Completed,
                    ),
                    listOf(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""), ModelEvent.Completed),
                ),
                seenRequests = seenRequests,
            ),
            mcpRegistry = ToolProviderRegistry(),
            workspace = VirtualWorkspace(root),
            historyCompactor = HistoryCompactor(
                modelClient = SummaryModel("usage summary"),
                budgetBytes = 100_000,
                minBatchMessages = 1,
                usageBudgetTokens = 100,
                usageThresholdRatio = 0.5,
            ),
        )

        val events = loop.run(agentRequest(), "request-1").events()

        val secondRequest = seenRequests[1].messages.joinToString("\n") { it.content }
        assertTrue(secondRequest.contains("Previous context summary"))
        assertTrue(secondRequest.contains("usage summary"))
        val compaction = events.filterIsInstance<AgentEvent.CompactionApplied>().single()
        assertEquals("request-1", compaction.runId)
        assertTrue(compaction.beforeMessageCount > compaction.afterMessageCount)
        assertEquals("usage summary".toByteArray(Charsets.UTF_8).size, compaction.summaryBytes)
    }

    @Test
    fun compactsOverBudgetHistoryWithBoundedLlmSummaryAndKeepsFirstUser() = runBlocking {
        val firstUser = ModelMessage(role = "user", content = "turn on AC then raise fan")
        val messages = listOf(firstUser) + toolMessages(count = 8, bytes = 600)
        val compactor = HistoryCompactor(SummaryModel("summary-${"x".repeat(3_000)}"), budgetBytes = 2_000)

        val result = compactor.compact(messages) as CompactionResult.Compacted

        assertTrue(result.messages.contains(firstUser))
        assertTrue(result.summaryBytes <= 2048)
        assertTrue(result.messages.any { it.role == "user" && it.content.contains("summary-") })
    }

    @Test
    fun foldsExplorationResultsWhenThatIsEnoughToFitBudget() = runBlocking {
        val firstUser = ModelMessage(role = "user", content = "inspect context")
        val droppable = ModelMessage(role = "tool", toolName = "list_dir", content = "x".repeat(4_000))
        val important = ModelMessage(role = "tool", toolName = "read_file", content = "state=22")

        val result = HistoryCompactor(modelClient = null, budgetBytes = 500)
            .compact(listOf(firstUser, droppable, important)) as CompactionResult.Compacted

        assertTrue(result.messages.contains(firstUser))
        assertTrue(result.messages.contains(important))
        assertTrue(result.messages.any { it.toolName == "list_dir" && it.content.contains("foldedToolResult") })
    }

    @Test
    fun fallsBackToRuleBasedSummaryWhenModelFails() = runBlocking {
        val firstUser = ModelMessage(role = "user", content = "preserve intent")
        val messages = listOf(firstUser) + toolMessages(count = 6, bytes = 500)

        val result = HistoryCompactor(FailingModel(), budgetBytes = 1_600).compact(messages)
            as CompactionResult.Compacted

        assertTrue(result.messages.contains(firstUser))
        assertTrue(result.messages.any { it.content.contains("tool=read_file") })
    }

    @Test
    fun llmSummaryRequestUsesProviderSafeUserMessage() = runBlocking {
        val firstUser = ModelMessage(role = "user", content = "complex navigation request")
        val assistantTool = ModelMessage(
            role = "assistant",
            content = "thinking",
            toolCallId = "call-1",
            toolName = "read_file",
            toolArgumentsJson = """{"path":"/mcp/tools.json"}""",
        )
        val toolResult = ModelMessage(
            role = "tool",
            content = "catalog-${"x".repeat(20_000)}",
            toolCallId = "call-1",
            toolName = "read_file",
        )
        val model = SummaryModel("summary")

        val result = HistoryCompactor(model, budgetBytes = 1_000, minBatchMessages = 1)
            .compact(listOf(firstUser, assistantTool, toolResult)) as CompactionResult.Compacted

        val summaryRequest = requireNotNull(model.lastRequest)
        assertEquals(listOf("user"), summaryRequest.messages.map { it.role })
        assertTrue(summaryRequest.messages.single().content.length <= 24_000)
        assertFalse(summaryRequest.messages.single().content.contains("\"x\".repeat"))
        assertTrue(result.messages.contains(firstUser))
        assertFalse(result.messages.any { it.toolCallId == "call-1" })
        assertTrue(result.messages.any { it.content.contains("Previous context summary") })
    }

    @Test
    fun recordsCompactionModelCallWhenLlmSummaryRuns() = runBlocking {
        val store = CompactionProbeStore()
        val recorder = RuntimeSessionRecorder(store) { 100 }
        val context = MaterializerContext("session-1", "run-1", "request-1")
        val messages = listOf(ModelMessage(role = "user", content = "complex navigation")) +
            toolMessages(count = 6, bytes = 500)

        HistoryCompactor(SummaryModel("summary"), budgetBytes = 1_600)
            .compact(messages, context, recorder) as CompactionResult.Compacted

        val call = store.modelCalls.single()
        assertEquals("run-1:model-1", call.callId)
        assertEquals(SESSION_MODEL_CALL_SOURCE_COMPACTION, call.source)
        assertEquals(SESSION_MODEL_CALL_STATUS_COMPLETED, call.status)
        assertEquals(1, call.messageCount)
        assertEquals(0, call.toolCount)
        assertEquals(2, call.eventCount)
        assertEquals(1, call.textEventCount)
    }

    @Test
    fun droppingExplorationResultsAlsoDropsOrphanAssistantToolCall() = runBlocking {
        val firstUser = ModelMessage(role = "user", content = "inspect context")
        val assistantTool = ModelMessage(
            role = "assistant",
            content = "",
            toolCallId = "list-call",
            toolName = "list_dir",
            toolArgumentsJson = """{"path":"/mcp"}""",
        )
        val droppable = ModelMessage(
            role = "tool",
            toolCallId = "list-call",
            toolName = "list_dir",
            content = "x".repeat(4_000),
        )
        val important = ModelMessage(role = "user", content = "continue with compact context")

        val result = HistoryCompactor(modelClient = null, budgetBytes = 500)
            .compact(listOf(firstUser, assistantTool, droppable, important)) as CompactionResult.Unchanged

        assertTrue(result.messages.contains(firstUser))
        assertTrue(result.messages.contains(important))
        assertFalse(result.messages.any { it.toolCallId == "list-call" })
    }

    private fun toolMessages(count: Int, bytes: Int): List<ModelMessage> =
        (0 until count).map {
            ModelMessage(role = "tool", toolName = "read_file", content = "tool-$it-${"x".repeat(bytes)}")
        }

    private fun assistantToolCall(callId: String, toolName: String): ModelMessage =
        ModelMessage(
            role = "assistant",
            content = "",
            toolCallId = callId,
            toolName = toolName,
            toolArgumentsJson = """{"path":"/context.txt"}""",
        )
}

private fun NativeLoopOutcome.events(): List<AgentEvent> =
    when (this) {
        is NativeLoopOutcome.Terminal -> events
        is NativeLoopOutcome.RequiresConfirmation -> events
        is NativeLoopOutcome.NeedsUserInput -> events
    }

private class SummaryModel(private val text: String) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    var lastRequest: ModelRequest? = null
        private set
    var requestCount: Int = 0
        private set

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        requestCount += 1
        lastRequest = request
        emit(ModelEvent.TextDelta(text))
        emit(ModelEvent.Completed)
    }
}

private class FailingModel : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        throw IllegalStateException("summary failed")
    }
}

private class CompactionProbeStore : SessionStore {
    val modelCalls = mutableListOf<StoredModelCall>()

    override fun upsertSession(session: StoredSession) = Unit
    override fun upsertRun(run: StoredRun) = Unit
    override fun updateRunStatus(update: StoredRunStatusUpdate) = Unit
    override fun appendMessage(message: StoredSessionMessage) = Unit
    override fun recordToolStarted(event: StoredToolEvent) = Unit
    override fun recordToolFinished(result: StoredToolResult) = Unit
    override fun appendUsage(usage: StoredUsageDelta) = Unit
    override fun appendVfsOperation(operation: StoredVfsOperation) = Unit
    override fun appendModelCall(call: StoredModelCall) { modelCalls += call }
    override fun listSessions(query: SessionQuery): List<StoredSession> = emptyList()
    override fun transcript(sessionId: String, range: TranscriptRange): TranscriptPage = TranscriptPage(sessionId, emptyList())
    override fun toolEvents(runId: String): List<StoredToolEvent> = emptyList()
    override fun usage(sessionId: String): StoredUsageSummary = StoredUsageSummary(0, 0, 0, 0, 0, 0)
    override fun vfsOperations(runId: String): List<StoredVfsOperation> = emptyList()
    override fun modelCalls(runId: String): List<StoredModelCall> = modelCalls.filter { it.runId == runId }
}

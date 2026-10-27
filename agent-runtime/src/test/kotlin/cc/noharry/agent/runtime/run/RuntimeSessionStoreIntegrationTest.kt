@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.run
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.WorkspaceExecutionTelemetry
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRef
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.state.SESSION_MODEL_CALL_STATUS_COMPLETED
import cc.noharry.agent.kernel.core.state.SESSION_TOOL_STATUS_COMPLETED
import cc.noharry.agent.kernel.core.state.SESSION_TOOL_STATUS_FAILED
import cc.noharry.agent.kernel.core.state.SESSION_TOOL_STATUS_STARTED
import cc.noharry.agent.kernel.core.state.SESSION_VFS_STATUS_OK
import cc.noharry.agent.kernel.core.state.SessionQuery
import cc.noharry.agent.kernel.core.state.SessionStore
import cc.noharry.agent.kernel.core.state.StoredModelCall
import cc.noharry.agent.kernel.core.state.StoredRun
import cc.noharry.agent.kernel.core.state.StoredRunStatusUpdate
import cc.noharry.agent.kernel.core.state.StoredSession
import cc.noharry.agent.kernel.core.state.StoredSessionMessage
import cc.noharry.agent.kernel.core.state.StoredToolEvent
import cc.noharry.agent.kernel.core.state.StoredToolResult
import cc.noharry.agent.kernel.core.state.StoredUsageDelta
import cc.noharry.agent.kernel.core.state.StoredUsageSummary
import cc.noharry.agent.kernel.core.state.StoredVfsOperation
import cc.noharry.agent.kernel.core.state.TranscriptPage
import cc.noharry.agent.kernel.core.state.TranscriptRange
import cc.noharry.agent.kernel.core.state.VfsOperationTimings
import cc.noharry.agent.kernel.core.tools.DynamicToolSource
import cc.noharry.agent.kernel.core.tools.MCP_TOOL_IS_ERROR_CODE
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.kernel.core.tools.ToolProviderRegistry
import cc.noharry.agent.kernel.core.trace.DefaultTraceRedactor
import cc.noharry.agent.kernel.core.trace.RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_MISSES
import cc.noharry.agent.kernel.core.trace.RUNTIME_COUNTER_OVERLAY_ATTACHES
import cc.noharry.agent.kernel.core.trace.RuntimeVfsPerformanceCounters
import cc.noharry.agent.kernel.core.trace.TraceVisibilityConfig
import cc.noharry.agent.runtime.DefaultAgentRuntime
import cc.noharry.agent.runtime.FakeModelClient
import cc.noharry.agent.runtime.RuntimeWorkspaceRef
import cc.noharry.agent.runtime.RuntimeWorkspaceRegistry
import cc.noharry.agent.runtime.agentRequest
import cc.noharry.agent.runtime.runtime
import cc.noharry.agent.vfs.VfsFileSystem
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSessionStoreIntegrationTest {
    @Test
    fun runtimePersistsTranscriptToolAndVfsEvidence() = runBlocking {
        val workspace = Files.createTempDirectory("session-store-runtime-").toFile()
        File(workspace, "note.txt").writeText("workspace content")
        val store = RecordingSessionStore()
        val runtime = sessionStoreRuntime(workspace, store)

        val events = runtime.run(agentRequest().copy(modelRef = ModelRef("openai", "gpt-5", "high"))).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals(RuntimeRunStatus.COMPLETED.name, store.runStatus("request-1"))
        assertEquals("openai", store.run("request-1")?.providerId)
        assertEquals("gpt-5", store.run("request-1")?.modelId)
        assertEquals("high", store.run("request-1")?.variant)
        assertEquals(listOf("user", "assistant", "tool", "assistant"), store.messages.map { it.role })
        assertEquals(listOf("read_file", "finish"), store.tools.map { it.toolName })
        assertEquals(1, events.filterIsInstance<AgentEvent.TokenUsageDelta>().size)
        assertEquals(StoredUsageDelta("request-1", "session-1", 10, 3, 2, 4, 1, 100), store.usages.single())
        assertEquals(listOf("request-1:model-1", "request-1:model-2"), store.modelCalls.map { it.callId })
        assertEquals(listOf(3, 2), store.modelCalls.map { it.eventCount })
        assertEquals(1, store.modelCalls.first().usageEventCount)
        assertEquals(1, store.modelCalls.first().toolCallEventCount)
        assertTrue(store.vfsOps.any { it.path == "/note.txt" && it.status == SESSION_VFS_STATUS_OK })
    }

    @Test
    fun usageThatClosesTheFinishingStreamIsStillRecorded() = runBlocking {
        val workspace = Files.createTempDirectory("session-store-finish-usage-").toFile()
        val store = RecordingSessionStore()
        val opening = ModelEvent.UsageDelta(inputTokens = 1000)
        val closing = ModelEvent.UsageDelta(inputTokens = 5, outputTokens = 40, cacheReadTokens = 900)
        val runtime = sessionStoreRuntime(
            workspace,
            store,
            listOf(listOf(opening, ModelEvent.ToolCall("tool-1", "finish", """{"response":"done"}"""), closing, ModelEvent.Completed)),
        )

        val events = runtime.run(agentRequest()).toList()

        assertEquals(RuntimeRunStatus.COMPLETED, events.filterIsInstance<AgentEvent.ResultAvailable>().single().result.status)
        // Providers send the frame with output tokens and cache reads after the tool call that ends the run.
        // The log keeps both frames; hosts get the call's sum as one event, so one event is one request.
        assertEquals(listOf(0L, 900L), store.usages.map { it.cacheReadTokens.toLong() })
        val usage = events.filterIsInstance<AgentEvent.TokenUsageDelta>().single()
        assertEquals(listOf(1005, 40, 900), listOf(usage.inputTokens, usage.outputTokens, usage.cacheReadTokens))
    }

    @Test
    fun recorderScopesRepeatedModelToolIdsByRun() {
        val store = RecordingSessionStore()
        val recorder = RuntimeSessionRecorder(store) { 1 }
        val event = ModelEvent.ToolCall("tool-1", "finish", """{"response":"done"}""")

        recorder.recordToolStarted(MaterializerContext("session-1", "run-1", "request-1"), event, DynamicToolSource.TERMINAL)
        recorder.recordToolStarted(MaterializerContext("session-2", "run-2", "request-2"), event, DynamicToolSource.TERMINAL)

        assertEquals(2, store.tools.size)
        assertEquals(listOf("run-1:tool-1", "run-2:tool-1"), store.tools.map { it.callId })
    }

    @Test
    fun recorderDefersToolFinishUntilFlushAndUsesBoundedLargeResultHash() {
        val store = RecordingSessionStore()
        val recorder = RuntimeSessionRecorder(store) { 100 }
        val context = MaterializerContext("session-1", "run-1", "request-1")
        val event = ModelEvent.ToolCall("tool-1", "read_file", """{"path":"/large.txt"}""")
        val largeResult = """{"ok":true,"content":"${"a".repeat(12_000)}"}"""

        recorder.recordToolStarted(context, event, DynamicToolSource.NATIVE_READ)
        recorder.recordToolFinished(context, event, DynamicToolSource.NATIVE_READ, largeResult)

        assertEquals(SESSION_TOOL_STATUS_STARTED, store.tools.single().status)
        assertTrue(store.vfsOps.isEmpty())

        recorder.recordUsage(context, ModelEvent.UsageDelta(inputTokens = 1))

        assertEquals(SESSION_TOOL_STATUS_COMPLETED, store.tools.single().status)
        assertTrue(store.tools.single().resultHash.startsWith("sha256-prefix:8192:"))
        assertTrue(store.vfsOps.any { it.path == "/large.txt" && it.status == SESSION_VFS_STATUS_OK })
    }

    @Test
    fun recorderUsesMcpErrorSemanticsOnlyForBackendResults() {
        val store = RecordingSessionStore()
        val recorder = RuntimeSessionRecorder(store) { 100 }
        val context = MaterializerContext("session-1", "run-1", "request-1")
        val backend = ModelEvent.ToolCall("tool-1", "mcp__device__open_ac", "{}")
        val terminal = ModelEvent.ToolCall("tool-2", "finish", "{}")
        val result = """{"jsonrpc":"2.0","result":{"content":[],"isError":false},"isError":true}"""

        recorder.recordToolStarted(context, backend, DynamicToolSource.BACKEND)
        recorder.recordToolFinished(context, backend, DynamicToolSource.BACKEND, result)
        recorder.recordToolStarted(context, terminal, DynamicToolSource.TERMINAL)
        recorder.recordToolFinished(context, terminal, DynamicToolSource.TERMINAL, result)
        recorder.updateRunStatus("run-1", RuntimeRunStatus.COMPLETED)

        val backendRecord = store.tools.single { it.callId == "run-1:tool-1" }
        assertEquals(SESSION_TOOL_STATUS_FAILED, backendRecord.status)
        assertEquals(false, backendRecord.ok)
        assertEquals(MCP_TOOL_IS_ERROR_CODE, backendRecord.code)
        val terminalRecord = store.tools.single { it.callId == "run-1:tool-2" }
        assertEquals(SESSION_TOOL_STATUS_COMPLETED, terminalRecord.status)
        assertEquals(true, terminalRecord.ok)
        assertNull(terminalRecord.code)
    }

    @Test
    fun recorderBatchesMultipleToolFinishesAcrossSubsequentStarts() {
        val store = RecordingSessionStore()
        val recorder = RuntimeSessionRecorder(store) { 100 }
        val context = MaterializerContext("session-1", "run-1", "request-1")
        val first = ModelEvent.ToolCall("tool-1", "read_file", """{"path":"/one.txt"}""")
        val second = ModelEvent.ToolCall("tool-2", "read_file", """{"path":"/two.txt"}""")

        recorder.recordToolStarted(context, first, DynamicToolSource.NATIVE_READ)
        recorder.recordToolFinished(context, first, DynamicToolSource.NATIVE_READ, """{"ok":true,"content":"one"}""")
        recorder.recordToolStarted(context, second, DynamicToolSource.NATIVE_READ)
        recorder.recordToolFinished(context, second, DynamicToolSource.NATIVE_READ, """{"ok":true,"content":"two"}""")

        assertEquals(0, store.finishWrites)
        assertEquals(
            listOf(SESSION_TOOL_STATUS_STARTED, SESSION_TOOL_STATUS_STARTED),
            store.tools.map { it.status },
        )

        recorder.updateRunStatus("run-1", RuntimeRunStatus.COMPLETED)

        assertEquals(2, store.finishWrites)
        assertEquals(
            listOf(SESSION_TOOL_STATUS_COMPLETED, SESSION_TOOL_STATUS_COMPLETED),
            store.tools.map { it.status },
        )
        assertEquals(listOf("/one.txt", "/two.txt"), store.vfsOps.map { it.path })
    }

    @Test
    fun recorderStoresRunScopedVfsCounterDeltasOnVfsOps() {
        RuntimeVfsPerformanceCounters.reset()
        try {
            val store = RecordingSessionStore()
            val recorder = RuntimeSessionRecorder(store) { 100 }
            RuntimeVfsPerformanceCounters.increment(RUNTIME_COUNTER_OVERLAY_ATTACHES)
            recorder.startRun(agentRequest(), "run-1")
            RuntimeVfsPerformanceCounters.increment(RUNTIME_COUNTER_OVERLAY_ATTACHES, amount = 2)
            RuntimeVfsPerformanceCounters.increment(RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_MISSES)
            val context = MaterializerContext("session-1", "run-1", "request-1")
            val event = ModelEvent.ToolCall("tool-1", "read_file", """{"path":"/mcp/tools.json"}""")

            recorder.recordToolStarted(context, event, DynamicToolSource.NATIVE_READ)
            recorder.recordToolFinished(context, event, DynamicToolSource.NATIVE_READ, """{"ok":true,"content":"tools"}""")
            recorder.recordUsage(context, ModelEvent.UsageDelta(inputTokens = 1))

            val counters = store.vfsOps.single().vfsCounters
            assertEquals(2L, counters[RUNTIME_COUNTER_OVERLAY_ATTACHES])
            assertEquals(1L, counters[RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_MISSES])
        } finally {
            RuntimeVfsPerformanceCounters.reset()
        }
    }

    @Test
    fun recorderOnlyStoresSchemaCacheHitForSchemaPaths() {
        val store = RecordingSessionStore()
        val recorder = RuntimeSessionRecorder(store) { 100 }
        val context = MaterializerContext("session-1", "run-1", "request-1")
        val catalog = ModelEvent.ToolCall("tool-1", "read_file", """{"path":"/mcp/servers.json"}""")
        val schema = ModelEvent.ToolCall(
            "tool-2",
            "read_file",
            """{"path":"/mcp/tools/mcp__device__command_navi_KeyWordSearch/schema.json"}""",
        )

        recorder.recordToolFinished(
            context,
            catalog,
            DynamicToolSource.NATIVE_READ,
            """{"ok":true,"content":"servers"}""",
            WorkspaceExecutionTelemetry(schemaCacheHit = false),
        )
        recorder.recordToolFinished(
            context,
            schema,
            DynamicToolSource.NATIVE_READ,
            """{"ok":true,"content":"schema"}""",
            WorkspaceExecutionTelemetry(schemaCacheHit = true),
        )
        recorder.updateRunStatus("run-1", RuntimeRunStatus.COMPLETED)

        assertNull(store.vfsOps.single { it.path == "/mcp/servers.json" }.schemaCacheHit)
        assertEquals(true, store.vfsOps.single { it.path.endsWith("/schema.json") }.schemaCacheHit)
    }

    @Test
    fun recorderStoresSearchToolsProbeTimings() {
        val store = RecordingSessionStore()
        val recorder = RuntimeSessionRecorder(store) { 100 }
        val context = MaterializerContext("session-1", "run-1", "request-1")
        val event = ModelEvent.ToolCall("tool-1", "search_tools", """{"query":"parking poi"}""")

        recorder.recordToolFinished(
            context,
            event,
            DynamicToolSource.NATIVE_READ,
            """{"ok":true,"matches":[]}""",
            WorkspaceExecutionTelemetry(
                resolveDurationMs = 1,
                scanDurationMs = 2,
                serializeDurationMs = 3,
                executeDurationMs = 6,
                batchResultWaitDurationMs = 4,
            ),
        )
        recorder.updateRunStatus("run-1", RuntimeRunStatus.COMPLETED)

        val op = store.vfsOps.single()
        assertEquals("search_tools", op.op)
        assertEquals("/mcp/search_tools", op.path)
        assertEquals(2, op.timings.scanMs)
        assertEquals(3, op.timings.serializeMs)
        assertEquals(6, op.timings.executeMs)
        assertNull(op.schemaCacheHit)
    }

}

private fun sessionStoreRuntime(
    workspace: File,
    store: SessionStore,
    eventsByRound: List<List<ModelEvent>> = listOf(
        listOf(
            ModelEvent.UsageDelta(
                inputTokens = 10,
                outputTokens = 3,
                reasoningTokens = 2,
                cacheReadTokens = 4,
                cacheWriteTokens = 1,
            ),
            ModelEvent.ToolCall("tool-1", "read_file", """{"path":"/note.txt"}"""),
            ModelEvent.Completed,
        ),
        listOf(ModelEvent.ToolCall("tool-2", "finish", """{"response":"done"}"""), ModelEvent.Completed),
    ),
): DefaultAgentRuntime =
    DefaultAgentRuntime(
        modelClient = FakeModelClient(eventsByRound = eventsByRound),
        mcpRegistry = ToolProviderRegistry(),
        workspaceRegistry = RuntimeWorkspaceRegistry(
            listOf(RuntimeWorkspaceRef("default", VfsFileSystem.singleFileRoot(workspace))),
            "default",
        ),
        confirmationTemplate = null,
        protocolRequestTemplate = null,
        nowEpochMillis = { 100 },
        hookChain = RuntimeHookChain.empty(),
        traceVisibilityConfig = TraceVisibilityConfig(),
        traceRedactor = DefaultTraceRedactor,
        sessionStore = store,
    )

private class RecordingSessionStore : SessionStore {
    private val runs = linkedMapOf<String, StoredRun>()
    private val toolMap = linkedMapOf<String, StoredToolEvent>()
    val messages = mutableListOf<StoredSessionMessage>()
    val usages = mutableListOf<StoredUsageDelta>()
    val modelCalls = mutableListOf<StoredModelCall>()
    var finishWrites = 0
    val tools: List<StoredToolEvent>
        get() = toolMap.values.toList()
    val vfsOps = mutableListOf<StoredVfsOperation>()

    override fun upsertSession(session: StoredSession) = Unit
    override fun upsertRun(run: StoredRun) { runs[run.runId] = run }
    override fun updateRunStatus(update: StoredRunStatusUpdate) {
        runs[update.runId]?.let { runs[update.runId] = it.copy(status = update.status) }
    }
    override fun appendMessage(message: StoredSessionMessage) { messages += message }
    override fun recordToolStarted(event: StoredToolEvent) { toolMap[event.callId] = event }
    override fun recordToolFinished(result: StoredToolResult) {
        finishWrites += 1
        toolMap[result.callId]?.let {
            toolMap[result.callId] = it.copy(
                status = result.status,
                finishedAtEpochMillis = result.finishedAtEpochMillis,
                resultJson = result.resultJson,
                resultHash = result.resultHash,
                ok = result.ok,
                code = result.code,
            )
        }
    }
    override fun appendUsage(usage: StoredUsageDelta) { usages += usage }
    override fun appendVfsOperation(operation: StoredVfsOperation) { vfsOps += operation }
    override fun appendModelCall(call: StoredModelCall) { modelCalls += call }
    override fun listSessions(query: SessionQuery): List<StoredSession> = emptyList()
    override fun transcript(sessionId: String, range: TranscriptRange): TranscriptPage = TranscriptPage(sessionId, messages)
    override fun toolEvents(runId: String): List<StoredToolEvent> = tools.filter { it.runId == runId }
    override fun usage(sessionId: String): StoredUsageSummary = StoredUsageSummary(0, 0, 0, 0, 0, 0)
    override fun vfsOperations(runId: String): List<StoredVfsOperation> = vfsOps.filter { it.runId == runId }
    override fun modelCalls(runId: String): List<StoredModelCall> = modelCalls.filter { it.runId == runId }
    fun runStatus(runId: String): String? = runs[runId]?.status
    fun run(runId: String): StoredRun? = runs[runId]
}

private fun sampleModelCall(): StoredModelCall =
    StoredModelCall(
        runId = "run-1",
        sessionId = "session-1",
        callId = "run-1:model-1",
        status = SESSION_MODEL_CALL_STATUS_COMPLETED,
        modelRef = "openai:gpt-5:high",
        startedAtEpochMillis = 10,
        firstEventAtEpochMillis = 12,
        firstToolCallAtEpochMillis = 15,
        finishedAtEpochMillis = 20,
        durationMs = 10,
        messageCount = 3,
        toolCount = 5,
        eventCount = 4,
        thinkingEventCount = 1,
        textEventCount = 1,
        toolCallEventCount = 1,
        usageEventCount = 1,
        inputTokens = 10,
        outputTokens = 2,
        reasoningTokens = 3,
        cacheReadTokens = 4,
        cacheWriteTokens = 5,
        providerModel = "deepseek-v4-flash",
        systemFingerprint = "fp-vfs",
    )

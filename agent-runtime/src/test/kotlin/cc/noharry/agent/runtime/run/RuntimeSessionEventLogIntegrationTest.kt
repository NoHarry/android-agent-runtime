@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.run
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.SessionEventPayload
import cc.noharry.agent.kernel.api.SessionEventType
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelTool
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.loop.completed
import cc.noharry.agent.kernel.core.loop.failed
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
import cc.noharry.agent.kernel.core.tools.DynamicToolSource
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.runtime.eventlog.InMemorySessionEventLog
import cc.noharry.agent.runtime.eventlog.scalarFields
import cc.noharry.agent.runtime.request
import cc.noharry.agent.runtime.tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSessionEventLogIntegrationTest {
    @Test
    fun queuedRunRecordsQueuedThenRunningThenTerminalOrder() {
        val log = InMemorySessionEventLog(nowEpochMillis = { 100 })
        val store = ControllableSessionStore()
        val recorder = RuntimeSessionRecorder(store, log) { 100 }
        val request = AgentRequest("request-1", "session-1", "queued input")

        recorder.startQueuedRun(request, request.requestId)
        assertEquals(RuntimeRunStatus.QUEUED.name, store.runs.single().status)
        recorder.updateRunStatus(request.requestId, RuntimeRunStatus.RUNNING)
        recorder.finishRun(
            RuntimeResult(
                runId = request.requestId,
                status = RuntimeRunStatus.COMPLETED,
                response = "done",
            ),
        )

        assertEquals(RuntimeRunStatus.QUEUED.name, store.runs.single().status)
        assertEquals(
            listOf(RuntimeRunStatus.RUNNING.name, RuntimeRunStatus.COMPLETED.name),
            store.statusUpdates.map { it.status },
        )
        assertEquals(
            listOf(
                SessionEventType.RUN_STARTED,
                SessionEventType.USER_MESSAGE,
                SessionEventType.RUN_STATUS,
                SessionEventType.RUN_STATUS,
                SessionEventType.RUN_STATUS,
                SessionEventType.RUN_FINISHED,
            ),
            log.replay(request.sessionId, request.requestId).map { it.type },
        )
        assertEquals(
            listOf(RuntimeRunStatus.QUEUED.name, RuntimeRunStatus.RUNNING.name, RuntimeRunStatus.COMPLETED.name),
            log.replay(request.sessionId, request.requestId)
                .filter { it.type == SessionEventType.RUN_STATUS }
                .map { it.payload.scalarFields()["status"] },
        )
    }

    @Test
    fun authoritativeRecorderWritesCausalRedactedReplayAndVerifiedTodoState() {
        val log = InMemorySessionEventLog(nowEpochMillis = { 100 })
        val recorder = RuntimeSessionRecorder(null, log) { 100 }
        val request = AgentRequest("request-1", "session-1", "private user input")
        recordCompletedTodo(recorder, request)

        val replay = log.replay(request.sessionId, request.requestId)
        assertEquals(TODO_CAUSAL_TYPES, replay.map { it.type })
        assertEquals("PLAN_REPORTED_COMPLETE", replay[2].payload.scalarFields()["todoCompletion"])
        assertEquals("PLAN_STORE_ACCEPTED_COMPLETE", replay[3].payload.scalarFields()["todoCompletion"])
        assertEquals(listOf(replay[2].sequence), replay[3].sourceEventSequences)
        assertEquals(listOf(replay[4].sequence), replay[5].sourceEventSequences)
        val user = replay[1].payload as SessionEventPayload.UserMessage
        val tool = replay[2].payload as SessionEventPayload.ToolCall
        assertEquals("private user input", user.text)
        assertTrue(tool.arguments.toString().contains("private task"))
        val persisted = replay.joinToString()
        assertFalse(persisted.contains("Authorization"))
        assertFalse(persisted.contains("Bearer "))
    }

    @Test
    fun modelCaptureWritesRequestChunksToolFactsAndAssistantMessage() {
        val log = InMemorySessionEventLog(nowEpochMillis = { 100 })
        val accepted = mutableListOf<String>()
        log.subscribe { accepted += "${it.sequence}:${it.type}:${it.eventId}" }
        val recorder = RuntimeSessionRecorder(null, log) { 100 }
        val request = AgentRequest("request-1", "session-1", "user prompt")
        recordCapturedModelRound(recorder, request)

        val replay = log.replay(request.sessionId, request.requestId)
        assertEquals(replay.map { "${it.sequence}:${it.type}:${it.eventId}" }, accepted)
        assertCapturedModelRound(replay)
        assertFalse(replay.joinToString().contains("Authorization"))
    }

    @Test
    fun eventLogFailureNeverChangesRecorderControlFlow() {
        val recorder = RuntimeSessionRecorder(
            store = null,
            eventLog = FailingEventLog(),
            nowEpochMillis = { 100 },
        )

        recorder.startRun(AgentRequest("request-1", "session-1", "input"), "request-1")
        recorder.finishRun(RuntimeResult("request-1", RuntimeRunStatus.COMPLETED, "done"))
    }

    @Test
    fun sessionStoreFailureStillAttemptsEventLog() {
        val store = ControllableSessionStore(fail = true)
        val log = InMemorySessionEventLog(nowEpochMillis = { 100 })
        val recorder = RuntimeSessionRecorder(store, log) { 100 }

        recorder.startRun(AgentRequest("request-1", "session-1", "input"), "request-1")
        recorder.finishRun(RuntimeResult("request-1", RuntimeRunStatus.COMPLETED, "done"))

        assertTrue(store.attempts > 0)
        assertEquals(0, store.sessions.size)
        assertTrue(log.replay("session-1", "request-1").isNotEmpty())
    }

    @Test
    fun eventLogFailureStillPersistsSessionStore() {
        val store = ControllableSessionStore()
        val log = FailingEventLog()
        val recorder = RuntimeSessionRecorder(store, log) { 100 }

        recorder.startRun(AgentRequest("request-1", "session-1", "input"), "request-1")
        recorder.finishRun(RuntimeResult("request-1", RuntimeRunStatus.COMPLETED, "done"))

        assertEquals(1, store.sessions.size)
        assertEquals(1, store.runs.size)
        assertTrue(store.statusUpdates.isNotEmpty())
        assertTrue(log.attempts > 0)
        assertTrue(log.replay("session-1", "request-1").isEmpty())
    }

    @Test
    fun dualPersistenceFailureDoesNotChangeControlFlow() {
        val store = ControllableSessionStore(fail = true)
        val log = FailingEventLog()
        val recorder = RuntimeSessionRecorder(store, log) { 100 }

        recorder.startRun(AgentRequest("request-1", "session-1", "input"), "request-1")
        recorder.appendMessage(
            MaterializerContext("session-1", "request-1", "request-1"),
            cc.noharry.agent.kernel.api.model.ModelMessage(role = "user", content = "hi"),
        )
        recorder.finishRun(RuntimeResult("request-1", RuntimeRunStatus.COMPLETED, "done"))

        assertTrue(store.attempts > 0)
        assertTrue(log.attempts > 0)
        assertEquals(0, store.sessions.size)
        assertTrue(log.replay("session-1", "request-1").isEmpty())
    }

    @Test
    fun storeIoExceptionStillAttemptsEventLog() {
        val store = ControllableSessionStore(failWith = java.io.IOException("store io failed"))
        val log = InMemorySessionEventLog(nowEpochMillis = { 100 })
        val recorder = RuntimeSessionRecorder(store, log) { 100 }

        recorder.startRun(AgentRequest("request-1", "session-1", "input"), "request-1")
        recorder.appendMessage(
            MaterializerContext("session-1", "request-1", "request-1"),
            cc.noharry.agent.kernel.api.model.ModelMessage(role = "user", content = "hi"),
        )
        recorder.finishRun(RuntimeResult("request-1", RuntimeRunStatus.COMPLETED, "done"))

        assertTrue(store.attempts > 0)
        assertEquals(0, store.sessions.size)
        assertTrue(log.replay("session-1", "request-1").isNotEmpty())
    }

    @Test
    fun eventLogIoExceptionStillPersistsSessionStore() {
        val store = ControllableSessionStore()
        val log = FailingEventLog(failWith = java.io.IOException("event log io failed"))
        val recorder = RuntimeSessionRecorder(store, log) { 100 }

        recorder.startRun(AgentRequest("request-1", "session-1", "input"), "request-1")
        recorder.finishRun(RuntimeResult("request-1", RuntimeRunStatus.COMPLETED, "done"))

        assertEquals(1, store.sessions.size)
        assertEquals(1, store.runs.size)
        assertTrue(store.statusUpdates.isNotEmpty())
        assertTrue(log.attempts > 0)
        assertTrue(log.replay("session-1", "request-1").isEmpty())
    }

    @Test
    fun bothIoExceptionsDoNotChangeControlFlow() {
        val store = ControllableSessionStore(failWith = java.io.IOException("store io failed"))
        val log = FailingEventLog(failWith = java.io.IOException("event log io failed"))
        val recorder = RuntimeSessionRecorder(store, log) { 100 }

        recorder.startRun(AgentRequest("request-1", "session-1", "input"), "request-1")
        recorder.appendMessage(
            MaterializerContext("session-1", "request-1", "request-1"),
            cc.noharry.agent.kernel.api.model.ModelMessage(role = "user", content = "hi"),
        )
        recorder.finishRun(RuntimeResult("request-1", RuntimeRunStatus.COMPLETED, "done"))

        assertTrue(store.attempts > 0)
        assertTrue(log.attempts > 0)
        assertEquals(0, store.sessions.size)
        assertTrue(log.replay("session-1", "request-1").isEmpty())
    }

    @Test
    fun bothOrdinaryExceptionsDoNotChangeControlFlow() {
        val store = ControllableSessionStore(failWith = Exception("store ordinary failed"))
        val log = FailingEventLog(failWith = Exception("event log ordinary failed"))
        val recorder = RuntimeSessionRecorder(store, log) { 100 }

        recorder.startRun(AgentRequest("request-1", "session-1", "input"), "request-1")
        recorder.finishRun(RuntimeResult("request-1", RuntimeRunStatus.COMPLETED, "done"))

        assertTrue(store.attempts > 0)
        assertTrue(log.attempts > 0)
        assertEquals(0, store.sessions.size)
        assertTrue(log.replay("session-1", "request-1").isEmpty())
    }
}

private val TODO_CAUSAL_TYPES = listOf(
    SessionEventType.RUN_STARTED,
    SessionEventType.USER_MESSAGE,
    SessionEventType.TOOL_STARTED,
    SessionEventType.TOOL_RESULT,
    SessionEventType.RUN_STATUS,
    SessionEventType.RUN_FINISHED,
)

private fun recordCompletedTodo(recorder: RuntimeSessionRecorder, request: AgentRequest) {
    val context = MaterializerContext(request.sessionId, request.requestId, request.requestId)
    val todo = ModelEvent.ToolCall(
        id = "todo-1",
        name = "update_todo",
        argumentsJson = """{"todos":[{"content":"private task","status":"completed"}]}""",
    )
    recorder.startRun(request, request.requestId)
    recorder.recordToolStarted(context, todo, DynamicToolSource.NATIVE_TODO)
    recorder.recordToolFinished(
        context = context,
        event = todo,
        source = DynamicToolSource.NATIVE_TODO,
        resultJson = """{"ok":true,"todoCount":1}""",
    )
    recorder.finishRun(RuntimeResult(request.requestId, RuntimeRunStatus.COMPLETED, "private final response"))
}

private fun recordCapturedModelRound(recorder: RuntimeSessionRecorder, request: AgentRequest) {
    val context = MaterializerContext(request.sessionId, request.requestId, request.requestId)
    val modelRequest = ModelRequest(
        messages = listOf(ModelMessage("user", "user prompt")),
        system = "be helpful",
        tools = listOf(ModelTool("read_file", """{"type":"object"}""")),
    )
    val tool = ModelEvent.ToolCall("call-a", "read_file", """{"path":"/tmp/a"}""")
    recorder.startRun(request, request.requestId)
    recorder.recordModelRequest(context, modelRequest)
    recorder.recordModelStreamEvent(context, ModelEvent.ThinkingDelta("plan first"))
    recorder.recordModelStreamEvent(context, ModelEvent.TextDelta("hello"))
    recorder.recordModelStreamEvent(context, tool)
    recorder.recordToolStarted(context, tool, DynamicToolSource.NATIVE_READ)
    recorder.recordToolFinished(context, tool, DynamicToolSource.NATIVE_READ, """{"ok":true,"content":"file-body"}""")
    recorder.appendMessage(context, ModelMessage(role = "assistant", content = "hello"))
    recorder.finishRun(RuntimeResult(request.requestId, RuntimeRunStatus.COMPLETED, "hello"))
}

private fun assertCapturedModelRound(replay: List<cc.noharry.agent.kernel.api.SessionEvent>) {
    val capturedRequest = replay.first { it.type == SessionEventType.MODEL_REQUEST }.payload
        as SessionEventPayload.ModelRequest
    assertEquals("be helpful", capturedRequest.systemPrompt)
    assertTrue(capturedRequest.tools.toString().contains("read_file"))
    val thinking = replay.first { it.type == SessionEventType.ASSISTANT_CHUNK }.payload
        as SessionEventPayload.AssistantChunk
    assertEquals("thinking", thinking.kind)
    assertEquals("plan first", thinking.text)
    assertTrue(replay.any { it.type == SessionEventType.MODEL_TOOL_CALL })
    assertTrue(replay.any { it.type == SessionEventType.TOOL_STARTED })
    val modelTool = replay.first { it.type == SessionEventType.MODEL_TOOL_CALL }.payload
        as SessionEventPayload.ToolCall
    val started = replay.first { it.type == SessionEventType.TOOL_STARTED }.payload
        as SessionEventPayload.ToolCall
    val result = replay.first { it.type == SessionEventType.TOOL_RESULT }.payload
        as SessionEventPayload.ToolResult
    assertEquals("call-a", modelTool.toolCallId)
    assertEquals("call-a", started.toolCallId)
    assertEquals("call-a", result.toolCallId)
    assertTrue(result.result.toString().contains("file-body"))
    val assistant = replay.first { it.type == SessionEventType.ASSISTANT_MESSAGE }.payload
        as SessionEventPayload.AssistantMessage
    assertEquals("hello", assistant.text)
    assertTrue(assistant.sourceChunkSequences.isNotEmpty())
}

private class ControllableSessionStore(
    var fail: Boolean = false,
    private val failWith: Throwable? = if (fail) IllegalStateException("store failed") else null,
) : SessionStore {
    val sessions = mutableListOf<StoredSession>()
    val runs = mutableListOf<StoredRun>()
    val statusUpdates = mutableListOf<StoredRunStatusUpdate>()
    var attempts: Int = 0

    private fun <T> attempt(block: () -> T): T {
        attempts += 1
        failWith?.let { throw it }
        if (fail) error("store failed")
        return block()
    }

    override fun upsertSession(session: StoredSession) {
        attempt { sessions += session }
    }

    override fun upsertRun(run: StoredRun) {
        attempt { runs += run }
    }

    override fun updateRunStatus(update: StoredRunStatusUpdate) {
        attempt { statusUpdates += update }
    }

    override fun appendMessage(message: StoredSessionMessage) {
        attempt { }
    }

    override fun recordToolStarted(event: StoredToolEvent) {
        attempt { }
    }

    override fun recordToolFinished(result: StoredToolResult) {
        attempt { }
    }

    override fun appendUsage(usage: StoredUsageDelta) {
        attempt { }
    }

    override fun appendVfsOperation(operation: StoredVfsOperation) {
        attempt { }
    }

    override fun appendModelCall(call: StoredModelCall) {
        attempt { }
    }

    override fun listSessions(query: SessionQuery): List<StoredSession> = emptyList()

    override fun transcript(sessionId: String, range: TranscriptRange): TranscriptPage =
        TranscriptPage(sessionId, emptyList())

    override fun toolEvents(runId: String): List<StoredToolEvent> = emptyList()

    override fun usage(sessionId: String): StoredUsageSummary = StoredUsageSummary(0, 0, 0, 0, 0, 0)

    override fun vfsOperations(runId: String): List<StoredVfsOperation> = emptyList()

    override fun modelCalls(runId: String): List<StoredModelCall> = emptyList()
}

private class FailingEventLog(
    private val failWith: Throwable = IllegalStateException("event log failed"),
) : cc.noharry.agent.kernel.api.SessionEventLog {
    var attempts: Int = 0

    override fun append(event: cc.noharry.agent.kernel.api.SessionEventAppend): cc.noharry.agent.kernel.api.SessionEvent {
        attempts += 1
        throw failWith
    }

    override fun query(query: cc.noharry.agent.kernel.api.SessionEventQuery) = emptyList<cc.noharry.agent.kernel.api.SessionEvent>()

    override fun replay(sessionId: String, runId: String) = emptyList<cc.noharry.agent.kernel.api.SessionEvent>()
}

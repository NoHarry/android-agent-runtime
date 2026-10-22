package cc.noharry.agent.kernel.core

interface SessionStore {
    fun upsertSession(session: StoredSession)

    fun upsertRun(run: StoredRun)

    fun updateRunStatus(update: StoredRunStatusUpdate)

    fun appendMessage(message: StoredSessionMessage)

    fun recordToolStarted(event: StoredToolEvent)

    fun recordToolFinished(result: StoredToolResult)

    fun appendUsage(usage: StoredUsageDelta)

    fun appendVfsOperation(operation: StoredVfsOperation)

    fun appendModelCall(call: StoredModelCall)

    fun listSessions(query: SessionQuery = SessionQuery()): List<StoredSession>

    fun transcript(sessionId: String, range: TranscriptRange = TranscriptRange()): TranscriptPage

    fun toolEvents(runId: String): List<StoredToolEvent>

    fun usage(sessionId: String): StoredUsageSummary

    fun vfsOperations(runId: String): List<StoredVfsOperation>

    fun modelCalls(runId: String): List<StoredModelCall>
}

data class StoredSession(
    val sessionId: String,
    val title: String,
    val modelId: String = "",
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class StoredRun(
    val runId: String,
    val requestId: String,
    val sessionId: String,
    val status: String,
    val scene: String = "",
    val providerId: String = "",
    val modelId: String = "",
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long? = null,
    val modelRef: String = "",
    val variant: String = "",
)

data class StoredRunStatusUpdate(
    val runId: String,
    val status: String,
    val updatedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long? = null,
    val response: String = "",
    val errorCode: String? = null,
)

data class StoredSessionMessage(
    val sessionId: String,
    val runId: String,
    val role: String,
    val content: String,
    val toolCallId: String = "",
    val toolName: String = "",
    val toolArgumentsJson: String = "",
    val modelRef: String = "",
    val createdAtEpochMillis: Long,
)

data class StoredToolEvent(
    val callId: String,
    val runId: String,
    val sessionId: String,
    val toolName: String,
    val source: String,
    val argumentsJson: String,
    val inputHash: String,
    val status: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long? = null,
    val resultJson: String = "",
    val resultHash: String = "",
    val ok: Boolean? = null,
    val code: String? = null,
)

data class StoredToolResult(
    val callId: String,
    val runId: String,
    val status: String,
    val finishedAtEpochMillis: Long,
    val resultJson: String,
    val resultHash: String,
    val ok: Boolean,
    val code: String? = null,
)

data class StoredUsageDelta(
    val runId: String,
    val sessionId: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val createdAtEpochMillis: Long,
)

data class StoredUsageSummary(
    val requestCount: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val reasoningTokens: Int,
    val cacheReadTokens: Int,
    val cacheWriteTokens: Int,
) {
    val totalTokens: Int = inputTokens + outputTokens + reasoningTokens
}

data class StoredVfsOperation(
    val runId: String,
    val sessionId: String,
    val op: String,
    val path: String,
    val mountId: String = "",
    val status: String,
    val contentHash: String = "",
    val createdAtEpochMillis: Long,
    val vfsCounters: Map<String, Long> = emptyMap(),
    val timings: VfsOperationTimings = VfsOperationTimings(),
    val schemaCacheHit: Boolean? = null,
)

data class StoredModelCall(
    val runId: String,
    val sessionId: String,
    val callId: String,
    val status: String,
    val source: String = SESSION_MODEL_CALL_SOURCE_ROUND,
    val modelRef: String = "",
    val startedAtEpochMillis: Long,
    val firstEventAtEpochMillis: Long? = null,
    val firstToolCallAtEpochMillis: Long? = null,
    val finishedAtEpochMillis: Long,
    val durationMs: Long,
    val messageCount: Int = 0,
    val toolCount: Int = 0,
    val eventCount: Int = 0,
    val thinkingEventCount: Int = 0,
    val textEventCount: Int = 0,
    val toolCallEventCount: Int = 0,
    val usageEventCount: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val providerModel: String = "",
    val systemFingerprint: String = "",
    val errorCode: String? = null,
)

data class VfsOperationTimings(
    val resolveMs: Long = 0,
    val materializeMs: Long = 0,
    val scanMs: Long = 0,
    val serializeMs: Long = 0,
    val prepareMs: Long = 0,
    val batchWaitMs: Long = 0,
    val executeMs: Long = 0,
    val batchResultWaitMs: Long = 0,
    val summaryMs: Long = 0,
    val publicFinishMs: Long = 0,
)

data class SessionQuery(
    val limit: Int = DEFAULT_SESSION_QUERY_LIMIT,
)

data class TranscriptRange(
    val limit: Int = DEFAULT_TRANSCRIPT_LIMIT,
)

data class TranscriptPage(
    val sessionId: String,
    val messages: List<StoredSessionMessage>,
)

const val SESSION_STORE_DB_FILE = "runtime-session-store.sqlite"
const val SESSION_TOOL_STATUS_STARTED = "STARTED"
const val SESSION_TOOL_STATUS_COMPLETED = "COMPLETED"
const val SESSION_TOOL_STATUS_FAILED = "FAILED"
const val SESSION_MODEL_CALL_SOURCE_ROUND = "round"
const val SESSION_MODEL_CALL_SOURCE_COMPACTION = "compaction"
const val SESSION_MODEL_CALL_STATUS_COMPLETED = "COMPLETED"
const val SESSION_MODEL_CALL_STATUS_FAILED = "FAILED"
const val SESSION_MODEL_CALL_STATUS_CANCELLED = "CANCELLED"
const val SESSION_VFS_STATUS_OK = "OK"
const val SESSION_VFS_STATUS_FAILED = "FAILED"

private const val DEFAULT_SESSION_QUERY_LIMIT = 50
private const val DEFAULT_TRANSCRIPT_LIMIT = 200

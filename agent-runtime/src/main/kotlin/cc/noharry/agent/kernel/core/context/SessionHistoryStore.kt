@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.context
import cc.noharry.agent.spi.model.ModelMessage

/**
 * The model messages of a session's earlier runs, kept outside any one runtime so that a host which builds a new
 * runtime per run can still hold one growing conversation, as Codex does. A run's requests start with these messages,
 * exactly as the earlier runs last sent them, and then its own; when it ends its own messages are appended. Nothing
 * before the new input changes, so the provider's prompt cache covers it.
 *
 * A run's own messages stay its own: compaction, the once-per-run corrections and implicit completion look only at
 * them, and loop history saved for resume holds only them. Runs are kept whole. [maxBytes] is a memory guard
 * ([ConversationCompactor] keeps the length down): past it the oldest runs go, down to half of it, so the history then
 * stays put for a while instead of shifting (and breaking the cache) every run.
 * With [persistence], evicted sessions and sessions from an earlier process are restored on demand. Only the
 * [maxSessions] most recently used sessions are kept in memory: an
 * app that opens a new session at every wake would otherwise hold every conversation since it started. Runs are followed
 * from start to end, at most [maxRunsInFlight] at once; past it the oldest is forgotten and not recorded.
 */
internal class SessionHistoryStore @JvmOverloads constructor(
    private val maxBytes: Int = Int.MAX_VALUE,
    private val maxSessions: Int = MAX_SESSIONS,
    private val maxRunsInFlight: Int = MAX_TRACKED_RUNS,
    private var persistence: SessionHistoryPersistence? = null,
) {
    private val sessions = object : LinkedHashMap<String, SessionRuns>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SessionRuns>?): Boolean =
            size > maxSessions
    }
    private val started = object : LinkedHashMap<String, RunStart>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RunStart>?): Boolean =
            size > maxRunsInFlight
    }

    /** Attaches storage before the builder exposes the runtime to callers. */
    internal fun bindPersistence(storage: SessionHistoryPersistence) {
        synchronized(sessions) {
            if (persistence?.identity == storage.identity) return
            check(synchronized(started) { started.isEmpty() }) { "Cannot replace history storage during a turn." }
            sessions.forEach { (id, session) -> synchronized(session) {
                if (storage.load(id) == null && session.runs.isNotEmpty()) {
                    storage.save(id, StoredSessionHistory(session.runs, session.lastInputTokens))
                }
            } }
            persistence = storage
        }
    }

    /** Called when a run starts; the run's requests then begin with the session's messages as they are now. */
    fun start(runId: String, sessionId: String) {
        val session = synchronized(sessions) { sessions.getOrPut(sessionId) { restored(sessionId) } }
        val start = synchronized(session) {
            persistence?.let { storage ->
                val saved = storage.load(sessionId)
                session.runs = trimmed(saved?.runs.orEmpty())
                session.lastInputTokens = saved?.lastInputTokens
            }
            session.latestStarted += 1
            RunStart(sessionId, session.latestStarted, session.runs)
        }
        synchronized(started) { started[runId] = start }
    }

    /** [own] after the earlier runs' messages this run started with. */
    fun prefixed(runId: String, own: List<ModelMessage>): List<ModelMessage> {
        val start = synchronized(started) { started[runId] } ?: return own
        return if (start.runs.isEmpty()) own else start.runs.flatten() + own
    }

    /**
     * Keeps [own], the run's messages up to now (its input first, its answer last), as the session's newest run. A run
     * that started before a newer one of the same session leaves the history alone. Calls left without a result (the
     * run paused or ended first) get one, so the next request is well formed.
     */
    fun record(runId: String, own: List<ModelMessage>) {
        val start = synchronized(started) { started[runId] } ?: return
        val session = session(start.sessionId) ?: return
        synchronized(session) {
            if (session.latestStarted != start.generation) return
            session.runs = trimmed(start.runs + listOf(own.withClosedToolCalls()))
            persistence?.save(start.sessionId, StoredSessionHistory(session.runs, session.lastInputTokens))
        }
    }

    /** The earlier runs [runId] started with (or the compacted form it was given since), oldest first. */
    fun earlierRuns(runId: String): List<List<ModelMessage>> =
        synchronized(started) { started[runId] }?.runs.orEmpty()

    /**
     * Replaces the earlier runs [runId] carries, after compaction: its later requests and, when it is recorded, the
     * session start from [runs]. False when the run is not tracked.
     */
    fun replaceEarlierRuns(runId: String, runs: List<List<ModelMessage>>): Boolean {
        val start = synchronized(started) { started[runId] } ?: return false
        start.runs = runs
        start.compacted = true
        return true
    }

    /** Whether [runId] has already replaced its earlier runs; one compaction per run keeps a small window from thrashing. */
    fun compactedInRun(runId: String): Boolean = synchronized(started) { started[runId] }?.compacted == true

    /** Input tokens of the session's latest request, so a run can tell before its first call that it is near the window. */
    fun noteInputTokens(runId: String, tokens: Long) {
        val start = synchronized(started) { started[runId] } ?: return
        session(start.sessionId)?.let { synchronized(it) { it.lastInputTokens = tokens } }
    }

    fun lastInputTokens(runId: String): Long? {
        val start = synchronized(started) { started[runId] } ?: return null
        return session(start.sessionId)?.let { synchronized(it) { it.lastInputTokens } }
    }

    /** Drops what [runId] started with; the runtime calls it once the run is over. */
    fun forget(runId: String) {
        synchronized(started) { started.remove(runId) }
    }

    fun messages(sessionId: String): List<ModelMessage> =
        session(sessionId)?.let { synchronized(it) { it.runs.flatten() } }.orEmpty()

    fun clearSession(sessionId: String) {
        // Deletion must not restore or parse persisted history, which may itself be damaged.
        synchronized(sessions) { sessions[sessionId] }?.let { session ->
            synchronized(session) {
                session.latestStarted += 1
                session.runs = emptyList()
                session.lastInputTokens = null
            }
        }
        synchronized(started) { started.entries.removeAll { it.value.sessionId == sessionId } }
        persistence?.delete(sessionId)
    }

    fun clearAll() {
        synchronized(sessions) { sessions.keys.toList() }.forEach(::clearSession)
        persistence?.clear()
    }

    private fun session(sessionId: String): SessionRuns? = synchronized(sessions) {
        sessions[sessionId] ?: persistence?.load(sessionId)?.let { saved ->
            SessionRuns().apply { runs = trimmed(saved.runs); lastInputTokens = saved.lastInputTokens }
                .also { sessions[sessionId] = it }
        }
    }

    private fun restored(sessionId: String): SessionRuns = SessionRuns().apply {
        persistence?.load(sessionId)?.let { saved ->
            runs = trimmed(saved.runs)
            lastInputTokens = saved.lastInputTokens
        }
    }

    private fun trimmed(runs: List<List<ModelMessage>>): List<List<ModelMessage>> {
        var bytes = runs.sumOf { it.historyBytes() }
        if (bytes <= maxBytes) return runs
        var removed = 0
        while (removed < runs.size && bytes > maxBytes / 2) { bytes -= runs[removed].historyBytes(); removed += 1 }
        return runs.drop(removed)
    }

    private class SessionRuns {
        var latestStarted = 0L
        var runs: List<List<ModelMessage>> = emptyList()
        var lastInputTokens: Long? = null
    }

    private class RunStart(val sessionId: String, val generation: Long, @Volatile var runs: List<List<ModelMessage>>) {
        @Volatile var compacted = false
    }
}

/** Whether a store is set or not, the messages a run's request carries. */
internal fun SessionHistoryStore?.prefixed(runId: String, own: List<ModelMessage>): List<ModelMessage> =
    this?.prefixed(runId, own) ?: own

private fun List<ModelMessage>.historyBytes(): Long = sumOf { message ->
    listOfNotNull(message.role, message.content, message.toolCallId, message.toolName, message.toolArgumentsJson,
        message.thinkingSignature, message.reasoningContent, message.assistantText).sumOf { it.utf8Size() } +
        message.toolAdditions.sumOf { it.utf8Size() } + message.images.sumOf { image ->
            image.bytes.size.toLong() + image.mediaType.utf8Size() + image.sourcePath.utf8Size() + image.detail.utf8Size()
        }
}

private fun String.utf8Size(): Long = toByteArray(Charsets.UTF_8).size.toLong()

/** Each unanswered call gets its result right after it: providers such as DeepSeek reject a result that comes later. */
private fun List<ModelMessage>.withClosedToolCalls(): List<ModelMessage> {
    val answered = filter { it.role == "tool" }.mapNotNullTo(hashSetOf()) { it.toolCallId }
    return flatMap { message ->
        if (message.role != "assistant" || message.toolCallId == null || message.toolCallId in answered) {
            listOf(message)
        } else {
            listOf(message, ModelMessage(role = "tool", content = UNANSWERED_CALL_RESULT, toolCallId = message.toolCallId, toolName = message.toolName))
        }
    }
}

private const val MAX_TRACKED_RUNS = 64
private const val MAX_SESSIONS = 16
private const val UNANSWERED_CALL_RESULT = """{"ok":false,"status":"no_result","note":"The run ended before this call returned."}"""

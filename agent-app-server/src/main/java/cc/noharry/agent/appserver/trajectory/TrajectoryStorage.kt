@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory
import android.content.Context
import android.util.Log
import cc.noharry.agent.appserver.runtime.encodeSessionId
import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.spi.SessionEvent
import cc.noharry.agent.spi.SessionEventLogHealth
import cc.noharry.agent.spi.SessionEventQuery
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App-private, no-backup EventLog root shared by the server's session writer and the Trajectory reader.
 *
 * One directory per session so deleting a session is a host storage operation on a
 * whole owned tree, never an update/delete against the append-only stream. It is kept out
 * of the business VFS (`runtime-debug-workspace`) and out of any SAF workspace.
 */
internal const val DEBUG_SESSION_EVENT_LOG_ROOT_DIR = "debug-session-event-log"

internal fun sessionEventLogBaseRoot(context: Context): File =
    File(context.noBackupFilesDir, DEBUG_SESSION_EVENT_LOG_ROOT_DIR).canonicalOrAbsolute()

internal fun appSessionEventLogRoot(context: Context, sessionId: String): File =
    appSessionEventLogRoot(sessionEventLogBaseRoot(context), sessionId)

internal fun appSessionEventLogRoot(baseRoot: File, sessionId: String): File =
    // Base64 url-safe encoding keeps every id a single path segment; a blank id would
    // otherwise collapse onto the base root and make one session delete wipe them all.
    File(baseRoot, encodeSessionId(sessionId).ifEmpty { BLANK_SESSION_ROOT_NAME })
        .canonicalOrAbsolute()
        .also(::clearLegacyEventLogV1)

/** Deletes leftover metadata-only v1 trees. v2 never reads or migrates that format. */
internal fun clearLegacyEventLogV1(root: File) {
    val legacy = File(File(root, "session-events"), "v1")
    if (legacy.exists()) {
        legacy.deleteRecursively()
    }
}

/**
 * Resolves the path the same way for writer and reader.
 *
 * The durable EventLog rejects link-shaped paths and canonicalizes internally, so handing it an
 * already-resolved path keeps both sides agreeing on one directory regardless of how the platform
 * spells the app data dir.
 */
private fun File.canonicalOrAbsolute(): File = runCatching { canonicalFile }.getOrDefault(absoluteFile)

/**
 * Bounded failure surfaced to the repository and UI.
 *
 * [reasonCode] is all the UI may show. [cause] is retained so the failure stays diagnosable on a
 * device: without it a cold-read failure is indistinguishable from "not loaded yet".
 */
internal class TrajectoryEventLogFailure(
    val reasonCode: String,
    override val cause: Throwable? = null,
) : Exception(reasonCode, cause)

internal object TrajectoryEventLogReason {
    const val READER_BUILD_FAILED = "reader_build_failed"
    internal const val QUERY_FAILED = "query_failed"
    internal const val REPLAY_FAILED = "replay_failed"
    internal const val HEALTH_FAILED = "health_failed"
    internal const val DELETE_FAILED = "delete_failed"
}

/** The diagnostic log tag; the UI stays bounded, logcat carries the real cause. */
internal const val TRAJECTORY_LOG_TAG = "DebugTrajectory"

/**
 * Diagnostic access to the durable EventLog and the writer's public-turn to execution mapping.
 *
 * Every operation builds a short-lived reader over the same per-session root, calls exactly one
 * query/replay/health method and closes it, so no Runtime survives an Activity detach, session
 * switch or Clear runtime data.
 * SDK storage adapters may initialize bookkeeping directories when opened; no conversation is resumed or run.
 *
 * Two levels of exclusion: a per-session mutex orders reads against that session's delete, and a
 * process-wide barrier lets a global wipe drain every in-flight reader and refuse new ones. A
 * stale load generation is not sufficient on its own — it discards a result but does not stop a
 * reader from touching files that are being deleted.
 */
internal class TrajectoryEventLogGateway(
    private val baseRoot: () -> File,
    private val rootForSession: (String) -> File,
    private val readerFactory: (File) -> AgentRuntime = ::buildTrajectoryEventLogReader,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val storageMutexes = HashMap<String, Mutex>()
    private val storageMutexesLock = Any()
    private val exclusiveGate = Mutex()
    private val readSlots = Semaphore(MAX_CONCURRENT_READERS)

    suspend fun replay(sessionId: String, runId: String): List<SessionEvent> =
        withSessionReader(sessionId, TrajectoryEventLogReason.REPLAY_FAILED) { runtime ->
            runtime.diagnostics.replaySessionEvents(sessionId, runId)
        }

    suspend fun query(query: SessionEventQuery): List<SessionEvent> =
        withSessionReader(query.sessionId, TrajectoryEventLogReason.QUERY_FAILED) { runtime ->
            runtime.diagnostics.querySessionEvents(query)
        }

    suspend fun health(sessionId: String, runId: String): SessionEventLogHealth =
        withSessionReader(sessionId, TrajectoryEventLogReason.HEALTH_FAILED) { runtime ->
            runtime.diagnostics.getSessionEventLogHealth(sessionId, runId)
        }

    /** Deletes the whole host-owned root for one session; callers must have stopped writers first. */
    suspend fun deleteSessionRoot(sessionId: String): Boolean = withRead {
        storageMutex(sessionId).withLock {
            withContext(ioDispatcher) { deleteTree(rootForSession(sessionId)) }
        }
    }

    /**
     * Deletes every session root behind the exclusive barrier: in-flight readers are drained and
     * new ones cannot start until the wipe finishes. Callers must additionally have stopped and
     * joined every writer.
     */
    suspend fun deleteAllRoots(): Boolean = withExclusive {
        withContext(ioDispatcher) { deleteTree(baseRoot()) }
    }

    private suspend fun <T> withSessionReader(
        sessionId: String,
        failureReason: String,
        block: suspend (AgentRuntime) -> T,
    ): T = withRead {
        storageMutex(sessionId).withLock {
            withContext(ioDispatcher) {
                val root = rootForSession(sessionId)
                val runtime = try {
                    readerFactory(root)
                } catch (failure: Throwable) {
                    throw reportFailure(TrajectoryEventLogReason.READER_BUILD_FAILED, root, failure)
                }
                try {
                    try {
                        block(runtime)
                    } catch (failure: Throwable) {
                        throw reportFailure(failureReason, root, failure)
                    }
                } finally {
                    runCatching { runtime.close() }
                }
            }
        }
    }

    private fun reportFailure(reasonCode: String, root: File, failure: Throwable): Throwable {
        if (failure is TrajectoryEventLogFailure) return failure
        // Keep the real cause out of the UI but visible on the device; a silent downgrade to
        // "Unknown / not loaded" makes a broken cold read look like an empty one.
        // Guarded because android.util.Log is a throwing stub under plain JVM unit tests.
        runCatching { Log.w(TRAJECTORY_LOG_TAG, "EventLog $reasonCode for root=${root.absolutePath}", failure) }
        return TrajectoryEventLogFailure(reasonCode, failure)
    }

    private fun storageMutex(sessionId: String): Mutex =
        synchronized(storageMutexesLock) { storageMutexes.getOrPut(sessionId) { Mutex() } }

    private suspend fun <T> withRead(block: suspend () -> T): T {
        // Passing through the gate is what makes the barrier airtight: a session mutex created
        // after a wipe started would otherwise let a brand new reader slip in beside it.
        exclusiveGate.withLock { readSlots.acquire() }
        return try {
            block()
        } finally {
            readSlots.release()
        }
    }

    private suspend fun <T> withExclusive(block: suspend () -> T): T = exclusiveGate.withLock {
        repeat(MAX_CONCURRENT_READERS) { readSlots.acquire() }
        try {
            block()
        } finally {
            repeat(MAX_CONCURRENT_READERS) { readSlots.release() }
        }
    }

    private companion object {
        /** Bounded reader parallelism; the wipe path acquires every slot to become exclusive. */
        internal const val MAX_CONCURRENT_READERS = 8
    }
}

/**
 * Diagnostic reader over the same storage and EventLog root as the writer. Storage supplies the durable mapping
 * from public turn ids to execution UUIDs. The model rejects execution; the gateway uses only diagnostic reads.
 * Opening SDK storage adapters can initialize bookkeeping directories, but does not load or resume a conversation.
 */
internal fun buildTrajectoryEventLogReader(root: File): AgentRuntime =
    AgentRuntimeBuilder.create()
        .model(EventLogReaderModel)
        .storage(root)
        .sessionEventLogRoot(root)
        .build()

private object EventLogReaderModel : cc.noharry.agent.spi.model.ModelClient {
    override val providerConcurrency = cc.noharry.agent.spi.ProviderConcurrencyDeclaration.serialized()
    override fun complete(request: cc.noharry.agent.spi.model.ModelRequest): kotlinx.coroutines.flow.Flow<cc.noharry.agent.spi.model.ModelEvent> =
        error("An event-log reader cannot execute turns")
}

private const val BLANK_SESSION_ROOT_NAME = "_"

private fun deleteTree(target: File): Boolean {
    if (!target.exists()) return true
    val deleted = runCatching { target.deleteRecursively() }.getOrDefault(false)
    return deleted || !target.exists()
}

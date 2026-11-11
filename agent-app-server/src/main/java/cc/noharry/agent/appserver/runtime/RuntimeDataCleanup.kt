package cc.noharry.agent.appserver.runtime
import android.content.Context
import cc.noharry.agent.appserver.protocol.view.GENERATED_IMAGES_DIR
import cc.noharry.agent.appserver.trajectory.DEBUG_SESSION_EVENT_LOG_ROOT_DIR
import java.io.File

/**
 * Deletes the host's **runtime** data only: sessions, generated images, workspace memory/artifacts,
 * and local evidence/logs. Provider and image model API keys and app settings are kept.
 */
internal data class RuntimeDataCleanupResult(
    val deletedPaths: List<String>,
    val failedPaths: List<String>,
) {
    internal val deletedCount: Int get() = deletedPaths.size
    internal val failedCount: Int get() = failedPaths.size
    val ok: Boolean get() = failedPaths.isEmpty()

    internal fun summaryLine(): String =
        when {
            deletedCount == 0 && failedCount == 0 -> "Nothing to clear"
            failedCount == 0 -> "Cleared $deletedCount runtime path${if (deletedCount == 1) "" else "s"}"
            else -> "Cleared $deletedCount path(s); $failedCount failed"
        }
}

/**
 * Wipe targets under [Context.filesDir] plus the no-backup EventLog base root. Safe to call
 * with empty/missing dirs. Does **not** touch provider config, MCP prefs, or SAF tree URI prefs.
 *
 * Callers must stop and join every run and cancel every Trajectory load before calling this;
 * the EventLog roots are deleted as whole host-owned trees, not edited in place.
 */
internal fun clearRuntimeDataOnDisk(context: Context): RuntimeDataCleanupResult {
    val targets = runtimeDataDirs(context.filesDir) +
        runtimeNoBackupDataDirs(context.noBackupFilesDir)
    return clearRuntimeDataDirs(targets)
}

/** Pure helper for tests: delete each existing path and report outcomes. */
internal fun clearRuntimeDataDirs(targets: List<File>): RuntimeDataCleanupResult {
    val deleted = mutableListOf<String>()
    val failed = mutableListOf<String>()
    targets.forEach { target ->
        if (!target.exists()) return@forEach
        val ok = runCatching { target.deleteRecursively() }.getOrDefault(false)
        if (ok || !target.exists()) {
            deleted += target.name
        } else {
            failed += target.name
        }
    }
    return RuntimeDataCleanupResult(deletedPaths = deleted, failedPaths = failed)
}

internal fun runtimeDataDirs(filesDir: File): List<File> =
    listOf(
        File(filesDir, DEBUG_WORKSPACE_DIR),
        File(filesDir, DEBUG_RUN_EVIDENCE_DIR),
        File(filesDir, MCP_PROGRESSIVE_EVIDENCE_DIR),
        File(filesDir, RUNTIME_DEBUG_LOG_DIR),
        File(filesDir, WEB_SEARCH_EVIDENCE_DIR),
        // The reliability root holds each run's probes, graph and run metadata: runtime data too.
        File(filesDir, AGENT_RELIABILITY_DIR),
        // Pictures the screens show: the picture plugin's, a folder per session (and its settings/adb tests), and
        // delegations' reports, a folder per task.
        File(filesDir, GENERATED_IMAGES_DIR),
    )

/** Runtime data that lives outside `filesDir`: the app-private no-backup EventLog roots. */
internal fun runtimeNoBackupDataDirs(noBackupFilesDir: File): List<File> =
    listOf(File(noBackupFilesDir, DEBUG_SESSION_EVENT_LOG_ROOT_DIR))

// Keep names aligned with the writers that create these directories.
internal const val DEBUG_WORKSPACE_DIR = "runtime-debug-workspace"
/** Reliability root for run.json + probe artifacts (probes.jsonl, run-meta, completeness). */
internal const val AGENT_RELIABILITY_DIR = "agent-reliability"
private const val DEBUG_RUN_EVIDENCE_DIR = "debug-run-evidence"
private const val MCP_PROGRESSIVE_EVIDENCE_DIR = "mcp-progressive-evidence"
private const val RUNTIME_DEBUG_LOG_DIR = "runtime-debug-logs"
private const val WEB_SEARCH_EVIDENCE_DIR = "web-search-evidence"

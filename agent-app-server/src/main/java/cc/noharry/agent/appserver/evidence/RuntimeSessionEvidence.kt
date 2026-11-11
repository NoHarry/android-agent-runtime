package cc.noharry.agent.appserver.evidence
import android.content.Context
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest
import cc.noharry.agent.appserver.runtime.append
import cc.noharry.agent.appserver.session.TranscriptStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun runEvidenceDir(context: Context): File =
    File(context.filesDir, DEBUG_RUN_EVIDENCE_DIR)

/** Location [RunEvidenceRecorder.write] uses; read-only enrichment resolves the same path. */
internal fun runEvidenceFile(context: Context, requestId: String): File =
    File(runEvidenceDir(context), runEvidenceFileName(requestId))

internal fun readRunEvidenceFile(context: Context, requestId: String): RunEvidence? {
    val file = runEvidenceFile(context, requestId)
    if (!file.isFile) return null
    return runCatching { RunEvidenceJson.read(file) }.getOrNull()
}

internal fun appRunEvidenceRecorder(
    context: Context,
    request: RuntimeTurnRequest,
    providerLabel: String,
    streamLabel: String,
    backendLabel: String,
    userText: String = request.input,
): RunEvidenceRecorder =
    RunEvidenceRecorder(
        // Kept as a file only for a host that keeps diagnostics; the replay check reads what is in memory.
        evidenceDir = if (AppHost.diagnostics) runEvidenceDir(context) else null,
        request = request,
        providerLabel = providerLabel,
        streamLabel = streamLabel,
        backendLabel = backendLabel,
        userText = userText,
    )

internal suspend fun TranscriptStore.appendRunEvidence(
    recorder: RunEvidenceRecorder,
    surfaceOnTimeline: Boolean = true,
): RunReplayResult {
    val (replay, fileResult) = withContext(Dispatchers.IO) {
        RunReplayVerifier.verify(recorder.snapshot()) to runCatching { recorder.write() }
    }
    val path = fileResult.getOrNull()?.absolutePath
    val error = fileResult.exceptionOrNull()?.message
    val shouldSurface = surfaceOnTimeline || !replay.passed || error != null
    if (shouldSurface) {
        appendRuntime("Runtime event checks", replayEvidenceBody(replay, path, error))
    }
    return replay
}

private fun replayEvidenceBody(
    replay: RunReplayResult,
    path: String?,
    error: String?,
): String = buildString {
    append(replay.summary)
    path?.let { append("\n").append(it) }
    error?.let { append("\nwriteError=").append(it) }
    if (replay.failures.isNotEmpty()) append("\nfailures=").append(replay.failures.joinToString("|"))
    if (replay.warnings.isNotEmpty()) append("\nwarnings=").append(replay.warnings.joinToString("|"))
}

private const val DEBUG_RUN_EVIDENCE_DIR = "debug-run-evidence"

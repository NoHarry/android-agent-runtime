package cc.noharry.agent.appserver.runtime
import android.content.Context
import cc.noharry.agent.appserver.evidence.inputPreviewText
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest
import java.io.File

internal fun appWorkspaceRoot(context: Context): File =
    File(context.filesDir, DEBUG_WORKSPACE_DIR).apply { mkdirs() }

// DEBUG_WORKSPACE_DIR is defined in DebugRuntimeDataCleanup.kt (shared wipe target).

/**
 * Real runtime intake summary derived only from [RuntimeTurnRequest] and debug config fields.
 *
 * The previous version emitted hardcoded `runtimeRoutingSummary`/`runtimeMemorySummary`
 * preview text that guessed candidate tools/domains by keyword and was unrelated to the
 * actual runtime execution. Those were removed; the transcript now shows real tool calls
 * from the SDK ThreadItem snapshots.
 */
internal fun runtimeIntakeSummary(
    request: RuntimeTurnRequest,
    provider: ModelProvider,
    streamLabel: String,
    workspaceRoot: File,
): String =
    """
        stage=intake
        requestId=${request.requestId}
        sessionId=${request.sessionId}
        scene=${request.scene ?: "default"}
        inputLength=${request.input.length}
        inputPreview=${request.input.inputPreviewText()}
        provider=${provider.label}
        mode=$streamLabel
        workspace=${workspaceRoot.name}
    """.trimIndent()

/**
 * Extracts the `tool=` value from a RawToolHook body so pre/post calls can be paired.
 * Falls back to "unknown" if the line is missing (malformed hook body).
 */
internal fun parseRawToolName(body: String): String {
    val line = body.lineSequence().firstOrNull { it.startsWith("tool=") } ?: return "unknown"
    return line.removePrefix("tool=").trim()
}

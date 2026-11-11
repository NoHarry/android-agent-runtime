package cc.noharry.agent.appserver.session

import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.view.normalizeTextEscapes
import cc.noharry.agent.appserver.runtime.append
internal const val DEBUG_ALREADY_SUBMITTED_EXTRA = "already_submitted"
internal const val DEBUG_BROADCAST_PERMISSION = "android.permission.DUMP"

internal const val BACKEND_LIVE = "live"
internal const val BACKEND_SIMULATED = "simulated"
internal const val LEGACY_BACKEND_LIVE_ALIAS = "catalog"
internal const val LEGACY_BACKEND_SIMULATED_ALIAS = "fixture"

/** What the front ends render; the server's state and the protocol client's are the same value. */
@cc.noharry.agent.appserver.AppServerPluginApi
typealias UiState = cc.noharry.agent.appserver.protocol.client.ServerState

internal fun String.trimForDisplay(): String =
    if (length <= MAX_TRANSCRIPT_CHARS) {
        this
    } else {
        take(MAX_TRANSCRIPT_CHARS) + "\n[truncated in debug UI]"
    }

internal fun parseToolResultStatus(resultBody: String): ToolCallStatus {
    val ok = resultBody.lineSequence()
        .firstOrNull { it.startsWith("ok=") }
        ?.removePrefix("ok=")
        ?.trim()
        ?.lowercase()
    return when (ok) {
        "true" -> ToolCallStatus.DONE
        "false" -> ToolCallStatus.FAILED
        else -> ToolCallStatus.UNKNOWN
    }
}

internal fun parseRawToolCallId(body: String): String =
    body.lineSequence()
        .firstOrNull { it.startsWith("toolCallId=") }
        ?.substringAfter("=")
        ?.trim()
        .orEmpty()

internal data class ToolIssueSummary(
    val failed: Int,
    val unknown: Int,
) {
    internal val hasIssues: Boolean = failed > 0 || unknown > 0
}

internal fun decorateRuntimeResultBody(
    body: String,
    issues: ToolIssueSummary,
): String {
    val normalizedBody = body.normalizeTextEscapes()
    if (!issues.hasIssues) return normalizedBody
    return buildString {
        append("⚠️ Tool execution did not fully pass.\n")
        if (issues.failed > 0) append("- Failed tool calls: ${issues.failed}\n")
        if (issues.unknown > 0) append("- Tool calls without captured raw result: ${issues.unknown}\n")
        append("\nModel finish response:\n\n")
        append(normalizedBody)
    }
}

private const val MAX_TRANSCRIPT_CHARS = 12000

/**
 * Who opened a turn with [text], for its header: a running plugin that recognizes it (another agent's report), else the
 * person ([cc.noharry.agent.appserver.server.PluginContext.turnLabel]).
 */
internal fun turnLabel(text: String): String =
    cc.noharry.agent.appserver.runtime.AppHost.additions.turnLabel(text) ?: cc.noharry.agent.appserver.protocol.USER_TURN_LABEL

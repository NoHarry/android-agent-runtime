package cc.noharry.agent.kernel.core

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun probeRoundStatus(outcome: RoundOutcome): String = when (outcome) {
    is RoundOutcome.Finished -> "FINISHED"
    is RoundOutcome.Failed -> "FAILED"
    is RoundOutcome.ToolResults -> "TOOL_RESULTS"
    is RoundOutcome.RequiresConfirmation -> "REQUIRES_CONFIRMATION"
    is RoundOutcome.NeedsUserInput -> "NEEDS_USER_INPUT"
}

internal fun probeToolSource(toolName: String): String = when {
    toolName == "web_fetch" -> "NETWORK"
    toolName.startsWith("mcp__") -> "BACKEND"
    else -> "NATIVE"
}

internal fun probeToolExecutionResult(toolName: String, resultJson: String?): cc.noharry.agent.kernel.api.ToolUseResult =
    when {
        resultJson == null -> cc.noharry.agent.kernel.api.ToolUseResult(
            ok = false,
            resultJson = "",
            code = PROBE_MISSING_TOOL_RESULT_CODE,
        )
        probeToolSource(toolName) == "BACKEND" -> backendToolCallResult(resultJson)
        else -> toolUseResult(resultJson)
    }

/** Tool args default to digest-only evidence (Q-RPB-008); raw args stay out of probe events. */
internal fun probeArgsDigest(argumentsJson: String?): String? =
    argumentsJson
        ?.takeIf { it.isNotBlank() }
        ?.let(::probeSha256Hex)

internal fun probeSha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun probeUrlHost(argumentsJson: String?): String? {
    val url = argumentsJson
        ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        ?.let { it as? JsonObject }
        ?.get("url")
        ?.jsonPrimitive
        ?.contentOrNull
        ?: return null
    val withoutScheme = url.substringAfter("://", "")
    if (withoutScheme.isBlank()) return null
    return withoutScheme.substringBefore('/').substringBefore('?').ifBlank { null }
}

private const val PROBE_MISSING_TOOL_RESULT_CODE = "PROBE_MISSING_TOOL_RESULT"

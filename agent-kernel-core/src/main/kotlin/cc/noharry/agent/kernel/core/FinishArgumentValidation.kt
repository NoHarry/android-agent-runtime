package cc.noharry.agent.kernel.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Check the terminal protocol before accepting a completion or asking for its one correction. */
internal fun invalidFinishArguments(argumentsJson: String): Boolean {
    val args = runCatching { Json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull() ?: return true
    if (args.keys.any { it !in FINISH_ARGUMENT_NAMES }) return true
    val response = (args["response"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return true
    if (invalidFinishResponseText(response)) return true
    // Preserve the documented response-only legacy form, but never coerce an invalid outcome to success.
    if ("outcome" !in args) return false
    val outcome = (args["outcome"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return outcome !in FINISH_OUTCOMES
}

internal fun invalidFinishResponseText(response: String): Boolean {
    if (response.isBlank() || isPureNarrationFinishResponse(response)) return true
    // Quoted code can legitimately explain the protocol; only unquoted tool-call syntax is spillover.
    val prose = response.replace(FENCED_CODE, "").replace(INLINE_CODE, "")
    if (FINISH_PARAMETER_SPILL.containsMatchIn(prose)) return true
    if (!prose.trimStart().startsWith("{")) return false
    val embedded = runCatching { Json.parseToJsonElement(prose) as? JsonObject }.getOrNull() ?: return false
    return "response" in embedded && embedded.keys.all { it in FINISH_ARGUMENT_NAMES }
}

private val FINISH_ARGUMENT_NAMES = setOf("response", "outcome")
private val FINISH_OUTCOMES = setOf("success", "partial", "blocked")
private val FENCED_CODE = Regex("```[^`]*```", RegexOption.DOT_MATCHES_ALL)
private val INLINE_CODE = Regex("`[^`\r\n]+`")
private val FINISH_PARAMETER_SPILL = Regex(
    """<(?:[\w|]+:)?parameter\s+name\s*=\s*["'](?:outcome|response)["']|<\|(?:DSML|tool_call)\|""",
    RegexOption.IGNORE_CASE,
)

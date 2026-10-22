package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.ToolUseResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

const val MCP_TOOL_IS_ERROR_CODE = "MCP_TOOL_IS_ERROR"
const val MCP_RESULT_INVALID_JSON_CODE = "MCP_RESULT_INVALID_JSON"
const val TOOL_CALL_CANCELLED_CODE = "TOOL_CALL_CANCELLED"

fun cancelledToolResult(toolName: String): String {
    val escaped = toolName.replace("\\", "\\\\").replace("\"", "\\\"")
    return """{"ok":false,"code":"$TOOL_CALL_CANCELLED_CODE","tool":"$escaped","message":"Tool call cancelled before completion.","retryable":false}"""
}

fun toolUseResult(resultJson: String): ToolUseResult {
    val jsonObject = try {
        Json.parseToJsonElement(resultJson) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
    return ToolUseResult(
        ok = jsonObject?.stringField("ok")?.toBooleanStrictOrNull() ?: !resultJson.contains("\"ok\":false"),
        resultJson = resultJson,
        retryable = jsonObject?.stringField("retryable")?.toBooleanStrictOrNull() ?: false,
        code = jsonObject?.stringField("code") ?: jsonObject?.stringField("errorCode"),
    )
}

/**
 * Backend-call view of a tool result: additionally recognizes MCP `tools/call`
 * envelopes whose payload carries `isError: true` (JSON-RPC `result.isError` or a
 * top-level `isError`). Providers may report transport-level success while the
 * tool itself failed. Typed business refusals remain failures for completion,
 * but do not imply an unavailable transport or count toward its circuit breaker.
 */
fun backendToolCallResult(resultJson: String): ToolUseResult {
    val jsonObject = backendResultObject(resultJson) ?: return ToolUseResult(
        ok = false,
        resultJson = resultJson,
        retryable = false,
        code = MCP_RESULT_INVALID_JSON_CODE,
    )
    val base = toolUseResult(resultJson)
    if (jsonObject["error"] != null && jsonObject["error"] !is JsonNull) {
        return base.copy(ok = false, code = base.code ?: MCP_TOOL_IS_ERROR_CODE)
    }
    val status = mcpBusinessStatus(jsonObject)
    if (status != null && (!base.ok || mcpEnvelopeIsError(jsonObject))) {
        return base.copy(ok = false, code = status, retryable = false)
    }
    if (!base.ok || !mcpEnvelopeIsError(jsonObject)) return base
    return base.copy(ok = false, code = base.code ?: MCP_TOOL_IS_ERROR_CODE)
}

/** A delivered MCP business result, not evidence of a broken connection. */
internal fun isMcpBusinessResult(resultJson: String): Boolean {
    val root = backendResultObject(resultJson) ?: return false
    if (root["error"] != null && root["error"] !is JsonNull) return false
    return mcpBusinessStatus(root) != null
}

private fun mcpBusinessStatus(root: JsonObject): String? {
    val payload = root["result"] as? JsonObject ?: root
    val content = payload["structuredContent"] as? JsonObject ?: return null
    val status = content["status"] as? JsonObject ?: return null
    val name = status["name"] as? JsonPrimitive ?: return null
    return name.contentOrNull?.takeIf { name.isString && it.isNotBlank() }
}

private fun backendResultObject(resultJson: String): JsonObject? =
    try {
        Json.parseToJsonElement(resultJson) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun mcpEnvelopeIsError(jsonObject: JsonObject): Boolean {
    val topLevelError = (jsonObject["isError"] as? kotlinx.serialization.json.JsonPrimitive)
        ?.booleanOrNull == true
    val nestedError = ((jsonObject["result"] as? JsonObject)?.get("isError") as? kotlinx.serialization.json.JsonPrimitive)
        ?.booleanOrNull == true
    return topLevelError || nestedError
}

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

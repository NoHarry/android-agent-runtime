package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

sealed interface ParsedToolArguments {
    data class Valid(val arguments: JsonObject) : ParsedToolArguments

    data class Invalid(val resultJson: String) : ParsedToolArguments
}

fun parseToolArguments(toolName: String, argumentsJson: String): ParsedToolArguments {
    val element = try {
        Json.parseToJsonElement(argumentsJson)
    } catch (_: Exception) {
        return ParsedToolArguments.Invalid(schemaArgumentsInvalidResult(toolName))
    }
    return if (element is JsonObject) {
        ParsedToolArguments.Valid(element)
    } else {
        ParsedToolArguments.Invalid(schemaArgumentsInvalidResult(toolName))
    }
}

private fun schemaArgumentsInvalidResult(toolName: String): String {
    val escaped = toolName.replace("\\", "\\\\").replace("\"", "\\\"")
    val code = RuntimeErrorCode.SCHEMA_VALIDATION_FAILED.wireCode
    return """{"ok":false,"code":"$code","tool":"$escaped","message":"Tool $escaped arguments must be a JSON object.","retryable":false}"""
}

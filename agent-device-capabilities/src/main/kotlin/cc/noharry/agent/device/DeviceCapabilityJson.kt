package cc.noharry.agent.device

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun deviceErrorJson(code: String, message: String, retryable: Boolean = false): String =
    """{"ok":false,"code":"${code.escapeJson()}","message":"${message.escapeJson()}","retryable":$retryable}"""

internal fun deviceSuccessJson(fields: String): String =
    """{"ok":true,$fields}"""

internal fun parseObject(json: String): JsonObject? =
    runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()

internal fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.longField(name: String): Long? =
    stringField(name)?.toLongOrNull()

internal fun JsonObject.intField(name: String): Int? =
    stringField(name)?.toIntOrNull()

internal fun String.escapeJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

internal const val DEVICE_PERMISSION_DENIED = "DEVICE_PERMISSION_DENIED"
internal const val DEVICE_CAPABILITY_UNAVAILABLE = "DEVICE_CAPABILITY_UNAVAILABLE"
internal const val DEVICE_ARGUMENT_INVALID = "DEVICE_ARGUMENT_INVALID"
internal const val DEVICE_CALL_FAILED = "DEVICE_CALL_FAILED"

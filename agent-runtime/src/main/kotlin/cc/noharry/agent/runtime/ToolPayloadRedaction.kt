package cc.noharry.agent.runtime

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Preserve JSON types and syntax while removing credentials and applying the host's text policy. */
internal fun redactToolPayload(value: String, redactor: TraceRedactor): String {
    val parsed = try { Json.parseToJsonElement(value) } catch (_: IllegalArgumentException) { null }
    return parsed?.redactedToolValue(redactor)?.toString() ?: redactor.redact(redactSecrets(value))
}

private fun JsonElement.redactedToolValue(redactor: TraceRedactor): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { (key, value) ->
        if (key.isCredentialField()) JsonPrimitive("[REDACTED_SECRET]") else value.redactedToolValue(redactor)
    })
    is JsonArray -> JsonArray(map { it.redactedToolValue(redactor) })
    is JsonPrimitive -> when {
        !isString -> this
        // MCP text content often carries a JSON document as a string: filtering it as text would break its syntax.
        else -> nestedJson(content)?.let { JsonPrimitive(it.redactedToolValue(redactor).toString()) }
            ?: JsonPrimitive(redactor.redact(redactSecrets(content)))
    }
}

private fun nestedJson(text: String): JsonElement? {
    val trimmed = text.trim()
    if (!(trimmed.startsWith("{") && trimmed.endsWith("}")) && !(trimmed.startsWith("[") && trimmed.endsWith("]"))) return null
    return try { Json.parseToJsonElement(trimmed) } catch (_: IllegalArgumentException) { null }
}

private fun String.isCredentialField(): Boolean {
    val normalized = lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    return normalized in setOf("authorization", "proxyauthorization", "bearer", "cookie", "setcookie") ||
        CREDENTIAL_SUFFIXES.any(normalized::endsWith)
}

private val CREDENTIAL_SUFFIXES = listOf("token", "apikey", "secret", "password", "credential", "credentials", "privatekey")

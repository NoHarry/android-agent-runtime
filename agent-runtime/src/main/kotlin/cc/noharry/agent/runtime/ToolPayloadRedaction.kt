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
    is JsonPrimitive -> if (isString) JsonPrimitive(redactor.redact(redactSecrets(content))) else this
}

private fun String.isCredentialField(): Boolean {
    val normalized = lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    return normalized in setOf("authorization", "proxyauthorization", "bearer", "cookie", "setcookie") ||
        CREDENTIAL_SUFFIXES.any(normalized::endsWith)
}

private val CREDENTIAL_SUFFIXES = listOf("token", "apikey", "secret", "password", "credential", "credentials", "privatekey")

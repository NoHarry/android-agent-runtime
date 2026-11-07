package cc.noharry.agent.runtime

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Preserve JSON types and syntax while removing credentials and applying the host's text policy. */
internal fun redactToolPayload(value: String, redactor: TraceRedactor): String {
    val parsed = if (nestingDepth(value) > MAX_JSON_DEPTH) null
        else try { Json.parseToJsonElement(value) } catch (_: IllegalArgumentException) { null }
    return parsed?.redactedToolValue(redactor, 0)?.toString() ?: redactor.redact(redactSecrets(value))
}

/** Past [MAX_JSON_DEPTH] levels a value is filtered as text, so a hostile document cannot exhaust the stack. */
private fun JsonElement.redactedToolValue(redactor: TraceRedactor, depth: Int): JsonElement = when {
    depth >= MAX_JSON_DEPTH -> JsonPrimitive(redactor.redact(redactSecrets(toString())))
    this is JsonObject -> JsonObject(mapValues { (key, value) ->
        if (key.isCredentialField()) JsonPrimitive("[REDACTED_SECRET]") else value.redactedToolValue(redactor, depth + 1)
    })
    this is JsonArray -> JsonArray(map { it.redactedToolValue(redactor, depth + 1) })
    this is JsonPrimitive && !isString -> this
    // MCP text content often carries a JSON document as a string: filtering it as text would break its syntax.
    else -> (this as JsonPrimitive).let { text ->
        nestedJson(text.content)?.let { JsonPrimitive(it.redactedToolValue(redactor, depth + 1).toString()) }
            ?: JsonPrimitive(redactor.redact(redactSecrets(text.content)))
    }
}

private const val MAX_JSON_DEPTH = 64

/** How deep brackets nest outside string literals, read without parsing (which a hostile document could overflow). */
private fun nestingDepth(text: String): Int {
    var depth = 0
    var deepest = 0
    var inString = false
    var escaped = false
    for (c in text) {
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{', '[' -> { depth += 1; if (depth > deepest) deepest = depth }
            '}', ']' -> depth -= 1
        }
    }
    return deepest
}

private fun nestedJson(text: String): JsonElement? {
    val trimmed = text.trim()
    if (!(trimmed.startsWith("{") && trimmed.endsWith("}")) && !(trimmed.startsWith("[") && trimmed.endsWith("]"))) return null
    if (nestingDepth(trimmed) > MAX_JSON_DEPTH) return null
    return try { Json.parseToJsonElement(trimmed) } catch (_: IllegalArgumentException) { null }
}

private fun String.isCredentialField(): Boolean {
    val normalized = lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    return normalized in setOf("authorization", "proxyauthorization", "bearer", "cookie", "setcookie") ||
        CREDENTIAL_SUFFIXES.any(normalized::endsWith)
}

private val CREDENTIAL_SUFFIXES = listOf("token", "apikey", "secret", "password", "credential", "credentials", "privatekey")

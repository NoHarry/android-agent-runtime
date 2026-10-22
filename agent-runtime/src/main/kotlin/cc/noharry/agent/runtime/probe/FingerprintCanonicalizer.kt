package cc.noharry.agent.runtime.probe

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale

/**
 * ConfigCanonical + SchemaCanonical (SPEC-RPB-003 / B6).
 * Schema numbers use exact BigDecimal only — never Double/Float.
 */
internal object FingerprintCanonicalizer {

    fun sha256Hex(utf8: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(utf8.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    fun sha256CanonicalJson(value: Any?): String = sha256Hex(configCanonical(value))

    fun configFingerprint(resolved: ResolvedConfigObject): String =
        sha256Hex(configCanonical(resolvedToMap(resolved)))

    fun controlFingerprint(resolved: ResolvedConfigObject, declaredVariables: List<String>): String {
        val map = resolvedToMap(resolved).toMutableMap()
        for (id in declaredVariables) {
            deleteVariablePath(map, id)
        }
        return sha256Hex(configCanonical(map))
    }

    fun toolsetFingerprint(toolset: List<ToolCatalogEntry>): String {
        val rows = toolset.sortedBy { it.qualifiedName }.map {
            linkedMapOf(
                "qualifiedName" to it.qualifiedName,
                "schemaDigest" to it.schemaDigest,
                "source" to it.source,
            )
        }
        return sha256Hex(configCanonical(rows))
    }

    fun schemaDigestFromCanonicalText(canonicalText: String): String = sha256Hex(canonicalText)

    /**
     * SchemaCanonical for a pre-parsed decimal-preserving tree.
     * Numbers must already be BigDecimal (or integer types); never Double.
     */
    fun schemaCanonical(value: Any?): String = schemaCanonicalValue(value)

    fun schemaDigest(value: Any?): String = sha256Hex(schemaCanonical(value))

    /** Parse JSON number token with exact decimal; rejects IEEE double path. */
    fun parseExactDecimalToken(token: String): BigDecimal {
        require(token.isNotBlank()) { "empty number token" }
        require(!token.contains(Regex("[iInN]"))) { "NaN/Infinity not allowed" }
        return BigDecimal(token)
    }

    /** Emit canonical number string from exact decimal (B6.2b step 3). */
    fun emitExactDecimal(d: BigDecimal): String {
        val normalized = d.stripTrailingZeros()
        return if (normalized.scale() <= 0) {
            normalized.toBigInteger().toString()
        } else {
            // Plain string without exponent; stripTrailingZeros already applied.
            val plain = normalized.toPlainString()
            // Ensure not scientific
            plain
        }
    }

    // --- ConfigCanonical (no floats) ---

    fun configCanonical(value: Any?): String =
        when (value) {
            null -> "null"
            is Boolean -> if (value) "true" else "false"
            is String -> encodeJsonString(value)
            is Int, is Long, is Short, is Byte -> value.toString()
            is BigDecimal -> {
                require(value.scale() <= 0 || value.stripTrailingZeros().scale() <= 0) {
                    "ConfigCanonical forbids non-integer numbers"
                }
                emitExactDecimal(value)
            }
            is Double, is Float -> error("ConfigCanonical forbids floating point")
            is Map<*, *> -> {
                val entries = value.entries
                    .map { (k, v) -> (k as? String) to v }
                    .filter { it.first != null }
                    .sortedBy { it.first!!.toByteArray(Charsets.UTF_8).contentHashCode().let { _ -> it.first!! } }
                    // Sort by UTF-8 byte order via string comparison (BMP-safe for keys)
                    .sortedWith(compareBy { it.first!! })
                buildString {
                    append('{')
                    entries.forEachIndexed { index, (k, v) ->
                        if (index > 0) append(',')
                        append(encodeJsonString(k!!))
                        append(':')
                        append(configCanonical(v))
                    }
                    append('}')
                }
            }
            is List<*> -> buildString {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    append(configCanonical(item))
                }
                append(']')
            }
            is Number -> {
                // Integer-only box types already handled; reject others
                val bd = BigDecimal(value.toString())
                require(bd.stripTrailingZeros().scale() <= 0) { "ConfigCanonical forbids floats" }
                emitExactDecimal(bd)
            }
            else -> encodeJsonString(value.toString())
        }

    private fun schemaCanonicalValue(value: Any?): String =
        when (value) {
            null -> "null"
            is Boolean -> if (value) "true" else "false"
            is String -> encodeJsonString(value)
            is BigDecimal -> emitExactDecimal(value)
            is Int, is Long, is Short, is Byte -> value.toString()
            is Double, is Float -> error("SchemaCanonical forbids Double/Float intermediate")
            is Map<*, *> -> {
                val entries = value.entries
                    .map { (k, v) -> (k as? String) to v }
                    .filter { it.first != null }
                    .sortedWith(compareBy { it.first!! })
                buildString {
                    append('{')
                    entries.forEachIndexed { index, (k, v) ->
                        if (index > 0) append(',')
                        append(encodeJsonString(k!!))
                        append(':')
                        append(schemaCanonicalValue(v))
                    }
                    append('}')
                }
            }
            is List<*> -> buildString {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    append(schemaCanonicalValue(item))
                }
                append(']')
            }
            else -> encodeJsonString(value.toString())
        }

    private fun resolvedToMap(r: ResolvedConfigObject): MutableMap<String, Any?> =
        linkedMapOf(
            "backend" to r.backend,
            "baseUrlHost" to r.baseUrlHost,
            "buildId" to r.buildId,
            "casePackId" to r.casePackId,
            "enabledProbePacks" to r.enabledProbePacks,
            "fetchMode" to r.fetchMode,
            "isolation" to linkedMapOf(
                "cache" to r.isolation.cache,
                "memory" to r.isolation.memory,
                "session" to r.isolation.session,
            ),
            "model" to r.model,
            "modelRef" to r.modelRef,
            "profile" to r.profile,
            "promptSha256" to r.promptSha256,
            "protocol" to r.protocol,
            "provider" to r.provider,
            "searchMode" to r.searchMode,
            "stream" to r.stream,
            "toolsetFingerprint" to r.toolsetFingerprint,
            "variant" to r.variant,
        )

    private fun deleteVariablePath(map: MutableMap<String, Any?>, id: String) {
        when (id) {
            "provider" -> map.remove("provider")
            "protocol" -> map.remove("protocol")
            "model" -> map.remove("model")
            "modelRef" -> map.remove("modelRef")
            "profile" -> map.remove("profile")
            "variant" -> map.remove("variant")
            "baseUrlHost" -> map.remove("baseUrlHost")
            "searchMode" -> map.remove("searchMode")
            "fetchMode" -> map.remove("fetchMode")
            "stream" -> map.remove("stream")
            "backend" -> map.remove("backend")
            "buildId" -> map.remove("buildId")
            "enabledProbePacks" -> map.remove("enabledProbePacks")
            "casePackId" -> map.remove("casePackId")
            "promptSha256" -> map.remove("promptSha256")
            "toolsetFingerprint" -> map.remove("toolsetFingerprint")
            "isolation" -> map.remove("isolation")
            "isolation.session", "isolation.memory", "isolation.cache" -> {
                val leaf = id.removePrefix("isolation.")
                val iso = (map["isolation"] as? Map<*, *>)
                    ?.entries
                    ?.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }
                    ?.toMap()
                    ?.toMutableMap()
                if (iso != null) {
                    iso.remove(leaf)
                    map["isolation"] = iso
                }
            }
            else -> error("unknown variable id: $id")
        }
    }

    private fun encodeJsonString(s: String): String {
        val sb = StringBuilder()
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u%04x".format(Locale.US, ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

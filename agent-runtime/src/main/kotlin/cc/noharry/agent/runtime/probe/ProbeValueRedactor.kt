package cc.noharry.agent.runtime.probe

/**
 * Value-level redaction for probe attrs (SPEC-RPB-009).
 * Attribute keys are already filtered by catalog; this redacts sensitive values.
 */
internal object ProbeValueRedactor {
    private val secretKeyHints = listOf(
        "password", "passwd", "secret", "token", "api_key", "apikey",
        "authorization", "credential", "cookie", "session_id",
    )
    private val secretPatterns = listOf(
        Regex("""(?i)sk-[A-Za-z0-9]{16,}"""),
        Regex("""(?i)Bearer\s+[A-Za-z0-9._\-]{8,}"""),
        Regex("""(?i)(api[_-]?key|token|password)\s*[:=]\s*\S+"""),
    )
    private val preciseCoord = Regex("""-?\d{1,3}\.\d{4,}""")
    private val urlQuery = Regex("""(\?|&)([^=&\s]+)=([^&\s]+)""")

    fun redactString(key: String, value: String): String {
        val keyLower = key.lowercase()
        if (secretKeyHints.any { it in keyLower }) {
            return REDACTED
        }
        var out = value
        for (pat in secretPatterns) {
            out = pat.replace(out, REDACTED)
        }
        // probe.error.message / free text: strip long secrets
        if (key == "message" || key == "path" || key == "host") {
            out = redactUrlish(out)
            out = preciseCoord.replace(out, REDACTED_COORD)
        }
        return out
    }

    fun redactAttrs(attrs: Map<String, Any?>): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        for ((k, v) in attrs) {
            out[k] = redactValue(k, v)
        }
        return out
    }

    private fun redactValue(key: String, value: Any?): Any? =
        when (value) {
            null -> null
            is String -> redactString(key, value)
            is Map<*, *> -> {
                val nested = linkedMapOf<String, Any?>()
                for ((nk, nv) in value) {
                    val nestedKey = nk as? String ?: continue
                    nested[nestedKey] = redactValue(nestedKey, nv)
                }
                nested
            }
            is List<*> -> value.map { item ->
                when (item) {
                    is String -> redactString(key, item)
                    is Map<*, *> -> redactValue(key, item)
                    else -> item
                }
            }
            else -> value
        }

    private fun redactUrlish(value: String): String {
        var out = value
        out = urlQuery.replace(out) { m ->
            val sep = m.groupValues[1]
            val name = m.groupValues[2]
            "$sep$name=$REDACTED"
        }
        // strip credentials in userinfo@host
        out = out.replace(Regex("""//[^/\s]+:[^/\s]+@"""), "//$REDACTED@$")
        return out
    }

    const val REDACTED = "[REDACTED]"
    const val REDACTED_COORD = "[COORD]"
}

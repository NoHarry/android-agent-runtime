package cc.noharry.agent.kernel.api

/**
 * Parses the diagnostic `namespace:name:major` form. String identity is never a
 * capability lookup key; [CapabilityKey] object identity remains authoritative.
 */
@ExperimentalAgentRuntimeApi
internal object CapabilityDiagnostic {
    const val DEFAULT_MAJOR: Int = 1

    private val fullForm =
        Regex("^([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*):([A-Za-z][A-Za-z0-9_.-]*):([1-9][0-9]*)$")
    private val simpleName = Regex("^[A-Za-z][A-Za-z0-9_.-]*$")

    fun validate(raw: String) {
        require(raw.isNotBlank()) { PluginContractMessages.BLANK_CAPABILITY_NAME }
        require(parseOrNull(raw) != null) { PluginContractMessages.INVALID_CAPABILITY_NAME }
    }

    fun format(namespace: String, name: String, major: Int): String {
        val rendered = "$namespace:$name:$major"
        validate(rendered)
        return rendered
    }

    fun parse(raw: String): Parsed {
        validate(raw)
        return requireNotNull(parseOrNull(raw))
    }

    private fun parseOrNull(raw: String): Parsed? {
        val full = fullForm.matchEntire(raw)
        if (full != null) {
            return Parsed(full.groupValues[1], full.groupValues[2], full.groupValues[3].toInt())
        }
        if (simpleName.matches(raw) && ':' !in raw) {
            return Parsed(PluginContractMessages.DEFAULT_NAMESPACE, raw, DEFAULT_MAJOR)
        }
        return null
    }

    class Parsed(
        val namespace: String,
        val localName: String,
        val major: Int,
    ) {
        val id: String = "$namespace:$localName:$major"
    }
}

package cc.noharry.agent.kernel.api

/**
 * Candidate typed identity for a capability supplied by an [AgentPlugin].
 *
 * A key instance is the only runtime lookup identity. [namespace], [localName] and
 * [major] form a diagnostic `namespace:name:major` string and never merge independently
 * created keys. This type intentionally has no equals/hashCode override.
 */
@ExperimentalAgentRuntimeApi
class CapabilityKey<T : Any>(
    val name: String,
    val cardinality: CapabilityCardinality,
) {
    init {
        CapabilityDiagnostic.validate(name)
    }

    /** Diagnostic namespace; not a lookup identity. */
    val namespace: String
        get() = CapabilityDiagnostic.parse(name).namespace

    /** Diagnostic local name; not a lookup identity. */
    val localName: String
        get() = CapabilityDiagnostic.parse(name).localName

    /** Diagnostic major; used only for compatibility preflight. */
    val major: Int
        get() = CapabilityDiagnostic.parse(name).major

    /** Diagnostic `namespace:name:major`. Never used as a registry key. */
    val diagnosticId: String
        get() = CapabilityDiagnostic.parse(name).id

    override fun toString(): String =
        "CapabilityKey(diagnosticId=$diagnosticId, cardinality=$cardinality)"

    companion object {
        /** Creates a key whose [name] stores the validated diagnostic triple. */
        fun <T : Any> of(
            namespace: String,
            name: String,
            major: Int,
            cardinality: CapabilityCardinality,
        ): CapabilityKey<T> =
            CapabilityKey(CapabilityDiagnostic.format(namespace, name, major), cardinality)
    }
}

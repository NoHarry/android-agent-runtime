package cc.noharry.agent.a2a

import java.util.Locale

/** A copied protected-header container whose string form never contains values. */
@ExperimentalA2aClientApi
public class A2aProtectedHeaders private constructor(
    private val values: Map<String, String>,
) {
    /** Visits protected values for immediate request construction. Callers must not retain them. */
    public fun forEachValue(block: (name: String, value: String) -> Unit) {
        values.forEach(block)
    }

    /** Returns only normalized header names for diagnostics or validation. */
    public fun names(): Set<String> = values.keys.toSet()

    override fun toString(): String = "A2aProtectedHeaders(names=${values.keys.sorted()})"

    /** Creates a copied protected-header container. */
    public companion object {
        /** Copies [headers] and normalizes names using a locale-independent representation. */
        public fun of(headers: Map<String, String>): A2aProtectedHeaders = A2aProtectedHeaders(
            headers.entries.associate { (name, value) ->
                require(name.isNotBlank()) { "Header name must not be blank" }
                name.lowercase(Locale.ROOT) to value
            },
        )

        /** Returns an empty protected-header container. */
        public fun empty(): A2aProtectedHeaders = A2aProtectedHeaders(emptyMap())
    }
}

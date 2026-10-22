package cc.noharry.agent.a2a

/** JSON-compatible data value that does not expose a JSON library type. */
@ExperimentalA2aClientApi
public sealed interface A2aValue {
    /** A JSON null value. */
    public data object Null : A2aValue
    /** A boolean value. */
    public data class BooleanValue(public val value: Boolean) : A2aValue
    /** A finite numeric value. */
    public data class NumberValue(public val value: Double) : A2aValue
    /** A string value. */
    public data class StringValue(public val value: String) : A2aValue
    /** An ordered list value. */
    public data class ListValue(public val values: List<A2aValue>) : A2aValue
    /** A string-keyed object value. */
    public data class ObjectValue(public val values: Map<String, A2aValue>) : A2aValue
}

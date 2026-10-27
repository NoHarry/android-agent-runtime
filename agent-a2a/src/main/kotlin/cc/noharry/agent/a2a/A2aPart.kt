package cc.noharry.agent.a2a

/** Content part carried by an A2A message. */
@ExperimentalA2aClientApi
public sealed interface A2aPart {
    /** Plain text content. */
    public data class Text(public val text: String) : A2aPart
    /** Structured JSON-compatible content. */
    public data class Data(public val value: A2aValue) : A2aPart
}

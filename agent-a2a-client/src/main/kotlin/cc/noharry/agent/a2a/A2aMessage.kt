package cc.noharry.agent.a2a

/** A protocol-neutral A2A message. */
@ExperimentalA2aClientApi
public data class A2aMessage(
    public val messageId: String? = null,
    public val role: Role,
    public val parts: List<A2aPart>,
    public val contextId: String? = null,
    public val taskId: String? = null,
    /** Optional message metadata, such as a routing `channel` or a reply `questionId`. */
    public val metadata: A2aValue.ObjectValue? = null,
) {
    /** Message author role. */
    public enum class Role {
        /** Message sent by the caller. */
        USER,
        /** Message sent by the remote Agent. */
        AGENT,
    }
}

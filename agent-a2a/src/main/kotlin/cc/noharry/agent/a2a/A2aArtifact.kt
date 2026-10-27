package cc.noharry.agent.a2a

/** Output artifact produced by a remote task. */
@ExperimentalA2aClientApi
public data class A2aArtifact(
    public val artifactId: String,
    public val name: String? = null,
    public val parts: List<A2aPart>,
    /** Optional artifact metadata, such as a consumer `channel`. */
    public val metadata: A2aValue.ObjectValue? = null,
)

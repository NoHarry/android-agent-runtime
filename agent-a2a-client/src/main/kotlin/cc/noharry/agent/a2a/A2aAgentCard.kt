package cc.noharry.agent.a2a

import java.net.URI

/** Validated public description of a remote A2A Agent. */
@ExperimentalA2aClientApi
public data class A2aAgentCard(
    public val name: String,
    public val description: String? = null,
    public val url: URI,
    public val protocolVersion: String? = null,
    public val streaming: Boolean = false,
)

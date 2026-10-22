package cc.noharry.agent.a2a

import java.net.URI

/** Identifies one explicitly selected remote Agent and its endpoint. */
@ExperimentalA2aClientApi
public data class RemoteAgentRef(
    public val id: String,
    public val endpoint: URI,
    public val cardUrl: URI? = null,
    public val expectedIdentity: String? = null,
)

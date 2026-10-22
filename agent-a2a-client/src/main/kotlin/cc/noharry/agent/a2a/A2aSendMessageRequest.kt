package cc.noharry.agent.a2a

/** Request for synchronous or streaming message exchange. */
@ExperimentalA2aClientApi
public data class A2aSendMessageRequest(
    public val message: A2aMessage,
)

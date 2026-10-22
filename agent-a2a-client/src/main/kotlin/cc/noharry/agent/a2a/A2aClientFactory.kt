package cc.noharry.agent.a2a

/** Creates isolated outbound A2A clients. */
@ExperimentalA2aClientApi
public fun interface A2aClientFactory {
    /** Creates a client using [config]. */
    public fun create(config: A2aClientConfig): A2aClient
}

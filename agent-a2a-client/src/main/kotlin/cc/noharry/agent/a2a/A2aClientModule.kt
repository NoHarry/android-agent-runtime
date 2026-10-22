package cc.noharry.agent.a2a

/** Supplies an A2A client factory without exposing a transport implementation. */
@ExperimentalA2aClientApi
public interface A2aClientModule {
    /** Returns the factory owned by this module. */
    public fun factory(): A2aClientFactory
}

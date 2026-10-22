package cc.noharry.agent.a2a

/** Supplies protected request headers immediately before each network request. */
@ExperimentalA2aClientApi
public fun interface A2aAuthProvider {
    /** Returns fresh headers for [context]. Values must not be cached or logged by clients. */
    public suspend fun headers(context: A2aAuthContext): A2aProtectedHeaders

    /** Authentication provider that supplies no headers. */
    public data object None : A2aAuthProvider {
        override suspend fun headers(context: A2aAuthContext): A2aProtectedHeaders =
            A2aProtectedHeaders.empty()
    }
}

package cc.noharry.agent.kernel.api

/**
 * Candidate factory that creates one session-scoped capability instance.
 *
 * Direct [ProviderConcurrencyMode.SESSION_FACTORY] instances are invalid; this
 * factory is the only supported registration route.
 */
@ExperimentalAgentRuntimeApi
fun interface SessionCapabilityFactory<T : Any> {
    /** Creates the instance owned by [context]'s session. */
    fun create(context: SessionCapabilityContext): T
}

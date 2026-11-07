package cc.noharry.agent.spi

/**
 * Factory that creates one session-scoped capability instance.
 *
 * Direct [ProviderConcurrencyMode.SESSION_FACTORY] instances are invalid; this
 * factory is the only supported registration route.
 */
@ExperimentalAgentRuntimeApi
internal fun interface SessionCapabilityFactory<T : Any> {
    /** Creates the instance owned by [context]'s session. */
    fun create(context: SessionCapabilityContext): T
}

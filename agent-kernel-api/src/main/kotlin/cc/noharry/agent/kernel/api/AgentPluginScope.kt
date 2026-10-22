package cc.noharry.agent.kernel.api

/** Candidate installation scope exposed to a host-packaged [AgentPlugin]. */
@ExperimentalAgentRuntimeApi
interface AgentPluginScope {
    /**
     * Provides one shared value for [key] with an explicit concurrency mode.
     * [ProviderConcurrencyMode.SESSION_FACTORY] is rejected; use a factory overload.
     */
    fun <T : Any> provide(
        key: CapabilityKey<T>,
        value: T,
        concurrency: ProviderConcurrencyDeclaration,
    )

    /**
     * Registers a session factory with [ProviderConcurrencyMode.SESSION_FACTORY]
     * bounded to [ProviderConcurrencyDeclaration.DEFAULT_BOUNDED_MAX_CONCURRENT_CALLS].
     * This convenience exists only because the meaning is explicit SESSION_FACTORY.
     */
    fun <T : Any> provide(
        key: CapabilityKey<T>,
        factory: SessionCapabilityFactory<T>,
    ) {
        provide(
            key,
            factory,
            ProviderConcurrencyDeclaration.sessionFactory(
                ProviderConcurrencyDeclaration.DEFAULT_BOUNDED_MAX_CONCURRENT_CALLS,
            ),
        )
    }

    /**
     * Registers a session factory. The only valid [ProviderConcurrencyMode.SESSION_FACTORY]
     * provision route. Required on every scope implementation.
     */
    fun <T : Any> provide(
        key: CapabilityKey<T>,
        factory: SessionCapabilityFactory<T>,
        concurrency: ProviderConcurrencyDeclaration,
    )

    /** Resolves an optional single-cardinality shared capability visible to this installation. */
    fun <T : Any> resolve(key: CapabilityKey<T>): T?

    /** Resolves all multiple-cardinality shared capabilities visible to this installation. */
    fun <T : Any> resolveAll(key: CapabilityKey<T>): List<T>

    /** Transfers resource ownership to the runtime and returns [resource] for convenient wiring. */
    fun <T : AutoCloseable> own(resource: T): T
}

package cc.noharry.agent.kernel.api

/**
 * Candidate provider/capability concurrency mode.
 *
 * This is metadata only: it does not carry payload, session state, or factory instances.
 * [RUNTIME_SHARED_SERIALIZED] is fail-safe non-reentrant: a same-coroutine nested call waits
 * on the same permit. A sound same-coroutine/non-child reentrancy model remains factory
 * composition debt, not a coroutine-context bypass.
 *
 * [SESSION_FACTORY] is realized only through [SessionCapabilityFactory] registration.
 * Direct SESSION_FACTORY instances remain invalid. Session drain evicts realized instances.
 */
@ExperimentalAgentRuntimeApi
enum class ProviderConcurrencyMode {
    RUNTIME_SHARED_THREAD_SAFE,
    RUNTIME_SHARED_SERIALIZED,
    SESSION_FACTORY,
}

/**
 * Bounded immutable concurrency declaration for a provider or model client instance.
 *
 * [maxConcurrentCalls] is a positive hard cap. Serialized declarations must be exactly 1.
 */
@ExperimentalAgentRuntimeApi
data class ProviderConcurrencyDeclaration internal constructor(
    val mode: ProviderConcurrencyMode,
    val maxConcurrentCalls: Int,
) {
    init {
        require(maxConcurrentCalls > 0) { "maxConcurrentCalls must be positive." }
        if (mode == ProviderConcurrencyMode.RUNTIME_SHARED_SERIALIZED) {
            require(maxConcurrentCalls == 1) { "serialized provider concurrency must be exactly 1." }
        }
    }

    companion object {
        const val DEFAULT_BOUNDED_MAX_CONCURRENT_CALLS = 4

        fun threadSafe(
            maxConcurrentCalls: Int = DEFAULT_BOUNDED_MAX_CONCURRENT_CALLS,
        ): ProviderConcurrencyDeclaration =
            ProviderConcurrencyDeclaration(
                mode = ProviderConcurrencyMode.RUNTIME_SHARED_THREAD_SAFE,
                maxConcurrentCalls = maxConcurrentCalls,
            )

        fun serialized(): ProviderConcurrencyDeclaration =
            ProviderConcurrencyDeclaration(
                mode = ProviderConcurrencyMode.RUNTIME_SHARED_SERIALIZED,
                maxConcurrentCalls = 1,
            )

        fun sessionFactory(maxConcurrentCalls: Int): ProviderConcurrencyDeclaration =
            ProviderConcurrencyDeclaration(
                mode = ProviderConcurrencyMode.SESSION_FACTORY,
                maxConcurrentCalls = maxConcurrentCalls,
            )
    }
}

package cc.noharry.agent.spi

/** Cardinality for a typed runtime capability key. */
@ExperimentalAgentRuntimeApi
internal enum class CapabilityCardinality {
    SINGLE,
    MULTIPLE,
}

package cc.noharry.agent.kernel.api

/** Candidate cardinality for a typed runtime capability key. Not frozen until API review closes. */
@ExperimentalAgentRuntimeApi
enum class CapabilityCardinality {
    SINGLE,
    MULTIPLE,
}

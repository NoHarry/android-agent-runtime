package cc.noharry.agent.kernel.api

/**
 * Marks candidate Android agent runtime APIs that are intentionally outside the stable set.
 *
 * This annotation is part of the API-freeze candidate process and does not close API freeze.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This Android agent runtime API is experimental until the API freeze review closes.",
)
annotation class ExperimentalAgentRuntimeApi

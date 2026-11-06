package cc.noharry.agent.kernel.api

/**
 * Marks agent runtime APIs that are the likeliest to change, possibly in a minor release. Using one without an
 * opt-in is a compile error.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This agent runtime API is experimental and may change in a minor release.",
)
annotation class ExperimentalAgentRuntimeApi

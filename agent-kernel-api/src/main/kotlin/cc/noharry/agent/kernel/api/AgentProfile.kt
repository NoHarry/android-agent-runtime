package cc.noharry.agent.kernel.api

/**
 * Candidate immutable profile declaration compiled with plugins into a
 * runtime blueprint. Not a second runtime builder.
 */
@ExperimentalAgentRuntimeApi
class AgentProfile(
    val id: String,
    val kernelApiVersion: KernelApiVersion = KernelApiVersions.CURRENT,
) {
    init {
        require(id.isNotBlank()) {
            PluginContractMessages.BLANK_PROFILE_ID
        }
    }
}

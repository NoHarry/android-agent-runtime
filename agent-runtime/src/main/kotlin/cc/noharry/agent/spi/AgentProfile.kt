package cc.noharry.agent.spi

/**
 * Immutable profile declaration compiled with plugins into a
 * runtime blueprint. Not a second runtime builder.
 */
@ExperimentalAgentRuntimeApi
internal class AgentProfile(
    val id: String,
    val kernelApiVersion: KernelApiVersion = KernelApiVersions.CURRENT,
) {
    init {
        require(id.isNotBlank()) {
            PluginContractMessages.BLANK_PROFILE_ID
        }
    }
}

package cc.noharry.agent.kernel.api

/**
 * Candidate install declaration for an [AgentPlugin].
 *
 * [id] is the single identity authority. [namespace:name:major] on
 * provided/required keys is diagnostic only.
 */
@ExperimentalAgentRuntimeApi
class AgentPluginDescriptor(
    val id: String,
    val pluginVersion: PluginVersion,
    val kernelApiRange: KernelApiRange,
    val provides: Set<CapabilityKey<*>> = emptySet(),
    val requires: Set<CapabilityKey<*>> = emptySet(),
    val optionalRequires: Set<CapabilityKey<*>> = emptySet(),
) {
    init {
        require(id.isNotEmpty() && id.trim() == id) {
            PluginContractMessages.BLANK_PLUGIN_ID
        }
        require(requires.intersect(optionalRequires).isEmpty()) {
            PluginContractMessages.REQUIREMENT_OVERLAP
        }
    }
}

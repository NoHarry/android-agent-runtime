package cc.noharry.agent.kernel.api

/** Session identity passed to [SessionCapabilityFactory] realizations. */
@ExperimentalAgentRuntimeApi
class SessionCapabilityContext(
    val sessionId: String,
) {
    init {
        require(sessionId.isNotBlank()) {
            PluginContractMessages.BLANK_SESSION_ID
        }
    }
}

package cc.noharry.agent.spi

/** Session identity passed to [SessionCapabilityFactory] realizations. */
@ExperimentalAgentRuntimeApi
internal class SessionCapabilityContext(
    val sessionId: String,
) {
    init {
        require(sessionId.isNotBlank()) {
            PluginContractMessages.BLANK_SESSION_ID
        }
    }
}

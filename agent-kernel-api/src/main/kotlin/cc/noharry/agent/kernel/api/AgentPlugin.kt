package cc.noharry.agent.kernel.api

/**
 * Candidate host-packaged runtime plugin. Dynamic or downloaded executable plugins
 * are unsupported. [descriptor] is abstract and the sole install authority.
 * [id] may default to [AgentPluginDescriptor.id].
 */
@ExperimentalAgentRuntimeApi
interface AgentPlugin {
    /**
     * Install declaration. Required and the sole identity, version, kernel-range,
     * and provides/requires authority.
     */
    val descriptor: AgentPluginDescriptor

    /**
     * Stable identifier. Defaults to [descriptor].id so a plugin that only
     * overrides [descriptor] stays consistent. Install preflight rejects a
     * plugin that overrides both properties to different values.
     */
    val id: String
        get() = descriptor.id

    /** Supplies capabilities and owned resources within one transactional installation. */
    fun install(scope: AgentPluginScope)
}

@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.plugin
import cc.noharry.agent.kernel.api.AgentPlugin
import cc.noharry.agent.kernel.api.AgentProfile
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.KernelApiVersion
import cc.noharry.agent.kernel.api.KernelApiVersions
import cc.noharry.agent.kernel.api.PluginContractMessages

/**
 * Compiles an [AgentProfile] and packaged plugins into an immutable
 * [RuntimeBlueprint]. This is the only new composition path; it is not a
 * second runtime builder or execution loop.
 */
@ExperimentalAgentRuntimeApi
class ProfileCompiler {
    fun compile(
        profile: AgentProfile,
        plugins: List<AgentPlugin>,
        kernelApiVersion: KernelApiVersion = KernelApiVersions.CURRENT,
    ): RuntimeBlueprint {
        require(profile.kernelApiVersion == kernelApiVersion) {
            PluginContractMessages.PROFILE_KERNEL_MISMATCH
        }
        val registry = PluginRegistry()
        try {
            registry.installAll(plugins, kernelApiVersion)
            return RuntimeBlueprint(profile, registry)
        } catch (error: Throwable) {
            registry.close()
            throw error
        }
    }
}

@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.plugin
import cc.noharry.agent.spi.AgentPlugin
import cc.noharry.agent.spi.AgentProfile
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.KernelApiVersion
import cc.noharry.agent.spi.KernelApiVersions
import cc.noharry.agent.spi.PluginContractMessages

/**
 * Compiles an [AgentProfile] and packaged plugins into an immutable
 * [RuntimeBlueprint]. This is the only new composition path; it is not a
 * second runtime builder or execution loop.
 */
@ExperimentalAgentRuntimeApi
internal class ProfileCompiler {
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

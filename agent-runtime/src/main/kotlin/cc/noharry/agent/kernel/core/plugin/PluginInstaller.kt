@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.plugin
import cc.noharry.agent.spi.AgentPlugin
import cc.noharry.agent.spi.CapabilityKey
import cc.noharry.agent.spi.KernelApiVersion
import cc.noharry.agent.spi.KernelApiVersions
import cc.noharry.agent.spi.PluginContractMessages

internal class PluginInstaller(
    private val registry: PluginRegistry,
) {
    fun install(plugin: AgentPlugin) {
        installPrepared(listOf(plugin), KernelApiVersions.CURRENT, rollbackAllOnFailure = false)
    }

    fun installAll(plugins: List<AgentPlugin>, kernelApiVersion: KernelApiVersion) {
        installPrepared(plugins, kernelApiVersion, rollbackAllOnFailure = true)
    }

    private fun installPrepared(
        plugins: List<AgentPlugin>,
        kernelApiVersion: KernelApiVersion,
        rollbackAllOnFailure: Boolean,
    ) = synchronized(registry) {
        PluginPreflight.validateBatch(
            plugins = plugins,
            kernelApiVersion = kernelApiVersion,
            alreadyProvided = registry.providedKeys(),
            alreadyInstalledIds = registry.installedIds().toSet(),
        )
        val order = PluginGraph.build(plugins, registry.providedKeys()).sortedIds()
        val byId = plugins.associateBy { it.descriptor.id }
        try {
            order.forEach { id -> installOne(byId.getValue(id)) }
        } catch (error: Throwable) {
            if (rollbackAllOnFailure) registry.abandon()
            throw error
        }
    }

    private fun installOne(plugin: AgentPlugin) {
        val pluginId = registry.requireAvailablePluginId(plugin)
        val scope = PluginInstallScope(registry)
        try {
            plugin.install(scope)
            val bindings = scope.bindingsSnapshot()
            validateDeclaredProvides(plugin, bindings)
            registry.commit(pluginId, plugin, bindings, scope.resourcesSnapshot())
        } catch (error: Throwable) {
            scope.rollback()
            throw error
        }
    }

    private fun validateDeclaredProvides(
        plugin: AgentPlugin,
        bindings: Map<CapabilityKey<*>, List<CapabilityBinding>>,
    ) {
        val declared = plugin.descriptor.provides
        val installed = bindings.keys
        check(declared.all { key -> installed.any { it === key } } &&
            installed.all { key -> declared.any { it === key } }) {
            "${PluginContractMessages.PROVIDES_MISMATCH}: ${plugin.descriptor.id}"
        }
    }
}

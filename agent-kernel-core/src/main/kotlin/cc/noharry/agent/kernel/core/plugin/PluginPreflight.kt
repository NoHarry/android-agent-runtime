@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.plugin
import cc.noharry.agent.kernel.api.AgentPlugin
import cc.noharry.agent.kernel.api.CapabilityCardinality
import cc.noharry.agent.kernel.api.CapabilityKey
import cc.noharry.agent.kernel.api.KernelApiVersion
import cc.noharry.agent.kernel.api.PluginContractMessages

object PluginPreflight {
    fun validateBatch(
        plugins: List<AgentPlugin>,
        kernelApiVersion: KernelApiVersion,
        alreadyProvided: Set<CapabilityKey<*>> = emptySet(),
        alreadyInstalledIds: Set<String> = emptySet(),
    ) {
        validateIdentities(plugins, alreadyInstalledIds)
        validateVersions(plugins, kernelApiVersion)
        val provided = alreadyProvided + plugins.flatMap { it.descriptor.provides }
        validateAmbiguousDiagnostics(plugins, alreadyProvided)
        validateRequirements(plugins, provided)
        validateDuplicateSingles(plugins, alreadyProvided)
        PluginGraph.build(plugins, alreadyProvided).sortedIds()
    }

    fun validateBindingModes(
        existing: Map<CapabilityKey<*>, List<CapabilityBinding>>,
        incoming: Map<CapabilityKey<*>, List<CapabilityBinding>>,
    ) {
        incoming.forEach { (key, bindings) ->
            val combined = existing[key].orEmpty() + bindings
            require(!bindingsMixSharedAndFactory(combined)) {
                "${RuntimeCompositionMessages.MIXED_CAPABILITY_MODE}: ${key.diagnosticId}"
            }
        }
    }

    private fun validateIdentities(plugins: List<AgentPlugin>, alreadyInstalledIds: Set<String>) {
        val seen = LinkedHashSet<String>()
        plugins.forEach { plugin ->
            val descriptorId = plugin.descriptor.id
            require(plugin.id.trim() == descriptorId) { PluginContractMessages.PLUGIN_ID_MISMATCH }
            require(descriptorId !in alreadyInstalledIds && descriptorId !in seen) {
                "${PluginContractMessages.DUPLICATE_PLUGIN_ID}: $descriptorId"
            }
            seen += descriptorId
        }
    }

    private fun validateVersions(plugins: List<AgentPlugin>, kernelApiVersion: KernelApiVersion) {
        plugins.forEach { plugin ->
            require(plugin.descriptor.kernelApiRange.contains(kernelApiVersion)) {
                "${PluginContractMessages.INCOMPATIBLE_KERNEL_API}: ${plugin.descriptor.id}"
            }
        }
    }

    private fun validateRequirements(plugins: List<AgentPlugin>, provided: Set<CapabilityKey<*>>) {
        plugins.forEach { plugin ->
            plugin.descriptor.requires.forEach { required ->
                matchRequirement(required, provided)
            }
        }
    }

    private fun matchRequirement(required: CapabilityKey<*>, provided: Set<CapabilityKey<*>>) {
        if (provided.any { it === required }) return
        val majorClash = provided.any { key ->
            key.namespace == required.namespace &&
                key.localName == required.localName &&
                key.major != required.major
        }
        require(!majorClash) { "${PluginContractMessages.CAPABILITY_MAJOR_MISMATCH}: ${required.diagnosticId}" }
        require(false) { "${PluginContractMessages.MISSING_REQUIRED_CAPABILITY}: ${required.diagnosticId}" }
    }

    private fun validateAmbiguousDiagnostics(
        plugins: List<AgentPlugin>,
        alreadyProvided: Set<CapabilityKey<*>>,
    ) {
        val seen = LinkedHashMap<String, CapabilityKey<*>>()
        val appearing = LinkedHashSet<CapabilityKey<*>>()
        appearing.addAll(alreadyProvided)
        plugins.forEach { plugin ->
            appearing.addAll(plugin.descriptor.provides)
            appearing.addAll(plugin.descriptor.requires)
            appearing.addAll(plugin.descriptor.optionalRequires)
        }
        appearing.forEach { key ->
            val previous = seen[key.diagnosticId]
            require(previous == null || previous === key) {
                "${RuntimeCompositionMessages.AMBIGUOUS_DIAGNOSTIC_ID}: ${key.diagnosticId}"
            }
            seen[key.diagnosticId] = key
        }
    }

    private fun validateDuplicateSingles(
        plugins: List<AgentPlugin>,
        alreadyProvided: Set<CapabilityKey<*>>,
    ) {
        val seen = alreadyProvided.filter { it.cardinality == CapabilityCardinality.SINGLE }.toMutableSet()
        plugins.forEach { plugin ->
            plugin.descriptor.provides.filter { it.cardinality == CapabilityCardinality.SINGLE }.forEach { key ->
                require(seen.none { it === key }) {
                    "${PluginContractMessages.DUPLICATE_SINGLE}: ${key.diagnosticId}"
                }
                seen += key
            }
        }
    }
}

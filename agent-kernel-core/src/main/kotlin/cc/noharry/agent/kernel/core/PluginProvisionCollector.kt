@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.CapabilityCardinality
import cc.noharry.agent.kernel.api.CapabilityKey
import cc.noharry.agent.kernel.api.PluginContractMessages
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ProviderConcurrencyMode
import cc.noharry.agent.kernel.api.SessionCapabilityFactory

class PluginProvisionCollector(
    private val alreadyHas: (CapabilityKey<*>) -> Boolean,
) {
    private val provided = LinkedHashMap<CapabilityKey<*>, MutableList<CapabilityBinding>>()

    fun <T : Any> addShared(
        key: CapabilityKey<T>,
        value: T,
        concurrency: ProviderConcurrencyDeclaration,
    ) {
        require(concurrency.mode != ProviderConcurrencyMode.SESSION_FACTORY) {
            PluginContractMessages.DIRECT_SESSION_FACTORY
        }
        rejectDuplicateSingle(key)
        rejectMixedMode(key, factory = false)
        provided.getOrPut(key) { mutableListOf() } += SharedCapabilityBinding(key, value, concurrency)
    }

    fun <T : Any> addFactory(
        key: CapabilityKey<T>,
        factory: SessionCapabilityFactory<T>,
        concurrency: ProviderConcurrencyDeclaration,
    ) {
        require(concurrency.mode == ProviderConcurrencyMode.SESSION_FACTORY) {
            PluginContractMessages.FACTORY_REQUIRES_SESSION_MODE
        }
        rejectDuplicateSingle(key)
        rejectMixedMode(key, factory = true)
        provided.getOrPut(key) { mutableListOf() } += FactoryCapabilityBinding(key, factory, concurrency)
    }

    fun <T : Any> sharedValues(key: CapabilityKey<T>): List<T> =
        provided[key].orEmpty().mapNotNull { binding ->
            (binding as? SharedCapabilityBinding)?.value as T?
        }

    fun snapshot(): Map<CapabilityKey<*>, List<CapabilityBinding>> =
        provided.mapValues { (_, bindings) -> bindings.toList() }

    fun clear() {
        provided.clear()
    }

    private fun rejectDuplicateSingle(key: CapabilityKey<*>) {
        if (key.cardinality != CapabilityCardinality.SINGLE) return
        check(!alreadyHas(key) && provided[key].isNullOrEmpty()) {
            "${PluginContractMessages.DUPLICATE_SINGLE}: ${key.diagnosticId}"
        }
    }

    private fun rejectMixedMode(key: CapabilityKey<*>, factory: Boolean) {
        val existing = provided[key].orEmpty()
        val mixed = existing.any { binding -> (binding is FactoryCapabilityBinding) != factory }
        require(!mixed) {
            "${RuntimeCompositionMessages.MIXED_CAPABILITY_MODE}: ${key.diagnosticId}"
        }
    }
}

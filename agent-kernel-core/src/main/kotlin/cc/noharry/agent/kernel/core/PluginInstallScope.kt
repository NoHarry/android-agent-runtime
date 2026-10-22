@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.AgentPluginScope
import cc.noharry.agent.kernel.api.CapabilityCardinality
import cc.noharry.agent.kernel.api.CapabilityKey
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.SessionCapabilityFactory

class PluginInstallScope(
    private val registry: PluginRegistry,
) : AgentPluginScope {
    private val collector = PluginProvisionCollector(registry::hasCapability)
    private val resources = registry.newResourceLedger()
    private var completed = false

    override fun <T : Any> provide(
        key: CapabilityKey<T>,
        value: T,
        concurrency: ProviderConcurrencyDeclaration,
    ) {
        checkActive()
        collector.addShared(key, value, concurrency)
    }

    override fun <T : Any> provide(
        key: CapabilityKey<T>,
        factory: SessionCapabilityFactory<T>,
    ) {
        provide(
            key,
            factory,
            ProviderConcurrencyDeclaration.sessionFactory(
                ProviderConcurrencyDeclaration.DEFAULT_BOUNDED_MAX_CONCURRENT_CALLS,
            ),
        )
    }

    override fun <T : Any> provide(
        key: CapabilityKey<T>,
        factory: SessionCapabilityFactory<T>,
        concurrency: ProviderConcurrencyDeclaration,
    ) {
        checkActive()
        collector.addFactory(key, factory, concurrency)
    }

    override fun <T : Any> resolve(key: CapabilityKey<T>): T? {
        checkActive()
        require(key.cardinality == CapabilityCardinality.SINGLE) {
            "Capability ${key.name} is multiple; use resolveAll()."
        }
        return collector.sharedValues(key).singleOrNull()
            ?: registry.visibleSharedValues(key).singleOrNull()
    }

    override fun <T : Any> resolveAll(key: CapabilityKey<T>): List<T> {
        checkActive()
        require(key.cardinality == CapabilityCardinality.MULTIPLE) {
            "Capability ${key.name} is single; use resolve()."
        }
        return registry.visibleSharedValues(key) + collector.sharedValues(key)
    }

    override fun <T : AutoCloseable> own(resource: T): T {
        checkActive()
        return resources.own(resource)
    }

    fun bindingsSnapshot(): Map<CapabilityKey<*>, List<CapabilityBinding>> {
        checkActive()
        completed = true
        return collector.snapshot()
    }

    fun resourcesSnapshot(): List<AutoCloseable> {
        check(completed) { "Capabilities must be snapshotted before resources." }
        return resources.snapshot()
    }

    fun rollback() {
        completed = true
        resources.rollback()
        collector.clear()
    }

    private fun checkActive() {
        check(!completed) { "Plugin installation scope is no longer active." }
    }
}

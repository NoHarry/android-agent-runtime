@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.plugin
import cc.noharry.agent.kernel.api.AgentPlugin
import cc.noharry.agent.kernel.api.CapabilityCardinality
import cc.noharry.agent.kernel.api.CapabilityKey
import cc.noharry.agent.kernel.api.ExecEngine
import cc.noharry.agent.kernel.api.KernelApiVersion
import cc.noharry.agent.kernel.api.PluginContractMessages
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ProviderConcurrencyMode
import cc.noharry.agent.kernel.api.SessionCapabilityContext
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.core.state.SessionSlot
import java.util.IdentityHashMap

class PluginRegistry : AutoCloseable {
    private val capabilities = LinkedHashMap<CapabilityKey<*>, MutableList<CapabilityBinding>>()
    private val installed = LinkedHashMap<String, AgentPlugin>()
    private val ownedResources = mutableListOf<AutoCloseable>()
    private val ownedIdentities = IdentityHashMap<AutoCloseable, Unit>()
    private val sessions = PluginSessionStore()
    private val installer = PluginInstaller(this)
    private var closed = false

    fun install(plugin: AgentPlugin) {
        installer.install(plugin)
    }

    fun installAll(plugins: List<AgentPlugin>, kernelApiVersion: KernelApiVersion) {
        installer.installAll(plugins, kernelApiVersion)
    }

    fun <T : Any> resolve(key: CapabilityKey<T>): T? = synchronized(this) {
        requireSingle(key)
        checkOpen()
        sharedValues(key).singleOrNull()
    }

    fun <T : Any> resolveAll(key: CapabilityKey<T>): List<T> = synchronized(this) {
        requireMultiple(key)
        checkOpen()
        sharedValues(key)
    }

    fun <T : Any> resolveSession(context: SessionCapabilityContext, key: CapabilityKey<T>): T? {
        requireSingle(key)
        val factories: List<CapabilityBinding>
        val shared: List<T>
        synchronized(this) {
            checkOpen()
            factories = factoryBindings(key)
            shared = sharedValues(key)
        }
        val realized = realizeFactories(context, key, factories)
        return realized.singleOrNull() ?: shared.singleOrNull()
    }

    fun <T : Any> resolveSessionAll(
        context: SessionCapabilityContext,
        key: CapabilityKey<T>,
    ): List<T> {
        requireMultiple(key)
        val factories: List<CapabilityBinding>
        val shared: List<T>
        synchronized(this) {
            checkOpen()
            factories = factoryBindings(key)
            shared = sharedValues(key)
        }
        return shared + realizeFactories(context, key, factories)
    }

    fun closeSession(context: SessionCapabilityContext) {
        val detached = synchronized(this) {
            checkOpen()
            sessions.takeSession(context.sessionId)
        }
        detached.forEach(::closeSafelyValue)
    }

    override fun close() {
        val sessionValues: List<Any>
        val resources: List<AutoCloseable>
        synchronized(this) {
            if (closed) return
            closed = true
            sessionValues = sessions.takeAll()
            resources = ownedResources.asReversed().toList()
            ownedResources.clear()
            ownedIdentities.clear()
            capabilities.clear()
            installed.clear()
        }
        sessionValues.forEach(::closeSafelyValue)
        resources.forEach(::closeSafely)
    }

    fun abandon() {
        close()
    }

    fun installedIds(): List<String> = installed.keys.toList()

    fun providedKeys(): Set<CapabilityKey<*>> = capabilities.keys.toSet()

    fun requireAvailablePluginId(plugin: AgentPlugin): String {
        checkOpen()
        val normalized = plugin.descriptor.id
        require(normalized.isNotEmpty()) { PluginContractMessages.BLANK_PLUGIN_ID }
        check(normalized !in installed) { "${PluginContractMessages.DUPLICATE_PLUGIN_ID}: $normalized" }
        return normalized
    }

    fun hasCapability(key: CapabilityKey<*>): Boolean =
        capabilities[key].orEmpty().isNotEmpty()

    fun <T : Any> visibleSharedValues(key: CapabilityKey<T>): List<T> {
        checkOpen()
        return sharedValues(key)
    }

    fun claimOwnedResource(resource: AutoCloseable): Boolean {
        if (ownedIdentities.containsKey(resource)) return false
        ownedIdentities[resource] = Unit
        return true
    }

    fun releaseOwnedResource(resource: AutoCloseable) {
        ownedIdentities.remove(resource)
    }

    fun newResourceLedger(): PluginResourceLedger =
        PluginResourceLedger(
            claimFirst = ::claimOwnedResource,
            releaseClaim = ::releaseOwnedResource,
        )

    fun commit(
        pluginId: String,
        plugin: AgentPlugin,
        provided: Map<CapabilityKey<*>, List<CapabilityBinding>>,
        resources: List<AutoCloseable>,
    ) {
        checkOpen()
        check(pluginId !in installed) { "${PluginContractMessages.DUPLICATE_PLUGIN_ID}: $pluginId" }
        PluginPreflight.validateBindingModes(capabilities, provided)
        provided.forEach { (key, bindings) ->
            check(key.cardinality != CapabilityCardinality.SINGLE || !hasCapability(key)) {
                "${PluginContractMessages.DUPLICATE_SINGLE}: ${key.diagnosticId}"
            }
            check(key.cardinality != CapabilityCardinality.SINGLE || bindings.size == 1) {
                "${PluginContractMessages.DUPLICATE_SINGLE}: ${key.diagnosticId}"
            }
        }
        provided.forEach { (key, bindings) ->
            capabilities.getOrPut(key) { mutableListOf() }.addAll(bindings)
        }
        ownedResources.addAll(resources)
        installed[pluginId] = plugin
    }

    fun concurrencyFor(key: CapabilityKey<*>): List<cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration> =
        capabilities[key].orEmpty().map { it.concurrency }

    private fun <T : Any> realizeFactories(
        context: SessionCapabilityContext,
        key: CapabilityKey<T>,
        bindings: List<CapabilityBinding>,
    ): List<T> {
        if (bindings.isEmpty()) return emptyList()
        val generation: Long
        synchronized(this) {
            checkOpen()
            sessions.cachedValues(context, bindings)?.let { cached ->
                return cached.map { it as T }
            }
            generation = sessions.generationOf(context.sessionId)
        }
        val created = createFactoryValues(context, key, bindings, generation)
        return publishFactoryValues(context, key, bindings, created, generation)
    }

    private fun <T : Any> createFactoryValues(
        context: SessionCapabilityContext,
        key: CapabilityKey<T>,
        bindings: List<CapabilityBinding>,
        generation: Long,
    ): ProducedFactoryValues<T> {
        val produced = mutableListOf<T>()
        val resolved = mutableListOf<Pair<SessionSlot, T>>()
        return try {
            for (binding in bindings) {
                val slot = SessionSlot(context.sessionId, key, binding)
                var existing: T? = null
                var stale = false
                synchronized(this) {
                    if (closed || sessions.generationOf(context.sessionId) != generation) {
                        stale = true
                    } else {
                        existing = sessions.peek(slot) as T?
                    }
                }
                if (stale) break
                val cached = existing
                if (cached != null) {
                    resolved += slot to cached
                } else {
                    val value = (binding as FactoryCapabilityBinding).factory.create(context) as T
                    produced += value
                    rejectInvalidProducedFactoryValue(binding, value)
                    resolved += slot to value
                }
            }
            ProducedFactoryValues(resolved, produced)
        } catch (error: Throwable) {
            produced.asReversed().forEach(::closeSafelyValue)
            throw error
        }
    }

    private fun <T : Any> publishFactoryValues(
        context: SessionCapabilityContext,
        key: CapabilityKey<T>,
        bindings: List<CapabilityBinding>,
        created: ProducedFactoryValues<T>,
        generation: Long,
    ): List<T> {
        var closedDuring = false
        var drainedDuring = false
        val published = synchronized(this) {
            when {
                closed -> {
                    closedDuring = true
                    emptyList()
                }
                sessions.generationOf(context.sessionId) != generation ||
                    created.resolved.size != bindings.size -> {
                    drainedDuring = true
                    emptyList()
                }
                else -> {
                    val bySlot = created.resolved.associate { it.first to it.second }
                    bindings.map { binding ->
                        val slot = SessionSlot(context.sessionId, key, binding)
                        val candidate = bySlot.getValue(slot)
                        sessions.publish(slot, candidate) as T
                    }
                }
            }
        }
        created.produced
            .filter { value -> published.none { it === value } }
            .asReversed()
            .forEach(::closeSafelyValue)
        check(!closedDuring) { "Plugin registry is closed." }
        check(!drainedDuring) { RuntimeCompositionMessages.SESSION_CAPABILITIES_DRAINED }
        return published
    }

    private fun <T : Any> sharedValues(key: CapabilityKey<T>): List<T> =
        capabilities[key].orEmpty().mapNotNull { binding ->
            (binding as? SharedCapabilityBinding)?.value as T?
        }

    private fun factoryBindings(key: CapabilityKey<*>): List<CapabilityBinding> =
        capabilities[key].orEmpty().filterIsInstance<FactoryCapabilityBinding>()

    private fun rejectInvalidProducedFactoryValue(binding: CapabilityBinding, value: Any) {
        val produced = producedProviderConcurrency(value) ?: return
        check(produced.mode != ProviderConcurrencyMode.SESSION_FACTORY) {
            RuntimeCompositionMessages.PRODUCED_SESSION_FACTORY_MODE
        }
        check(produced.maxConcurrentCalls <= binding.concurrency.maxConcurrentCalls) {
            RuntimeCompositionMessages.PRODUCED_CONCURRENCY_EXCEEDS_BINDING
        }
    }

    private fun producedProviderConcurrency(value: Any): ProviderConcurrencyDeclaration? =
        when (value) {
            is ToolProvider -> value.providerConcurrency
            is ModelClient -> value.providerConcurrency
            is cc.noharry.agent.kernel.api.ExecEngine -> value.providerConcurrency
            else -> null
        }

    private fun requireSingle(key: CapabilityKey<*>) {
        require(key.cardinality == CapabilityCardinality.SINGLE) {
            "Capability ${key.name} is multiple; use resolveAll()."
        }
    }

    private fun requireMultiple(key: CapabilityKey<*>) {
        require(key.cardinality == CapabilityCardinality.MULTIPLE) {
            "Capability ${key.name} is single; use resolve()."
        }
    }

    private fun checkOpen() {
        check(!closed) { "Plugin registry is closed." }
    }
}

private class ProducedFactoryValues<T : Any>(
    val resolved: List<Pair<SessionSlot, T>>,
    val produced: List<T>,
)

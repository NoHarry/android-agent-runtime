@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.plugin
import cc.noharry.agent.kernel.api.AgentProfile
import cc.noharry.agent.kernel.api.CapabilityKey
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyMode
import cc.noharry.agent.kernel.api.SessionCapabilityContext

/**
 * Immutable compiled snapshot of [AgentProfile] plus installed plugins.
 * Owns the plugin registry lifecycle and exposes typed resolution internally.
 */
@ExperimentalAgentRuntimeApi
class RuntimeBlueprint internal constructor(
    val profile: AgentProfile,
    private val registry: PluginRegistry,
) : AutoCloseable {
    /** Snapshot of installed plugin ids in commit order. */
    val installedPluginIds: List<String> = registry.installedIds()

    private var closed = false

    fun <T : Any> resolveShared(key: CapabilityKey<T>): T? = registry.resolve(key)

    fun <T : Any> resolveSharedAll(key: CapabilityKey<T>): List<T> = registry.resolveAll(key)

    fun <T : Any> resolveSession(
        context: SessionCapabilityContext,
        key: CapabilityKey<T>,
    ): T? = registry.resolveSession(context, key)

    fun <T : Any> resolveSessionAll(
        context: SessionCapabilityContext,
        key: CapabilityKey<T>,
    ): List<T> = registry.resolveSessionAll(context, key)

    fun concurrencyFor(key: CapabilityKey<*>) = registry.concurrencyFor(key)

    fun providedKeys(): Set<CapabilityKey<*>> = registry.providedKeys()

    fun factoryKeys(): Set<CapabilityKey<*>> =
        providedKeys().filter { key ->
            concurrencyFor(key).any { it.mode == ProviderConcurrencyMode.SESSION_FACTORY }
        }.toSet()

    fun closeSession(context: SessionCapabilityContext) {
        check(!closed) { "Runtime blueprint is closed." }
        registry.closeSession(context)
    }

    override fun close() {
        if (closed) return
        closed = true
        registry.close()
    }
}

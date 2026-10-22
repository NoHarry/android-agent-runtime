@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.AgentCapabilities
import cc.noharry.agent.kernel.api.AgentPlugin
import cc.noharry.agent.kernel.api.AgentPluginDescriptor
import cc.noharry.agent.kernel.api.AgentPluginScope
import cc.noharry.agent.kernel.api.CapabilityKey
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.KernelApiRange
import cc.noharry.agent.kernel.api.KernelApiVersions
import cc.noharry.agent.kernel.api.PluginVersion
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ProviderConcurrencyMode
import cc.noharry.agent.kernel.api.RuntimeContextProvider
import cc.noharry.agent.kernel.api.RuntimeTurnObserver
import cc.noharry.agent.kernel.api.SessionEventLog
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.api.WorkspacePort
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.ExecEngine

/**
 * Synthetic descriptor-rich plugin that packages host-direct capabilities.
 *
 * When [ownResources] is true (Android runtime default), AutoCloseable
 * declarations are owned exactly once through the plugin ledger. Kernel
 * host model/workspace composition passes [ownResources] false so
 * [CoreAgentRuntime.close] never closes those host objects.
 */
@ExperimentalAgentRuntimeApi
class DirectCapabilityPlugin(
    private val declarations: RuntimeCapabilityDeclarations,
    private val ownResources: Boolean = true,
) : AgentPlugin {
    override val id: String = RuntimeCompositionMessages.DIRECT_PLUGIN_ID
    override val descriptor: AgentPluginDescriptor = AgentPluginDescriptor(
        id = RuntimeCompositionMessages.DIRECT_PLUGIN_ID,
        pluginVersion = PluginVersion(1, 0, 0),
        kernelApiRange = KernelApiRange(KernelApiVersions.CURRENT, KernelApiVersions.CURRENT),
        provides = declaredProvides(declarations),
    )

    override fun install(scope: AgentPluginScope) {
        val owned = IdentitySet()
        declarations.modelClient?.let {
            provideShared(scope, AgentCapabilities.MODEL_CLIENT, it, owned, ownResources)
        }
        declarations.workspace?.let {
            provideShared(scope, AgentCapabilities.WORKSPACE, it, owned, ownResources)
        }
        declarations.execEngine?.let {
            provideShared(scope, AgentCapabilities.EXEC_ENGINE, it, owned, ownResources)
        }
        declarations.sessionEventLog?.let {
            provideShared(scope, AgentCapabilities.SESSION_EVENT_LOG, it, owned, ownResources)
        }
        declarations.toolProviders.forEach { provider ->
            provideShared(scope, AgentCapabilities.TOOL_PROVIDER, provider, owned, ownResources)
        }
        declarations.contextProviders.forEach { provider ->
            provideShared(scope, AgentCapabilities.RUNTIME_CONTEXT_PROVIDER, provider, owned, ownResources)
        }
        declarations.turnObservers.forEach { observer ->
            provideShared(scope, AgentCapabilities.RUNTIME_TURN_OBSERVER, observer, owned, ownResources)
        }
    }

    companion object {
        fun from(
            declarations: RuntimeCapabilityDeclarations,
            ownResources: Boolean = true,
        ): DirectCapabilityPlugin? =
            if (declarations.isEmpty) null else DirectCapabilityPlugin(declarations, ownResources)
    }
}

private fun declaredProvides(declarations: RuntimeCapabilityDeclarations): Set<CapabilityKey<*>> {
    val keys = linkedSetOf<CapabilityKey<*>>()
    if (declarations.modelClient != null) keys += AgentCapabilities.MODEL_CLIENT
    if (declarations.workspace != null) keys += AgentCapabilities.WORKSPACE
    if (declarations.execEngine != null) keys += AgentCapabilities.EXEC_ENGINE
    if (declarations.sessionEventLog != null) keys += AgentCapabilities.SESSION_EVENT_LOG
    if (declarations.toolProviders.isNotEmpty()) keys += AgentCapabilities.TOOL_PROVIDER
    if (declarations.contextProviders.isNotEmpty()) keys += AgentCapabilities.RUNTIME_CONTEXT_PROVIDER
    if (declarations.turnObservers.isNotEmpty()) keys += AgentCapabilities.RUNTIME_TURN_OBSERVER
    return keys
}

private fun <T : Any> provideShared(
    scope: AgentPluginScope,
    key: CapabilityKey<T>,
    value: T,
    owned: IdentitySet,
    ownResources: Boolean,
) {
    scope.provide(key, value, sharedConcurrency(value))
    if (!ownResources) return
    val resource = value as? AutoCloseable ?: return
    if (owned.add(resource)) {
        scope.own(resource)
    }
}

private fun sharedConcurrency(value: Any): ProviderConcurrencyDeclaration {
    val declared = when (value) {
        is ToolProvider -> value.providerConcurrency
        is ModelClient -> value.providerConcurrency
        is ExecEngine -> value.providerConcurrency
        is WorkspacePort,
        is RuntimeContextProvider,
        is RuntimeTurnObserver,
        is SessionEventLog,
        -> ProviderConcurrencyDeclaration.serialized()
        else -> ProviderConcurrencyDeclaration.serialized()
    }
    check(declared.mode != ProviderConcurrencyMode.SESSION_FACTORY) {
        "${RuntimeCompositionMessages.UNSUPPORTED_FACTORY_COMPOSITION}: ${value::class.java.simpleName}"
    }
    return declared
}

private class IdentitySet {
    private val items = ArrayList<Any>()

    fun add(item: Any): Boolean {
        if (items.any { it === item }) return false
        items += item
        return true
    }
}

@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.plugin
import cc.noharry.agent.spi.CapabilityKey
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.SessionCapabilityFactory

internal sealed class CapabilityBinding {
    abstract val key: CapabilityKey<*>
    abstract val concurrency: ProviderConcurrencyDeclaration
}

internal class SharedCapabilityBinding(
    override val key: CapabilityKey<*>,
    val value: Any,
    override val concurrency: ProviderConcurrencyDeclaration,
) : CapabilityBinding()

internal class FactoryCapabilityBinding(
    override val key: CapabilityKey<*>,
    val factory: SessionCapabilityFactory<*>,
    override val concurrency: ProviderConcurrencyDeclaration,
) : CapabilityBinding()

internal fun bindingsMixSharedAndFactory(bindings: Iterable<CapabilityBinding>): Boolean {
    var shared = false
    var factory = false
    for (binding in bindings) {
        when (binding) {
            is SharedCapabilityBinding -> shared = true
            is FactoryCapabilityBinding -> factory = true
        }
        if (shared && factory) return true
    }
    return false
}

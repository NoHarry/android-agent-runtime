@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.plugin
import cc.noharry.agent.spi.AgentPlugin
import cc.noharry.agent.spi.AgentPluginDescriptor
import cc.noharry.agent.spi.AgentPluginScope
import cc.noharry.agent.spi.AgentProfile
import cc.noharry.agent.spi.CapabilityCardinality
import cc.noharry.agent.spi.CapabilityKey
import cc.noharry.agent.spi.KernelApiRange
import cc.noharry.agent.spi.KernelApiVersions
import cc.noharry.agent.spi.PluginVersion
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration

internal fun testKey(
    name: String,
    cardinality: CapabilityCardinality = CapabilityCardinality.SINGLE,
    namespace: String = "test",
    major: Int = 1,
): CapabilityKey<String> = CapabilityKey.of(namespace, name, major, cardinality)

internal fun testCloseableKey(
    name: String,
    cardinality: CapabilityCardinality = CapabilityCardinality.SINGLE,
    namespace: String = "test",
    major: Int = 1,
): CapabilityKey<AutoCloseable> = CapabilityKey.of(namespace, name, major, cardinality)

internal fun testDescriptor(
    id: String,
    provides: Set<CapabilityKey<*>> = emptySet(),
    requires: Set<CapabilityKey<*>> = emptySet(),
    optionalRequires: Set<CapabilityKey<*>> = emptySet(),
    version: PluginVersion = PluginVersion(1, 0, 0),
    range: KernelApiRange = KernelApiRange(KernelApiVersions.CURRENT, KernelApiVersions.CURRENT),
): AgentPluginDescriptor =
    AgentPluginDescriptor(
        id = id,
        pluginVersion = version,
        kernelApiRange = range,
        provides = provides,
        requires = requires,
        optionalRequires = optionalRequires,
    )

internal fun testPlugin(
    id: String,
    descriptor: AgentPluginDescriptor = testDescriptor(id),
    install: AgentPluginScope.() -> Unit = {},
): AgentPlugin {
    return object : AgentPlugin {
        override val descriptor: AgentPluginDescriptor = descriptor
        override val id: String = descriptor.id

        override fun install(scope: AgentPluginScope) {
            scope.install()
        }
    }
}

internal fun testProfile(id: String = "test-profile"): AgentProfile = AgentProfile(id)

internal fun trackingResource(
    name: String,
    closed: MutableList<String>,
    throws: Boolean = false,
): AutoCloseable = AutoCloseable {
    closed += name
    if (throws) error("close failed")
}

internal val testSerializedConcurrency: ProviderConcurrencyDeclaration =
    ProviderConcurrencyDeclaration.serialized()

@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentCapabilities
import cc.noharry.agent.kernel.api.AgentPlugin
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.KernelApiVersions
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.core.DirectCapabilityPlugin
import cc.noharry.agent.kernel.core.RuntimeCapabilityDeclarations
import cc.noharry.agent.runtime.eventlog.DurableSessionEventLogPlugin
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import cc.noharry.agent.kernel.core.RuntimeCompositionMessages

class FirstPartyPluginDescriptorCensusTest {
    @Test
    fun durableEventLogAndDirectPluginOverrideFullV2Descriptors() {
        val durable = DurableSessionEventLogPlugin.forRoot(
            Files.createTempDirectory("census-eventlog-").toFile(),
        )
        assertFullDescriptor(durable, DurableSessionEventLogPlugin.PLUGIN_ID)
        assertEquals(setOf(AgentCapabilities.SESSION_EVENT_LOG), durable.descriptor.provides)

        val direct = DirectCapabilityPlugin.from(
            RuntimeCapabilityDeclarations.Collector().apply {
                sessionEventLog(cc.noharry.agent.runtime.eventlog.InMemorySessionEventLog())
            }.snapshot(),
        )!!
        assertFullDescriptor(direct, RuntimeCompositionMessages.DIRECT_PLUGIN_ID)
        assertEquals(setOf(AgentCapabilities.SESSION_EVENT_LOG), direct.descriptor.provides)
    }

    @Test
    fun firstPartyInstallUsesExplicitConcurrency() {
        val durable = DurableSessionEventLogPlugin.forRoot(
            Files.createTempDirectory("census-eventlog-conc-").toFile(),
        )
        val scope = RecordingConcurrencyScope()
        durable.install(scope)
        assertEquals(
            ProviderConcurrencyDeclaration.serialized(),
            scope.concurrency.single(),
        )
    }

    @Test
    fun minimalProfileUsesTheSameCompilerPath() {
        assertEquals(RuntimeCompositionMessages.MINIMAL_PROFILE_ID, "minimal")
    }

    private fun assertFullDescriptor(plugin: AgentPlugin, id: String) {
        val descriptor = plugin.descriptor
        assertEquals(id, descriptor.id)
        assertEquals(id, plugin.id)
        assertEquals(1, descriptor.pluginVersion.major)
        assertEquals(0, descriptor.pluginVersion.minor)
        assertEquals(0, descriptor.pluginVersion.patch)
        assertTrue(descriptor.kernelApiRange.contains(KernelApiVersions.CURRENT))
        assertTrue(descriptor.provides.isNotEmpty())
    }
}

private class RecordingConcurrencyScope : cc.noharry.agent.kernel.api.AgentPluginScope {
    val concurrency = mutableListOf<ProviderConcurrencyDeclaration>()

    override fun <T : Any> provide(
        key: cc.noharry.agent.kernel.api.CapabilityKey<T>,
        value: T,
        concurrency: ProviderConcurrencyDeclaration,
    ) {
        this.concurrency += concurrency
    }

    override fun <T : Any> provide(
        key: cc.noharry.agent.kernel.api.CapabilityKey<T>,
        factory: cc.noharry.agent.kernel.api.SessionCapabilityFactory<T>,
        concurrency: ProviderConcurrencyDeclaration,
    ) {
        error("factory provide is unused by first-party census plugins")
    }

    override fun <T : Any> resolve(key: cc.noharry.agent.kernel.api.CapabilityKey<T>): T? = null

    override fun <T : Any> resolveAll(key: cc.noharry.agent.kernel.api.CapabilityKey<T>): List<T> = emptyList()

    override fun <T : AutoCloseable> own(resource: T): T = resource
}

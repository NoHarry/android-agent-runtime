package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.AgentPlugin
import cc.noharry.agent.kernel.api.AgentPluginScope
import cc.noharry.agent.kernel.api.CapabilityCardinality
import cc.noharry.agent.kernel.api.CapabilityKey
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.PluginContractMessages
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ProviderConcurrencyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalAgentRuntimeApi::class)
class PluginRegistryTest {
    @Test
    fun multipleCapabilities_preservePluginAndProvisionOrder() {
        val key = CapabilityKey<String>("test.multiple", CapabilityCardinality.MULTIPLE)
        val registry = PluginRegistry()

        registry.install(
            plugin("first", setOf(key)) {
                provide(key, "a", testSerializedConcurrency)
                provide(key, "b", testSerializedConcurrency)
            },
        )
        registry.install(plugin("second", setOf(key)) { provide(key, "c", testSerializedConcurrency) })

        assertEquals(listOf("a", "b", "c"), registry.resolveAll(key))
    }

    @Test
    fun duplicatePluginId_isRejectedBeforeSecondInstallRuns() {
        val registry = PluginRegistry()
        var secondRan = false
        registry.install(plugin("same") {})

        val error = assertThrows(IllegalArgumentException::class.java) {
            registry.install(plugin("same") { secondRan = true })
        }
        assertTrue(error.message.orEmpty().contains(PluginContractMessages.DUPLICATE_PLUGIN_ID))
        assertEquals(false, secondRan)
    }

    @Test
    fun duplicateSingleCapability_isRejectedWithoutReplacingFirstValue() {
        val key = CapabilityKey<String>("test.single", CapabilityCardinality.SINGLE)
        val registry = PluginRegistry()
        registry.install(plugin("first", setOf(key)) { provide(key, "kept", testSerializedConcurrency) })

        assertThrows(IllegalStateException::class.java) {
            registry.install(plugin("second") { provide(key, "rejected", testSerializedConcurrency) })
        }

        assertEquals("kept", registry.resolve(key))
    }

    @Test
    fun failedInstall_rollsBackCapabilitiesPluginIdAndResources() {
        val key = CapabilityKey<String>("test.rollback", CapabilityCardinality.SINGLE)
        val registry = PluginRegistry()
        val closed = mutableListOf<String>()

        assertThrows(IllegalArgumentException::class.java) {
            registry.install(
                plugin("retryable") {
                    provide(key, "leaked", testSerializedConcurrency)
                    own(resource("failed", closed))
                    throw IllegalArgumentException("install failed")
                },
            )
        }
        assertNull(registry.resolve(key))
        assertEquals(listOf("failed"), closed)

        registry.install(plugin("retryable", setOf(key)) { provide(key, "committed", testSerializedConcurrency) })
        assertEquals("committed", registry.resolve(key))
    }

    @Test
    fun duplicateOwn_closesOnceInReverseFirstOwnership() {
        val shared = AnyCloseable("shared")
        val first = AnyCloseable("first")
        val second = AnyCloseable("second")
        val closed = mutableListOf<String>()
        shared.closed = closed
        first.closed = closed
        second.closed = closed
        val registry = PluginRegistry()
        registry.install(
            plugin("alpha") {
                own(first)
                own(shared)
            },
        )
        registry.install(
            plugin("beta") {
                own(shared)
                own(second)
            },
        )
        registry.close()
        assertEquals(listOf("second", "shared", "first"), closed)
    }

    @Test
    fun close_isLifoIdempotentAndNeverThrowsToHost() {
        val registry = PluginRegistry()
        val closed = mutableListOf<String>()
        registry.install(
            plugin("resources") {
                own(resource("first", closed))
                own(resource("throwing", closed, throws = true))
                own(resource("last", closed))
            },
        )

        registry.close()
        registry.close()

        assertEquals(listOf("last", "throwing", "first"), closed)
    }

    @Test
    fun provisionCarriesConcurrencyMetadata() {
        val key = CapabilityKey<String>("test.concurrency", CapabilityCardinality.SINGLE)
        val registry = PluginRegistry()
        registry.install(
            plugin("typed", setOf(key)) {
                provide(key, "value", ProviderConcurrencyDeclaration.threadSafe(3))
            },
        )

        val declaration = registry.concurrencyFor(key).single()
        assertEquals(ProviderConcurrencyMode.RUNTIME_SHARED_THREAD_SAFE, declaration.mode)
        assertEquals(3, declaration.maxConcurrentCalls)
    }

    private fun plugin(
        id: String,
        provides: Set<CapabilityKey<*>> = emptySet(),
        install: AgentPluginScope.() -> Unit,
    ): AgentPlugin = testPlugin(id, testDescriptor(id, provides = provides), install)

    private fun resource(
        name: String,
        closed: MutableList<String>,
        throws: Boolean = false,
    ): AutoCloseable = trackingResource(name, closed, throws)

    private class AnyCloseable(
        private val name: String,
    ) : AutoCloseable {
        lateinit var closed: MutableList<String>
        override fun close() {
            closed += name
        }
    }
}

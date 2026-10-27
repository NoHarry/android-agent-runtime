package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.CapabilityCardinality
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.KernelApiVersions
import cc.noharry.agent.kernel.api.SessionCapabilityContext
import cc.noharry.agent.kernel.api.SessionCapabilityFactory
import cc.noharry.agent.kernel.core.loop.failed
import cc.noharry.agent.kernel.core.plugin.PluginRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalAgentRuntimeApi::class)
class PluginTransactionTest {
    @Test
    fun pluginPartialFailure_closesOwnedResourcesInReverse() {
        val closed = mutableListOf<String>()
        val registry = PluginRegistry()
        assertThrows(IllegalStateException::class.java) {
            registry.install(
                testPlugin("partial") {
                    own(trackingResource("one", closed))
                    own(trackingResource("two", closed))
                    error("install exploded")
                },
            )
        }
        assertEquals(listOf("two", "one"), closed)
        assertTrue(registry.installedIds().isEmpty())
    }

    @Test
    fun batchFailure_closesCommittedPluginsGloballyAndLeavesNoRegistry() {
        val closed = mutableListOf<String>()
        val firstKey = testKey("first")
        val registry = PluginRegistry()
        val error = assertThrows(IllegalStateException::class.java) {
            registry.installAll(
                listOf(
                    testPlugin("alpha", testDescriptor("alpha", provides = setOf(firstKey))) {
                        own(trackingResource("alpha", closed))
                        provide(firstKey, "ok", testSerializedConcurrency)
                    },
                    testPlugin("beta") {
                        own(trackingResource("beta", closed))
                        error("beta failed")
                    },
                ),
                KernelApiVersions.CURRENT,
            )
        }
        assertTrue(error.message.orEmpty().contains("beta failed"))
        assertEquals(listOf("beta", "alpha"), closed)
        assertThrows(IllegalStateException::class.java) { registry.resolve(firstKey) }
    }

    @Test
    fun close_usesCrossPluginLifo() {
        val closed = mutableListOf<String>()
        val registry = PluginRegistry()
        registry.install(testPlugin("first") { own(trackingResource("first", closed)) })
        registry.install(testPlugin("second") { own(trackingResource("second", closed)) })
        registry.close()
        assertEquals(listOf("second", "first"), closed)
    }

    @Test
    fun rollbackDoesNotCloseAlreadyOwnedIdentity() {
        val closed = mutableListOf<String>()
        val shared = trackingResource("shared", closed)
        val registry = PluginRegistry()
        registry.install(testPlugin("alpha") { own(shared) })
        assertThrows(IllegalStateException::class.java) {
            registry.install(
                testPlugin("beta") {
                    own(shared)
                    own(trackingResource("beta", closed))
                    error("beta failed")
                },
            )
        }
        assertEquals(listOf("beta"), closed)
        registry.close()
        assertEquals(listOf("beta", "shared"), closed)
    }

    @Test
    fun producedInstanceFailure_rollsBackNewSessionInstances() {
        val closed = mutableListOf<String>()
        val key = testCloseableKey("session-multi", CapabilityCardinality.MULTIPLE)
        val registry = PluginRegistry()
        registry.install(
            testPlugin("factories", testDescriptor("factories", provides = setOf(key))) {
                provide(key, SessionCapabilityFactory { trackingResource("one", closed) })
                provide(key, SessionCapabilityFactory { error("factory two failed") })
            },
        )

        val context = SessionCapabilityContext("session-a")
        assertThrows(IllegalStateException::class.java) {
            registry.resolveSessionAll(context, key)
        }
        assertEquals(listOf("one"), closed)

        registry.close()
        assertEquals(listOf("one"), closed)
    }
}

@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.plugin
import cc.noharry.agent.kernel.api.CapabilityCardinality
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.SessionCapabilityContext
import cc.noharry.agent.kernel.api.SessionCapabilityFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class PluginRegistrySessionResolutionTest {
    @Test
    fun mixedSharedAndFactoryOnSameKey_isRejected() {
        val key = testKey("multi", CapabilityCardinality.MULTIPLE)
        val registry = PluginRegistry()
        val error = assertThrows(IllegalArgumentException::class.java) {
            registry.install(
                testPlugin("both", testDescriptor("both", provides = setOf(key))) {
                    provide(key, "shared", testSerializedConcurrency)
                    provide(key, SessionCapabilityFactory { "factory-${it.sessionId}" })
                },
            )
        }
        assertTrue(error.message.orEmpty().contains(RuntimeCompositionMessages.MIXED_CAPABILITY_MODE))
        registry.close()
    }

    @Test
    fun mixedSharedAndFactoryAcrossPlugins_isRejectedAtCommit() {
        val key = testKey("cross-mixed", CapabilityCardinality.MULTIPLE)
        val registry = PluginRegistry()
        registry.install(
            testPlugin("shared", testDescriptor("shared", provides = setOf(key))) {
                provide(key, "shared", testSerializedConcurrency)
            },
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            registry.install(
                testPlugin("factory", testDescriptor("factory", provides = setOf(key))) {
                    provide(key, SessionCapabilityFactory { "factory" })
                },
            )
        }
        assertTrue(error.message.orEmpty().contains(RuntimeCompositionMessages.MIXED_CAPABILITY_MODE))
        assertEquals(listOf("shared"), registry.resolveSessionAll(SessionCapabilityContext("s1"), key))
        registry.close()
    }

    @Test
    fun singleSessionResolutionFallsBackToSharedWhenNoFactory() {
        val key = testKey("single-shared")
        val registry = PluginRegistry()
        registry.install(
            testPlugin("shared-only", testDescriptor("shared-only", provides = setOf(key))) {
                provide(key, "fallback", testSerializedConcurrency)
            },
        )

        assertEquals("fallback", registry.resolveSession(SessionCapabilityContext("s1"), key))
        registry.close()
    }

    @Test
    fun factoryFailureDoesNotLeaveCacheOrLedgerEntries() {
        val key = testCloseableKey("fail-factory")
        val registry = PluginRegistry()
        val closed = mutableListOf<String>()
        var shouldFail = true
        registry.install(
            testPlugin("boom", testDescriptor("boom", provides = setOf(key))) {
                provide(
                    key,
                    SessionCapabilityFactory {
                        if (shouldFail) error("factory failed")
                        trackingResource("ok", closed)
                    },
                )
            },
        )

        assertThrows(IllegalStateException::class.java) {
            registry.resolveSession(SessionCapabilityContext("s1"), key)
        }
        shouldFail = false
        val recovered = registry.resolveSession(SessionCapabilityContext("s1"), key)
        assertTrue(recovered != null)
        registry.close()
        assertEquals(listOf("ok"), closed)
    }

    @Test
    fun closeSessionClosesOutsideRegistryLock() {
        val key = testCloseableKey("lock-close")
        val registry = PluginRegistry()
        val closedOutside = AtomicBoolean(false)
        registry.install(
            testPlugin("res", testDescriptor("res", provides = setOf(key))) {
                provide(
                    key,
                    SessionCapabilityFactory {
                        AutoCloseable {
                            // Re-entering resolve under close must not deadlock.
                            registry.resolve(testKey("unused-shared"))
                            closedOutside.set(true)
                        }
                    },
                )
            },
        )
        registry.resolveSession(SessionCapabilityContext("s1"), key)
        registry.closeSession(SessionCapabilityContext("s1"))
        assertTrue(closedOutside.get())
        registry.close()
    }

    @Test
    fun closeDuringFactoryCreate_closesProducedInstanceOnce() {
        val key = testCloseableKey("close-during-create")
        val registry = PluginRegistry()
        val entered = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val closed = mutableListOf<String>()
        registry.install(
            testPlugin("factory", testDescriptor("factory", provides = setOf(key))) {
                provide(
                    key,
                    SessionCapabilityFactory {
                        entered.countDown()
                        proceed.await()
                        AutoCloseable {
                            closed += "produced"
                            runCatching { registry.resolve(testKey("unused-shared")) }
                        }
                    },
                )
            },
        )
        val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val worker = Thread {
            try {
                registry.resolveSession(SessionCapabilityContext("s1"), key)
            } catch (thrown: Throwable) {
                error.set(thrown)
            }
        }
        worker.start()
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        registry.close()
        proceed.countDown()
        worker.join(5_000)
        assertTrue(error.get()?.message.orEmpty().contains("closed"))
        assertEquals(listOf("produced"), closed)
    }

    @Test
    fun closeSessionDuringCreate_preventsRepublish() {
        val key = testCloseableKey("close-session-create")
        val registry = PluginRegistry()
        val entered = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val closed = mutableListOf<String>()
        var created = 0
        registry.install(
            testPlugin("factory", testDescriptor("factory", provides = setOf(key))) {
                provide(
                    key,
                    SessionCapabilityFactory {
                        created += 1
                        if (created == 1) {
                            entered.countDown()
                            proceed.await()
                        }
                        trackingResource("v$created", closed)
                    },
                )
            },
        )
        val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val worker = Thread {
            try {
                registry.resolveSession(SessionCapabilityContext("s1"), key)
            } catch (thrown: Throwable) {
                error.set(thrown)
            }
        }
        worker.start()
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        registry.closeSession(SessionCapabilityContext("s1"))
        proceed.countDown()
        worker.join(5_000)
        assertTrue(
            error.get()?.message.orEmpty()
                .contains(RuntimeCompositionMessages.SESSION_CAPABILITIES_DRAINED),
        )
        assertEquals(listOf("v1"), closed.toList())
        val next = registry.resolveSession(SessionCapabilityContext("s1"), key)
        assertTrue(next != null)
        registry.closeSession(SessionCapabilityContext("s1"))
        assertEquals(listOf("v1", "v2"), closed.toList())
        registry.close()
    }
}

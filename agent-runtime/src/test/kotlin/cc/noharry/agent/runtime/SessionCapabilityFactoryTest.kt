package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentCapabilities
import cc.noharry.agent.kernel.api.CapabilityCardinality
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.SessionCapabilityContext
import cc.noharry.agent.kernel.api.SessionCapabilityFactory
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.core.plugin.PluginRegistry
import cc.noharry.agent.kernel.core.plugin.RuntimeCompositionMessages
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalAgentRuntimeApi::class)
class SessionCapabilityFactoryTest {
    @Test
    fun sameSession_reusesFactoryInstance_differentSessionsIsolate() {
        var created = 0
        val key = testKey("session-cap")
        val registry = PluginRegistry()
        registry.install(
            testPlugin("factory", testDescriptor("factory", provides = setOf(key))) {
                provide(
                    key,
                    SessionCapabilityFactory { context ->
                        created += 1
                        "instance-$created-${context.sessionId}"
                    },
                )
            },
        )

        val first = SessionCapabilityContext("s1")
        val second = SessionCapabilityContext("s2")
        val firstA = registry.resolveSession(first, key)
        val firstB = registry.resolveSession(first, key)
        val secondA = registry.resolveSession(second, key)

        assertEquals("instance-1-s1", firstA)
        assertSame(firstA, firstB)
        assertEquals("instance-2-s2", secondA)
        assertNotSame(firstA, secondA)
        assertEquals(2, created)
        registry.close()
    }

    @Test
    fun closeSession_drainsExactInstancesReverseLifo() {
        val closed = mutableListOf<String>()
        val key = testCloseableKey("session-multi", CapabilityCardinality.MULTIPLE)
        val registry = PluginRegistry()
        registry.install(
            testPlugin("factories", testDescriptor("factories", provides = setOf(key))) {
                provide(key, SessionCapabilityFactory { trackingResource("early", closed) })
                provide(key, SessionCapabilityFactory { trackingResource("late", closed) })
            },
        )

        val keep = SessionCapabilityContext("keep")
        val drain = SessionCapabilityContext("drain")
        registry.resolveSessionAll(keep, key)
        registry.resolveSessionAll(drain, key)
        registry.closeSession(drain)

        assertEquals(listOf("late", "early"), closed)
        assertEquals(2, registry.resolveSessionAll(keep, key).size)
        registry.close()
    }

    @Test
    fun runtimeClose_closesRemainingSessionInstancesThenSharedResources() {
        val closed = mutableListOf<String>()
        val shared = testKey("shared-res")
        val session = testCloseableKey("session-res")
        val registry = PluginRegistry()
        registry.install(
            testPlugin(
                "owner",
                testDescriptor("owner", provides = setOf(shared, session)),
            ) {
                provide(shared, "shared", testSerializedConcurrency)
                own(trackingResource("shared", closed))
                provide(session, SessionCapabilityFactory { trackingResource("session", closed) })
            },
        )
        registry.resolveSession(SessionCapabilityContext("live"), session)
        registry.close()
        registry.close()
        assertEquals(listOf("session", "shared"), closed)
    }

    @Test
    fun directSessionFactoryInstance_isRejected() {
        val key = testKey("direct")
        val registry = PluginRegistry()
        val error = assertThrows(IllegalArgumentException::class.java) {
            registry.install(
                testPlugin("bad") {
                    provide(key, "value", ProviderConcurrencyDeclaration.sessionFactory(1))
                },
            )
        }
        assertTrue(error.message.orEmpty().contains("Direct SESSION_FACTORY"))
    }

    @Test
    fun genericNonProviderFactoryValues_skipProducedConcurrencyChecks() {
        val key = testKey("generic-factory")
        val registry = PluginRegistry()
        registry.install(
            testPlugin("generic", testDescriptor("generic", provides = setOf(key))) {
                provide(
                    key,
                    SessionCapabilityFactory { "plain-${it.sessionId}" },
                    ProviderConcurrencyDeclaration.sessionFactory(1),
                )
            },
        )
        assertEquals("plain-s1", registry.resolveSession(SessionCapabilityContext("s1"), key))
        registry.close()
    }

    @Test
    fun rejectedProducedProvider_closesOnceOutsideRegistryLock() {
        val key = AgentCapabilities.TOOL_PROVIDER
        val registry = PluginRegistry()
        val closeCount = AtomicInteger(0)
        val reentered = AtomicBoolean(false)
        registry.install(
            testPlugin("wide-tools", testDescriptor("wide-tools", provides = setOf(key))) {
                provide(
                    key,
                    SessionCapabilityFactory {
                        object : ToolProvider, AutoCloseable {
                            override val providerId: String = "wide"
                            override val providerConcurrency =
                                ProviderConcurrencyDeclaration.threadSafe(8)

                            override suspend fun listTools() = emptyList<cc.noharry.agent.kernel.api.ToolDescriptor>()

                            override suspend fun callTool(
                                toolName: String,
                                argumentsJson: String,
                                context: ToolCallContext,
                            ): ToolCallResult = ToolCallResult(success = true, resultJson = "{}")

                            override fun close() {
                                closeCount.incrementAndGet()
                                registry.resolve(testKey("unused-shared"))
                                reentered.set(true)
                            }
                        }
                    },
                    ProviderConcurrencyDeclaration.sessionFactory(4),
                )
            },
        )

        val error = assertThrows(IllegalStateException::class.java) {
            registry.resolveSessionAll(SessionCapabilityContext("s1"), key)
        }
        assertTrue(
            error.message.orEmpty().contains(RuntimeCompositionMessages.PRODUCED_CONCURRENCY_EXCEEDS_BINDING),
        )
        assertEquals(1, closeCount.get())
        assertTrue(reentered.get())
        registry.close()
        assertEquals(1, closeCount.get())
    }
}

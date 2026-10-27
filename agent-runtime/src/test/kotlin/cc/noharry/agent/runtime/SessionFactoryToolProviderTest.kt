@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentCapabilities
import cc.noharry.agent.kernel.api.AgentPlugin
import cc.noharry.agent.kernel.api.AgentPluginDescriptor
import cc.noharry.agent.kernel.api.AgentPluginScope
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.KernelApiRange
import cc.noharry.agent.kernel.api.KernelApiVersions
import cc.noharry.agent.kernel.api.PluginVersion
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.SessionCapabilityContext
import cc.noharry.agent.kernel.api.SessionCapabilityFactory
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolCatalogCacheIdentity
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.core.plugin.ProfileCompiler
import cc.noharry.agent.kernel.core.plugin.RuntimeCompositionMessages
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.kernel.core.tools.ProviderConcurrencyLimiter
import cc.noharry.agent.kernel.core.tools.ToolProviderRegistry
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionFactoryToolProviderTest {
    @Before
    fun clearEnvironmentCatalogCache() = runBlocking {
        ToolProviderRegistry.clearEnvironmentProviderCatalogCacheForTests()
    }

    @Test
    fun sameSessionReusesFactoryProviderAndDifferentSessionsIsolate() {
        var created = 0
        val instances = mutableListOf<TrackingToolProvider>()
        val blueprint = ProfileCompiler().compile(
            testProfile("factory-tools"),
            listOf(toolFactoryPlugin { context ->
                created += 1
                TrackingToolProvider("p-$created-${context.sessionId}").also { instances += it }
            }),
        )
        val first = SessionCapabilityContext("s1")
        val second = SessionCapabilityContext("s2")
        val firstA = blueprint.resolveSessionAll(first, AgentCapabilities.TOOL_PROVIDER).single()
        val firstB = blueprint.resolveSessionAll(first, AgentCapabilities.TOOL_PROVIDER).single()
        val secondA = blueprint.resolveSessionAll(second, AgentCapabilities.TOOL_PROVIDER).single()

        assertSame(firstA, firstB)
        assertNotSame(firstA, secondA)
        assertEquals(2, created)
        blueprint.closeSession(first)
        assertEquals(listOf("p-1-s1"), instances.filter { it.closed }.map { it.providerId })
        assertTrue(!instances.single { it.providerId == "p-2-s2" }.closed)
        blueprint.close()
    }

    @Test
    fun schedulerDoesNotReportDrainWhileQueuedOrNextOwner() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val first = holdOrdinary(scheduler, "session-a", "run-1", started)
        first.started.await()
        val second = holdOrdinary(scheduler, "session-a", "run-2", started)
        awaitSnapshot(scheduler) { it.queuedCount == 1 }

        val afterFirst = scheduler.completeAndQueryDrain(first.lease.await())
        assertTrue(!afterFirst.sessionDrained)
        assertTrue(!scheduler.isSessionDrained("session-a"))
        second.started.await()

        val afterSecond = scheduler.completeAndQueryDrain(second.lease.await())
        assertTrue(afterSecond.sessionDrained)
        assertTrue(!scheduler.isSessionDrained("session-a"))
        scheduler.finishSessionDrain(checkNotNull(afterSecond.drainLease))
        assertTrue(scheduler.isSessionDrained("session-a"))
        first.releaseAndJoin()
        second.releaseAndJoin()
    }

    @Test
    fun sessionDrainClosesFactoryThenRuntimeFallbackClosesRemainder() = runBlocking {
        val closed = mutableListOf<String>()
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(sessionFactoryFinishModel())
            .workspaceRoot(Files.createTempDirectory("factory-drain-").toFile())
            .plugin(toolFactoryPlugin { context ->
                TrackingToolProvider("live-${context.sessionId}", closed)
            })
            .build() as DefaultAgentRuntime
        val blueprint = runtime.runtimeBlueprintForTest()!!
        val live = blueprint.resolveSessionAll(
            SessionCapabilityContext("s-live"),
            AgentCapabilities.TOOL_PROVIDER,
        ).single() as TrackingToolProvider
        assertTrue(!live.closed)

        runtime.run(request("s-live")).toList()
        assertEquals(listOf("live-s-live"), closed.toList())
        assertTrue(live.closed)

        val leftover = blueprint.resolveSessionAll(
            SessionCapabilityContext("fallback-s"),
            AgentCapabilities.TOOL_PROVIDER,
        ).single() as TrackingToolProvider
        assertTrue(!leftover.closed)
        runtime.close()
        assertTrue(leftover.closed)
        assertTrue(closed.contains("live-fallback-s"))
        runtime.close()
    }

    @Test
    fun mcpRegistryCloseSessionEvictsLimiterIdentitiesWithoutClosingProviders() = runBlocking {
        val closed = mutableListOf<String>()
        val blueprint = ProfileCompiler().compile(
            testProfile("limiter-evict"),
            listOf(toolFactoryPlugin { context ->
                TrackingToolProvider("lim-${context.sessionId}", closed)
            }),
        )
        val limiter = ProviderConcurrencyLimiter()
        val registry = ToolProviderRegistry(emptyList(), limiter, blueprint)
        val context = MaterializerContext(sessionId = "sess-lim", runId = "run-lim")
        registry.allTools(context)
        assertTrue(limiter.cachedEntryCountForTests() >= 1)
        val instance = blueprint.resolveSessionAll(
            SessionCapabilityContext("sess-lim"),
            AgentCapabilities.TOOL_PROVIDER,
        ).single() as TrackingToolProvider
        assertTrue(!instance.closed)

        registry.closeSession("sess-lim")
        assertEquals(0, limiter.cachedEntryCountForTests())
        assertTrue(!instance.closed)
        blueprint.closeSession(SessionCapabilityContext("sess-lim"))
        assertTrue(instance.closed)
        blueprint.close()
    }

    @Test
    fun closeOrderStopsAdmissionThenLimiterThenBlueprint() = runBlocking {
        val limiterCountAtPluginClose = java.util.concurrent.atomic.AtomicInteger(-1)
        lateinit var built: DefaultAgentRuntime
        built = AgentRuntimeBuilder.create()
            .modelClient(sessionFactoryFinishModel())
            .workspaceRoot(Files.createTempDirectory("factory-close-order-").toFile())
            .plugin(toolFactoryPlugin { TrackingToolProvider("order-${it.sessionId}") })
            .plugin(
                object : AgentPlugin {
                    override val descriptor = testDescriptor("close-order")
                    override fun install(scope: AgentPluginScope) {
                        scope.own(
                            AutoCloseable {
                                limiterCountAtPluginClose.set(built.providerLimiterEntryCountForTests())
                            },
                        )
                    }
                },
            )
            .build() as DefaultAgentRuntime

        built.run(request("order")).toList()
        built.close()
        assertEquals(0, built.providerLimiterEntryCountForTests())
        assertEquals(0, limiterCountAtPluginClose.get())
    }

    @Test
    fun sessionFactoryProvidersDoNotShareEnvironmentCatalogCache() = runBlocking {
        val instances = mutableListOf<StableFactoryProvider>()
        val blueprint = ProfileCompiler().compile(
            testProfile("factory-cache"),
            listOf(toolFactoryPlugin { context ->
                StableFactoryProvider("stable-${context.sessionId}").also { instances += it }
            }),
        )
        val registry = ToolProviderRegistry(emptyList(), ProviderConcurrencyLimiter(), blueprint)
        registry.allTools(MaterializerContext(sessionId = "s1", runId = "r1", requestId = "q1"))
        registry.allTools(MaterializerContext(sessionId = "s2", runId = "r2", requestId = "q2"))
        assertEquals(2, instances.size)
        assertEquals(1, instances[0].listToolsCount.get())
        assertEquals(1, instances[1].listToolsCount.get())
        blueprint.close()
    }

    @Test
    fun registryInspectUsesSessionContextForFactoryTools() = runBlocking {
        val blueprint = ProfileCompiler().compile(
            testProfile("factory-inspect"),
            listOf(toolFactoryPlugin { TrackingToolProvider("inspect-${it.sessionId}") }),
        )
        val registry = ToolProviderRegistry(emptyList(), ProviderConcurrencyLimiter(), blueprint)
        val schema = registry.toolSchema(
            "mcp__inspect-s1__echo",
            MaterializerContext(sessionId = "s1", runId = "run", requestId = "req"),
        )
        assertEquals("""{"type":"object"}""", schema?.schemaJson)
        val missing = registry.toolSchema(
            "mcp__inspect-s1__echo",
            MaterializerContext(sessionId = "", runId = "", requestId = ""),
        )
        assertEquals(null, missing)
        blueprint.close()
    }

    @Test
    fun producedThreadSafe8UnderFactory4_isRejectedAndClosedOnce() {
        val instances = mutableListOf<TrackingToolProvider>()
        val blueprint = ProfileCompiler().compile(
            testProfile("factory-cap-reject"),
            listOf(
                toolFactoryPlugin { context ->
                    TrackingToolProvider(
                        providerId = "wide-${context.sessionId}",
                        providerConcurrency = ProviderConcurrencyDeclaration.threadSafe(8),
                    ).also { instances += it }
                },
            ),
        )
        val error = assertThrows(IllegalStateException::class.java) {
            blueprint.resolveSessionAll(
                SessionCapabilityContext("s1"),
                AgentCapabilities.TOOL_PROVIDER,
            )
        }
        assertTrue(
            error.message.orEmpty().contains(RuntimeCompositionMessages.PRODUCED_CONCURRENCY_EXCEEDS_BINDING),
        )
        assertEquals(1, instances.size)
        assertEquals(1, instances.single().closeCount)
        assertTrue(instances.single().closed)
        blueprint.close()
        assertEquals(1, instances.single().closeCount)
    }

    @Test
    fun producedSerialized1UnderFactory4_isAcceptedAndLimiterSerializes() = runBlocking {
        val probes = mutableListOf<ProbeFactoryProvider>()
        val blueprint = ProfileCompiler().compile(
            testProfile("factory-cap-accept"),
            listOf(
                toolFactoryPlugin { context ->
                    ProbeFactoryProvider("serial-${context.sessionId}").also { probes += it }
                },
            ),
        )
        val instance = blueprint.resolveSessionAll(
            SessionCapabilityContext("s-serial"),
            AgentCapabilities.TOOL_PROVIDER,
        ).single() as ProbeFactoryProvider
        assertSame(probes.single(), instance)
        assertEquals(ProviderConcurrencyDeclaration.serialized(), instance.providerConcurrency)

        val limiter = ProviderConcurrencyLimiter()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val firstJob = launch {
            limiter.withPermit(instance) {
                instance.enter()
                firstEntered.complete(Unit)
                release.await()
                instance.leave()
            }
        }
        withTimeout(FACTORY_LIMITER_TIMEOUT_MS) { firstEntered.await() }
        val secondJob = launch {
            limiter.withPermit(instance) {
                instance.enter()
                secondEntered.complete(Unit)
                release.await()
                instance.leave()
            }
        }
        assertTrue(!secondEntered.isCompleted)
        assertEquals(1, instance.maxActive.get())
        release.complete(Unit)
        firstJob.join()
        withTimeout(FACTORY_LIMITER_TIMEOUT_MS) { secondEntered.await() }
        secondJob.join()
        assertEquals(1, instance.maxActive.get())
        blueprint.close()
    }

    @Test
    fun producedSessionFactoryDeclaration_isRejectedAndClosedOnce() {
        val instances = mutableListOf<TrackingToolProvider>()
        val blueprint = ProfileCompiler().compile(
            testProfile("factory-mode-reject"),
            listOf(
                toolFactoryPlugin { context ->
                    TrackingToolProvider(
                        providerId = "nested-${context.sessionId}",
                        providerConcurrency = ProviderConcurrencyDeclaration.sessionFactory(1),
                    ).also { instances += it }
                },
            ),
        )
        val error = assertThrows(IllegalStateException::class.java) {
            blueprint.resolveSessionAll(
                SessionCapabilityContext("s1"),
                AgentCapabilities.TOOL_PROVIDER,
            )
        }
        assertTrue(
            error.message.orEmpty().contains(RuntimeCompositionMessages.PRODUCED_SESSION_FACTORY_MODE),
        )
        assertEquals(1, instances.size)
        assertEquals(1, instances.single().closeCount)
        assertTrue(instances.single().closed)
        blueprint.close()
        assertEquals(1, instances.single().closeCount)
    }
}

private fun toolFactoryPlugin(
    factory: (SessionCapabilityContext) -> ToolProvider,
): AgentPlugin {
    val key = AgentCapabilities.TOOL_PROVIDER
    return object : AgentPlugin {
        override val id: String = "session-tools"
        override val descriptor = AgentPluginDescriptor(
            id = "session-tools",
            pluginVersion = PluginVersion(1, 0, 0),
            kernelApiRange = KernelApiRange(KernelApiVersions.CURRENT, KernelApiVersions.CURRENT),
            provides = setOf(key),
        )

        override fun install(scope: AgentPluginScope) {
            scope.provide(key, SessionCapabilityFactory(factory))
        }
    }
}

private class StableFactoryProvider(
    override val providerId: String,
) : ToolProvider, ToolCatalogCacheIdentity {
    override val catalogCacheIdentity: String = "shared-factory-identity"
    val listToolsCount = AtomicInteger(0)

    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    override suspend fun listTools(): List<ToolDescriptor> {
        listToolsCount.incrementAndGet()
        return listOf(
            ToolDescriptor(
                name = "echo",
                description = "echo",
                inputSchemaJson = """{"type":"object"}""",
            ),
        )
    }

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult = ToolCallResult(success = true, resultJson = "{}")
}

private class TrackingToolProvider(
    override val providerId: String,
    private val closedIds: MutableList<String> = mutableListOf(),
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized(),
) : ToolProvider, AutoCloseable {
    var closed: Boolean = false
        private set
    var closeCount: Int = 0
        private set

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(
            ToolDescriptor(
                name = "echo",
                description = "echo",
                inputSchemaJson = """{"type":"object"}""",
            ),
        )

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult = ToolCallResult(success = true, resultJson = "{}")

    override fun close() {
        closeCount += 1
        if (closed) return
        closed = true
        closedIds += providerId
    }
}

private class ProbeFactoryProvider(
    override val providerId: String,
) : ToolProvider {
    val active = AtomicInteger(0)
    val maxActive = AtomicInteger(0)

    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(
            ToolDescriptor(
                name = "echo",
                description = "echo",
                inputSchemaJson = """{"type":"object"}""",
            ),
        )

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult = ToolCallResult(success = true, resultJson = "{}")

    fun enter() {
        val current = active.incrementAndGet()
        maxActive.updateAndGet { maxOf(it, current) }
    }

    fun leave() {
        active.decrementAndGet()
    }
}

private const val FACTORY_LIMITER_TIMEOUT_MS = 5_000L

private fun sessionFactoryFinishModel(): ModelClient =
    object : ModelClient {
        override val providerConcurrency: ProviderConcurrencyDeclaration =
            ProviderConcurrencyDeclaration.serialized()
        override fun complete(request: ModelRequest) =
            flowOf(
                ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""),
                ModelEvent.Completed,
            )
    }

private fun request(session: String): AgentRequest = AgentRequest(
    requestId = "request-$session",
    sessionId = session,
    input = session,
)

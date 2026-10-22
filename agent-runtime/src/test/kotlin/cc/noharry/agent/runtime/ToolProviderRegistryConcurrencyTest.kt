@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolCatalogCacheIdentity
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.ToolInitializeContext
import cc.noharry.agent.kernel.api.ToolInitializeResult
import cc.noharry.agent.kernel.api.ToolPromptDescriptor
import cc.noharry.agent.kernel.api.ToolResourceDescriptor
import cc.noharry.agent.kernel.api.ToolDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import cc.noharry.agent.kernel.core.MaterializerContext
import cc.noharry.agent.kernel.core.ProviderConcurrencyLimiter
import cc.noharry.agent.kernel.core.ToolProviderRegistry

class ToolProviderRegistryConcurrencyTest {
    @Before
    fun clearEnvironmentCatalogCache() = runBlocking {
        ToolProviderRegistry.clearEnvironmentProviderCatalogCacheForTests()
    }

    @Test
    fun objectIdentityIsolatesSameServerIdOnCatalogAndCall() = runBlocking {
        val limiter = ProviderConcurrencyLimiter()
        val first = RegistryProbeProvider(
            serverId = "shared",
            providerConcurrency = ProviderConcurrencyDeclaration.serialized(),
        )
        val second = RegistryProbeProvider(
            serverId = "shared",
            providerConcurrency = ProviderConcurrencyDeclaration.serialized(),
        )
        val left = ToolProviderRegistry(listOf(first), limiter)
        val right = ToolProviderRegistry(listOf(second), limiter)
        val release = CompletableDeferred<Unit>()
        first.catalogHold = release
        second.catalogHold = release

        coroutineScope {
            val jobs = listOf(
                async { left.toolsJson(MaterializerContext(runId = "run-a")) },
                async { right.toolsJson(MaterializerContext(runId = "run-b")) },
            )
            withTimeout(TEST_TIMEOUT_MS) {
                first.catalogEntered.await()
                second.catalogEntered.await()
            }
            assertEquals(1, first.active.get())
            assertEquals(1, second.active.get())
            release.complete(Unit)
            jobs.awaitAll()
        }
        assertEquals(1, first.maxActive.get())
        assertEquals(1, second.maxActive.get())
    }

    @Test
    fun serializedCatalogAndCallNeverOverlap() = runBlocking {
        val provider = RegistryProbeProvider(providerConcurrency = ProviderConcurrencyDeclaration.serialized())
        val registry = ToolProviderRegistry(listOf(provider))
        val release = CompletableDeferred<Unit>()
        provider.catalogHold = release

        val catalog = launch { registry.toolsJson(context()) }
        withTimeout(TEST_TIMEOUT_MS) { provider.catalogEntered.await() }
        val call = launch {
            registry.callTool("mcp__probe__echo", "{}", context(runId = "call"))
        }
        assertEquals(1, provider.active.get())
        assertEquals(0, provider.callCount.get())
        release.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) { catalog.join(); call.join() }
        assertEquals(1, provider.maxActive.get())
        assertEquals(1, provider.callCount.get())
    }

    @Test
    fun threadSafeHonorsMaxNAndDifferentProvidersOverlap() = runBlocking {
        val first = RegistryProbeProvider(
            serverId = "one",
            providerConcurrency = ProviderConcurrencyDeclaration.threadSafe(2),
        )
        val second = RegistryProbeProvider(
            serverId = "two",
            providerConcurrency = ProviderConcurrencyDeclaration.threadSafe(2),
        )
        val registry = ToolProviderRegistry(listOf(first, second))
        val release = CompletableDeferred<Unit>()
        first.callHold = release
        second.callHold = release
        registry.toolsJson(context())

        val overlap = AtomicInteger(0)
        val bothActive = CompletableDeferred<Unit>()
        coroutineScope {
            val jobs = listOf(first, second).map { provider ->
                async {
                    registry.callTool(
                        qualifiedName = "mcp__${provider.serverId}__echo",
                        argumentsJson = "{}",
                        context = context(runId = "call-${provider.serverId}"),
                    )
                }
            }
            withTimeout(TEST_TIMEOUT_MS) {
                first.callEntered.await()
                second.callEntered.await()
            }
            if (first.active.get() == 1 && second.active.get() == 1) {
                overlap.set(1)
                bothActive.complete(Unit)
            }
            release.complete(Unit)
            jobs.awaitAll()
        }
        assertEquals(1, overlap.get())
        assertTrue(bothActive.isCompleted)
    }

    @Test
    fun compositeCatalogDoesNotDoubleAcquireSerializedPermit() = runBlocking {
        val provider = RegistryProbeProvider(providerConcurrency = ProviderConcurrencyDeclaration.serialized())
        val registry = ToolProviderRegistry(listOf(provider))
        val releaseInitialize = CompletableDeferred<Unit>()
        provider.initializeHold = releaseInitialize

        val catalog = launch { registry.toolsJson(context()) }
        withTimeout(TEST_TIMEOUT_MS) { provider.initializeEntered.await() }
        val call = launch {
            registry.callTool("mcp__probe__echo", "{}", context(runId = "interleave"))
        }
        assertEquals(1, provider.active.get())
        assertEquals(0, provider.listToolsCount.get())
        releaseInitialize.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) { catalog.join(); call.join() }
        assertEquals(1, provider.maxActive.get())
        assertTrue(provider.listToolsCount.get() >= 1)
    }

    @Test
    fun resourcesAndPromptsHonorSerializedLimitWithoutDeadlock() = runBlocking {
        val provider = RegistryProbeProvider(providerConcurrency = ProviderConcurrencyDeclaration.serialized())
        val registry = ToolProviderRegistry(listOf(provider))
        val release = CompletableDeferred<Unit>()
        provider.resourceHold = release

        val resources = launch { registry.resourcesIndexJson(context()) }
        withTimeout(TEST_TIMEOUT_MS) { provider.resourceEntered.await() }
        val prompts = launch { registry.promptsIndexJson(context(runId = "prompts")) }
        assertEquals(1, provider.active.get())
        release.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) {
            resources.join()
            prompts.join()
        }
        assertEquals(1, provider.maxActive.get())
        assertTrue(registry.promptsIndexJson(context(runId = "prompts-2")).contains("prompt"))
    }

    @Test
    fun cancellationWhileBlockedAcquiringSerializedPermit() = runBlocking {
        val provider = RegistryProbeProvider(providerConcurrency = ProviderConcurrencyDeclaration.serialized())
        val registry = ToolProviderRegistry(listOf(provider))
        registry.toolsJson(context())
        val release = CompletableDeferred<Unit>()
        provider.callHold = release

        val holder = launch {
            registry.callTool("mcp__probe__echo", "{}", context(runId = "holder"))
        }
        withTimeout(TEST_TIMEOUT_MS) { provider.callEntered.await() }

        val waiterStarted = CompletableDeferred<Unit>()
        val waiter = launch {
            waiterStarted.complete(Unit)
            registry.callTool("mcp__probe__echo", "{}", context(runId = "blocked"))
        }
        withTimeout(TEST_TIMEOUT_MS) { waiterStarted.await() }
        repeat(16) { yield() }
        delay(20)
        assertEquals(1, provider.active.get())
        assertEquals(1, provider.callCount.get())
        waiter.cancelAndJoin()

        provider.callHold = null
        release.complete(Unit)
        holder.join()
        val result = withTimeout(TEST_TIMEOUT_MS) {
            registry.callTool("mcp__probe__echo", "{}", context(runId = "next"))
        }
        assertTrue(result?.result?.success == true)
        assertEquals(1, provider.maxActive.get())
    }

    @Test
    fun cancellationReleasesProviderPermitForNextCall() = runBlocking {
        val provider = RegistryProbeProvider(providerConcurrency = ProviderConcurrencyDeclaration.serialized())
        val registry = ToolProviderRegistry(listOf(provider))
        registry.toolsJson(context())
        val release = CompletableDeferred<Unit>()
        provider.callHold = release

        val blocked = launch {
            registry.callTool("mcp__probe__echo", "{}", context(runId = "blocked"))
        }
        withTimeout(TEST_TIMEOUT_MS) { provider.callEntered.await() }
        blocked.cancelAndJoin()

        provider.callHold = null
        val result = withTimeout(TEST_TIMEOUT_MS) {
            registry.callTool("mcp__probe__echo", "{}", context(runId = "next"))
        }
        assertTrue(result?.result?.success == true)
    }

    @Test
    fun stableCatalogIdentityDoesNotMisattributeAcrossProviderClasses() = runBlocking {
        val alpha = AlphaStableCatalogProvider()
        val beta = BetaStableCatalogProvider()
        val left = ToolProviderRegistry(listOf(alpha))
        val right = ToolProviderRegistry(listOf(beta))

        val alphaJson = left.toolsJson(context(runId = "alpha"))
        val betaJson = right.toolsJson(context(runId = "beta"))

        assertTrue(alphaJson.contains("alpha_tool"))
        assertFalse(alphaJson.contains("beta_tool"))
        assertTrue(betaJson.contains("beta_tool"))
        assertFalse(betaJson.contains("alpha_tool"))
        assertEquals(1, alpha.listToolsCount.get())
        assertEquals(1, beta.listToolsCount.get())
    }

    @Test
    fun sameClassStableIdentityStillSharesEnvironmentCatalog() = runBlocking {
        val first = AlphaStableCatalogProvider()
        val second = AlphaStableCatalogProvider()
        val left = ToolProviderRegistry(listOf(first))
        val right = ToolProviderRegistry(listOf(second))

        left.toolsJson(context(runId = "first"))
        right.toolsJson(context(runId = "second"))

        assertEquals(1, first.listToolsCount.get())
        assertEquals(0, second.listToolsCount.get())
    }

    @Test
    fun closeClearsLimiterAndSnapshotCaches() = runBlocking {
        val limiter = ProviderConcurrencyLimiter()
        val provider = RegistryProbeProvider(providerConcurrency = ProviderConcurrencyDeclaration.threadSafe())
        val registry = ToolProviderRegistry(listOf(provider), limiter)
        registry.toolsJson(context())
        assertTrue(limiter.cachedEntryCountForTests() >= 1)
        assertTrue(registry.cachedLimiterEntryCountForTests() >= 1)
        assertTrue(registry.cachedSnapshotCountForTests() >= 1)

        registry.close()

        assertEquals(0, limiter.cachedEntryCountForTests())
        assertEquals(0, registry.cachedLimiterEntryCountForTests())
        assertEquals(0, registry.cachedSnapshotCountForTests())
    }

    @Test
    fun sequentialCatalogReusesCache() = runBlocking {
        val provider = RegistryProbeProvider(providerConcurrency = ProviderConcurrencyDeclaration.threadSafe())
        val registry = ToolProviderRegistry(listOf(provider))
        val context = context()
        registry.toolsJson(context)
        registry.toolsJson(context)
        assertEquals(1, provider.listToolsCount.get())
    }

    @Test
    fun directSessionFactoryIsRejected() {
        val provider = RegistryProbeProvider(
            providerConcurrency = ProviderConcurrencyDeclaration.sessionFactory(1),
        )
        val error = assertThrows(IllegalStateException::class.java) {
            ToolProviderRegistry(listOf(provider))
        }
        assertTrue(error.message.orEmpty().contains("SESSION_FACTORY"))
    }
}

private class AlphaStableCatalogProvider(
    override val serverId: String = "shared",
    override val catalogCacheIdentity: String = "colliding-identity",
) : McpClientProvider, ToolCatalogCacheIdentity {
    val listToolsCount = AtomicInteger(0)
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe()

    override suspend fun listTools(): List<ToolDescriptor> {
        listToolsCount.incrementAndGet()
        return listOf(
            ToolDescriptor(
                name = "alpha_tool",
                description = "alpha",
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

private class BetaStableCatalogProvider(
    override val serverId: String = "shared",
    override val catalogCacheIdentity: String = "colliding-identity",
) : McpClientProvider, ToolCatalogCacheIdentity {
    val listToolsCount = AtomicInteger(0)
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe()

    override suspend fun listTools(): List<ToolDescriptor> {
        listToolsCount.incrementAndGet()
        return listOf(
            ToolDescriptor(
                name = "beta_tool",
                description = "beta",
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

private class RegistryProbeProvider(
    override val serverId: String = "probe",
    override val providerConcurrency: ProviderConcurrencyDeclaration,
) : McpClientProvider {
    val active = AtomicInteger(0)
    val maxActive = AtomicInteger(0)
    val listToolsCount = AtomicInteger(0)
    val callCount = AtomicInteger(0)
    val catalogEntered = CompletableDeferred<Unit>()
    val initializeEntered = CompletableDeferred<Unit>()
    val callEntered = CompletableDeferred<Unit>()
    val resourceEntered = CompletableDeferred<Unit>()
    var catalogHold: CompletableDeferred<Unit>? = null
    var initializeHold: CompletableDeferred<Unit>? = null
    var callHold: CompletableDeferred<Unit>? = null
    var resourceHold: CompletableDeferred<Unit>? = null

    override suspend fun initialize(context: ToolInitializeContext): ToolInitializeResult {
        inSection(initializeEntered, initializeHold)
        return ToolInitializeResult.connected()
    }

    override suspend fun listTools(): List<ToolDescriptor> {
        listToolsCount.incrementAndGet()
        inSection(catalogEntered, catalogHold)
        return listOf(
            ToolDescriptor(
                name = "echo",
                description = "echo",
                inputSchemaJson = """{"type":"object"}""",
            ),
        )
    }

    override suspend fun listResources(): List<ToolResourceDescriptor> {
        inSection(resourceEntered, resourceHold)
        return listOf(ToolResourceDescriptor(uri = "resource://probe", name = "resource"))
    }

    override suspend fun listPrompts(): List<ToolPromptDescriptor> =
        listOf(ToolPromptDescriptor(name = "prompt", description = "prompt"))

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult {
        callCount.incrementAndGet()
        inSection(callEntered, callHold)
        return ToolCallResult(success = true, resultJson = """{"ok":true}""")
    }

    private suspend fun inSection(
        entered: CompletableDeferred<Unit>,
        hold: CompletableDeferred<Unit>?,
    ) {
        val current = active.incrementAndGet()
        maxActive.updateAndGet { maxOf(it, current) }
        if (!entered.isCompleted) entered.complete(Unit)
        try {
            hold?.await()
        } finally {
            active.decrementAndGet()
        }
    }
}

private fun context(runId: String = "run"): MaterializerContext = MaterializerContext(runId = runId)

private const val TEST_TIMEOUT_MS = 5_000L

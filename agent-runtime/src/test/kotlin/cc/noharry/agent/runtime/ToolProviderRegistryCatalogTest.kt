@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolInitializeContext
import cc.noharry.agent.kernel.api.ToolInitializeResult
import cc.noharry.agent.kernel.api.ToolPromptDescriptor
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.api.ToolProviderStatus
import cc.noharry.agent.kernel.api.ToolResourceDescriptor
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.kernel.core.tools.ToolProviderRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ToolProviderRegistryCatalogTest {
    @Before
    fun clearEnvironmentCatalogCache() = runBlocking {
        ToolProviderRegistry.clearEnvironmentProviderCatalogCacheForTests()
    }

    @Test
    fun snapshotCacheKeyIsolatesSameRunIdAcrossSessions() = runBlocking {
        val provider = CatalogGateProvider()
        val registry = ToolProviderRegistry(listOf(provider))
        val first = MaterializerContext(sessionId = "s1", runId = "shared-run", requestId = "req-a")
        val second = MaterializerContext(sessionId = "s2", runId = "shared-run", requestId = "req-b")

        registry.toolsJson(first)
        registry.toolsJson(second)
        // Shared stable provider env catalog lists once; tuple snapshots stay isolated.
        assertEquals(1, provider.listToolsCount.get())
        assertEquals(2, registry.cachedSnapshotCountForTests())
        assertRoutedCalls(registry, provider, first, second)

        registry.toolsJson(first)
        registry.toolsJson(second)
        assertEquals(1, provider.listToolsCount.get())
        assertEquals(2, registry.cachedSnapshotCountForTests())
    }

    @Test
    fun closeSessionEvictsOnlyMatchingTupleKeys() = runBlocking {
        val provider = CatalogGateProvider()
        val registry = ToolProviderRegistry(listOf(provider))
        val first = MaterializerContext(sessionId = "s1", runId = "shared-run", requestId = "req-a")
        val second = MaterializerContext(sessionId = "s2", runId = "shared-run", requestId = "req-b")
        registry.toolsJson(first)
        registry.toolsJson(second)
        assertEquals(2, registry.cachedSnapshotCountForTests())
        assertEquals(1, provider.listToolsCount.get())

        registry.closeSession("s1")
        assertEquals(1, registry.cachedSnapshotCountForTests())
        assertEquals(1, provider.listToolsCount.get())
        registry.toolsJson(second)
        assertEquals(1, registry.cachedSnapshotCountForTests())
        assertEquals(1, provider.listToolsCount.get())
        val remaining = registry.callTool(QUALIFIED_GATE_ECHO, "{}", second)
        assertNotNull(remaining)
        assertSame(provider, remaining!!.tool.provider)
        assertEquals(listOf(callContext(second)), provider.callContexts)

        registry.toolsJson(first)
        assertEquals(2, registry.cachedSnapshotCountForTests())
        assertEquals(1, provider.listToolsCount.get())
    }

    @Test
    fun duplicateProviderIdServerQueryUsesStableIndexSuffix() = runBlocking {
        val first = CatalogGateProvider(providerId = "shared", toolName = "alpha")
        val second = CatalogGateProvider(providerId = "shared", toolName = "beta")
        val registry = ToolProviderRegistry(listOf(first, second))
        val context = MaterializerContext(sessionId = "s", runId = "r", requestId = "q")

        val primary = registry.serverToolsJson("shared", context)
        val suffixed = registry.serverToolsJson("shared__1", context)

        assertNotNull(primary)
        assertNotNull(suffixed)
        assertTrue(primary!!.contains("\"serverId\":\"shared\""))
        assertTrue(primary.contains("alpha"))
        assertTrue(suffixed!!.contains("\"serverId\":\"shared__1\""))
        assertTrue(suffixed.contains("beta"))
        assertNull(registry.serverToolsJson("missing", context))
        assertEquals(1, first.listToolsCount.get())
        assertEquals(1, second.listToolsCount.get())
    }

    @Test
    fun resourcesAndPromptsSkipListUnlessConnected() = runBlocking {
        val provider = CatalogGateProvider(initStatus = ToolProviderStatus.DISCONNECTED)
        val registry = ToolProviderRegistry(listOf(provider))
        val context = MaterializerContext(sessionId = "s", runId = "r", requestId = "q")

        val resources = registry.resourcesIndexJson(context)
        val prompts = registry.promptsIndexJson(context)

        assertTrue(resources.contains("\"resources\":[]"))
        assertTrue(prompts.contains("\"prompts\":[]"))
        assertEquals(0, provider.listResourcesCount.get())
        assertEquals(0, provider.listPromptsCount.get())
    }

    @Test
    fun resourcesAndPromptsTreatRuntimeExceptionAsEmptyContribution() = runBlocking {
        val provider = CatalogGateProvider(
            listResourcesError = RuntimeException("resource boom"),
            listPromptsError = RuntimeException("prompt boom"),
        )
        val registry = ToolProviderRegistry(listOf(provider))
        val context = MaterializerContext(sessionId = "s", runId = "r", requestId = "q")

        assertTrue(registry.resourcesIndexJson(context).contains("\"resources\":[]"))
        assertTrue(registry.promptsIndexJson(context).contains("\"prompts\":[]"))
        assertEquals(1, provider.listResourcesCount.get())
        assertEquals(1, provider.listPromptsCount.get())
    }

    @Test
    fun resourcesRethrowCancellationException() = runBlocking {
        val provider = CatalogGateProvider(listResourcesError = CancellationException("cancelled"))
        val registry = ToolProviderRegistry(listOf(provider))
        try {
            registry.resourcesIndexJson(MaterializerContext(sessionId = "s", runId = "r", requestId = "q"))
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertEquals(1, provider.listResourcesCount.get())
    }
}

private class CatalogGateProvider(
    override val providerId: String = "gate",
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe(),
    private val initStatus: ToolProviderStatus = ToolProviderStatus.CONNECTED,
    private val toolName: String = "echo",
    private val listResourcesError: Throwable? = null,
    private val listPromptsError: Throwable? = null,
) : ToolProvider {
    val listToolsCount = AtomicInteger(0)
    val listResourcesCount = AtomicInteger(0)
    val listPromptsCount = AtomicInteger(0)
    val callContexts = mutableListOf<ToolCallContext>()

    override suspend fun initialize(context: ToolInitializeContext): ToolInitializeResult =
        ToolInitializeResult(initStatus, initStatus.name)

    override suspend fun listTools(): List<ToolDescriptor> {
        listToolsCount.incrementAndGet()
        return listOf(ToolDescriptor(name = toolName, description = toolName, inputSchemaJson = """{"type":"object"}"""))
    }

    override suspend fun listResources(): List<ToolResourceDescriptor> {
        listResourcesCount.incrementAndGet()
        listResourcesError?.let { throw it }
        return listOf(ToolResourceDescriptor(uri = "resource://$providerId", name = "resource"))
    }

    override suspend fun listPrompts(): List<ToolPromptDescriptor> {
        listPromptsCount.incrementAndGet()
        listPromptsError?.let { throw it }
        return listOf(ToolPromptDescriptor(name = "prompt", description = "prompt"))
    }

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult {
        callContexts += context
        return ToolCallResult(success = true, resultJson = "{}")
    }
}

private suspend fun assertRoutedCalls(
    registry: ToolProviderRegistry,
    provider: CatalogGateProvider,
    first: MaterializerContext,
    second: MaterializerContext,
) {
    val firstCall = registry.callTool(QUALIFIED_GATE_ECHO, "{}", first)
    val secondCall = registry.callTool(QUALIFIED_GATE_ECHO, "{}", second)
    assertNotNull(firstCall)
    assertNotNull(secondCall)
    assertSame(provider, firstCall!!.tool.provider)
    assertSame(provider, secondCall!!.tool.provider)
    assertEquals(listOf(callContext(first), callContext(second)), provider.callContexts)
}

private fun callContext(context: MaterializerContext): ToolCallContext =
    ToolCallContext(sessionId = context.sessionId, runId = context.runId, requestId = context.requestId)

private const val QUALIFIED_GATE_ECHO = "mcp__gate__echo"

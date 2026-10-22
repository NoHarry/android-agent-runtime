@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolCatalogCacheIdentity
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolProvider
import kotlinx.coroutines.runBlocking
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

class ToolProviderRegistryAdapterTest {
    @Before
    fun clearEnvironmentCatalogCache() = runBlocking {
        ToolProviderRegistry.clearEnvironmentProviderCatalogCacheForTests()
    }

    @Test
    fun genericProviderKeepsMcpQualifiedNamesAndServerIdJson() = runBlocking {
        val provider = GenericCatalogProvider(providerId = "navi")
        val registry = ToolProviderRegistry(listOf(provider))
        val tools = registry.toolsJson(MaterializerContext(runId = "run"))
        val servers = registry.serversJson(MaterializerContext(runId = "run"))

        assertTrue(tools.contains("mcp__navi__echo"))
        assertTrue(servers.contains("\"id\":\"navi\""))
        assertEquals(1, provider.listToolsCount.get())
        val routed = registry.callTool("mcp__navi__echo", "{}", MaterializerContext(runId = "call"))
        assertTrue(routed?.result?.success == true)
    }

    @Test
    fun cacheKeyBindsClassAndProviderIdAndIdentity() = runBlocking {
        val alpha = GenericCatalogProvider(providerId = "shared", toolName = "alpha_tool")
        val beta = OtherCatalogProvider(providerId = "shared", toolName = "beta_tool")
        val left = ToolProviderRegistry(listOf(alpha))
        val right = ToolProviderRegistry(listOf(beta))

        val alphaJson = left.toolsJson(MaterializerContext(runId = "alpha"))
        val betaJson = right.toolsJson(MaterializerContext(runId = "beta"))

        assertTrue(alphaJson.contains("alpha_tool"))
        assertFalse(alphaJson.contains("beta_tool"))
        assertTrue(betaJson.contains("beta_tool"))
        assertFalse(betaJson.contains("alpha_tool"))
        assertEquals(1, alpha.listToolsCount.get())
        assertEquals(1, beta.listToolsCount.get())
    }

    @Test
    fun limiterAndRegistryRejectDirectSessionFactory() {
        val provider = GenericCatalogProvider(
            providerConcurrency = ProviderConcurrencyDeclaration.sessionFactory(1),
        )
        val limiter = ProviderConcurrencyLimiter()
        val limiterError = assertThrows(IllegalStateException::class.java) {
            runBlocking { limiter.withPermit(provider) { } }
        }
        val registryError = assertThrows(IllegalStateException::class.java) {
            ToolProviderRegistry(listOf(provider))
        }
        assertTrue(limiterError.message.orEmpty().contains("SESSION_FACTORY"))
        assertTrue(registryError.message.orEmpty().contains("SESSION_FACTORY"))
    }
}

private open class GenericCatalogProvider(
    override val providerId: String = "generic",
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe(),
    override val catalogCacheIdentity: String = "stable-v1",
    private val toolName: String = "echo",
) : ToolProvider, ToolCatalogCacheIdentity {
    val listToolsCount = AtomicInteger(0)

    override suspend fun listTools(): List<ToolDescriptor> {
        listToolsCount.incrementAndGet()
        return listOf(
            ToolDescriptor(name = toolName, description = toolName, inputSchemaJson = """{"type":"object"}"""),
        )
    }

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult = ToolCallResult(success = true, resultJson = """{"ok":true}""")
}

private class OtherCatalogProvider(
    providerId: String,
    toolName: String,
) : GenericCatalogProvider(providerId = providerId, toolName = toolName)

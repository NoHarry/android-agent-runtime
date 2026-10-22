@file:OptIn(ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.api

import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ToolProviderContractTest {
    @Test
    fun toolProviderKeyIsCanonicalMultiple() {
        val key = AgentCapabilities.TOOL_PROVIDER
        val typed: CapabilityKey<ToolProvider> = AgentCapabilities.TOOL_PROVIDER

        assertSame(key, typed)
        assertEquals(CapabilityCardinality.MULTIPLE, key.cardinality)
        assertEquals("tool_provider", key.localName)
        assertEquals("agent:tool_provider:1", key.diagnosticId)
    }

    @Test
    fun mcpAdapterIsAcceptedAsToolProvider() {
        val provider = RecordingMcpProvider()
        val scope = RecordingProvideScope()
        scope.provide(
            AgentCapabilities.TOOL_PROVIDER,
            provider,
            ProviderConcurrencyDeclaration.serialized(),
        )
        assertSame(provider, scope.values.single())
    }

    @Test
    fun explicitSerializedDeclarationIsHonoredOnGenericProvider() {
        val provider = RecordingToolProvider()
        assertEquals(ProviderConcurrencyDeclaration.serialized(), provider.providerConcurrency)
        assertEquals(ProviderConcurrencyMode.RUNTIME_SHARED_SERIALIZED, provider.providerConcurrency.mode)
        assertEquals(1, provider.providerConcurrency.maxConcurrentCalls)
    }

    @Test
    fun explicitDeclarationIsHonoredOnGenericProvider() {
        val provider = RecordingToolProvider(
            providerConcurrency = ProviderConcurrencyDeclaration.threadSafe(3),
        )
        assertEquals(ProviderConcurrencyMode.RUNTIME_SHARED_THREAD_SAFE, provider.providerConcurrency.mode)
        assertEquals(3, provider.providerConcurrency.maxConcurrentCalls)
    }
}

private class RecordingToolProvider(
    override val providerId: String = "generic",
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized(),
) : ToolProvider {
    override suspend fun listTools(): List<ToolDescriptor> = emptyList()

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult = ToolCallResult(success = true, resultJson = "{}")
}

private class RecordingMcpProvider : McpClientProvider {
    override val serverId: String = "alias-mcp"
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    override suspend fun listTools(): List<ToolDescriptor> = emptyList()

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult = ToolCallResult(success = true, resultJson = "{}")
}

private class RecordingProvideScope : AgentPluginScope {
    val values = mutableListOf<Any>()

    override fun <T : Any> provide(
        key: CapabilityKey<T>,
        value: T,
        concurrency: ProviderConcurrencyDeclaration,
    ) {
        values += value
    }

    override fun <T : Any> provide(
        key: CapabilityKey<T>,
        factory: SessionCapabilityFactory<T>,
        concurrency: ProviderConcurrencyDeclaration,
    ) {
        error("factory provide is unused in this contract test")
    }

    override fun <T : Any> resolve(key: CapabilityKey<T>): T? = null

    override fun <T : Any> resolveAll(key: CapabilityKey<T>): List<T> = emptyList()

    override fun <T : AutoCloseable> own(resource: T): T = resource
}

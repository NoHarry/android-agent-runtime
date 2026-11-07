@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.spi.AgentProfile
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.mcp.McpClientProvider
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.kernel.core.plugin.RuntimeCompositionMessages
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeBuilderDelegationTest {
    @Test
    fun builderHasNoDuplicateCapabilityCompositionFields() {
        val names = AgentRuntimeBuilder::class.java.declaredFields.map { it.name }.toSet()
        listOf(
            "modelClient",
            "mcpProviders",
            "contextProviders",
            "turnObservers",
            "execEngine",
            "sessionEventLog",
        ).forEach { field ->
            assertFalse("Builder still has composition field $field", field in names)
        }
        assertTrue("declarations" in names)
        assertTrue("plugins" in names)
    }

    @Test
    fun toolProvidersShareTheCanonicalCapabilityPath() = runBlocking {
        val seen = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .profile(AgentProfile("delegation"))
            .modelClient(finishModel(seen))
            .toolProvider(catalogProvider("generic"))
            .toolProvider(catalogProvider("legacy"))
            .buildEngine() as DefaultAgentRuntime

        runtime.run(request("delegation")).toList()
        val providers = runtime.runtimeBlueprintForTest()!!
            .resolveSharedAll(cc.noharry.agent.spi.AgentCapabilities.TOOL_PROVIDER)
        assertEquals(setOf("generic", "legacy"), providers.map { it.providerId }.toSet())
        assertEquals("delegation", runtime.runtimeBlueprintForTest()?.profile?.id)
        runtime.close()
        assertTrue(seen.isNotEmpty())
    }

    @Test
    fun defaultProfileIdentityIsCompiledWhenHostOmitsProfile() {
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(finishModel(mutableListOf()))
            .buildEngine() as DefaultAgentRuntime
        assertEquals(
            RuntimeCompositionMessages.DEFAULT_PROFILE_ID,
            runtime.runtimeBlueprintForTest()?.profile?.id,
        )
        runtime.close()
    }
}

private fun finishModel(seen: MutableList<ModelRequest>): ModelClient =
    object : ModelClient {
        override val providerConcurrency: ProviderConcurrencyDeclaration =
            ProviderConcurrencyDeclaration.serialized()
        override fun complete(request: ModelRequest) =
            flowOf(
                ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""),
                ModelEvent.Completed,
            ).also { seen += request }
    }

private fun catalogProvider(id: String): McpClientProvider =
    object : McpClientProvider {
        override val providerConcurrency: ProviderConcurrencyDeclaration =
            ProviderConcurrencyDeclaration.serialized()
        override val serverId: String = id
        override suspend fun listTools(): List<ToolDescriptor> =
            listOf(ToolDescriptor(name = "echo", description = id, inputSchemaJson = """{"type":"object"}"""))
        override suspend fun callTool(
            toolName: String,
            argumentsJson: String,
            context: ToolCallContext,
        ): ToolCallResult = ToolCallResult(success = true, resultJson = "{}")
    }

private fun request(name: String) = cc.noharry.agent.spi.AgentRequest(
    requestId = "request-$name",
    sessionId = "session-$name",
    input = name,
)

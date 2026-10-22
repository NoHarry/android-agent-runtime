@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration

import cc.noharry.agent.kernel.api.AgentProfile
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import cc.noharry.agent.kernel.core.RuntimeCompositionMessages

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
            .build() as DefaultAgentRuntime

        runtime.run(request("delegation")).toList()
        val providers = runtime.runtimeBlueprintForTest()!!
            .resolveSharedAll(cc.noharry.agent.kernel.api.AgentCapabilities.TOOL_PROVIDER)
        assertEquals(setOf("generic", "legacy"), providers.map { it.providerId }.toSet())
        assertEquals("delegation", runtime.runtimeBlueprintForTest()?.profile?.id)
        runtime.close()
        assertTrue(seen.isNotEmpty())
    }

    @Test
    fun defaultProfileIdentityIsCompiledWhenHostOmitsProfile() {
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(finishModel(mutableListOf()))
            .build() as DefaultAgentRuntime
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

private fun request(name: String) = cc.noharry.agent.kernel.api.AgentRequest(
    requestId = "request-$name",
    sessionId = "session-$name",
    input = name,
)

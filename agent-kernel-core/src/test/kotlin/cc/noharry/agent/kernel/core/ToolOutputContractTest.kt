@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ToolOutputContractTest {
    @Test
    fun cardRetainsExactOutputSchemaWithoutTreatingItAsInputOrMutatingDescriptorCopy() = runTest {
        val output = """{"type":"object","properties":{"songs":{"type":"array","items":{"type":"object","properties":{"id":{"type":"string"}}}}}}"""
        val descriptor = ToolDescriptor("search", "search", """{"type":"object","properties":{"query":{"type":"string"}}}""", outputSchemaJson = output)
        val provider = object : ToolProvider {
            override val providerId = "fixture"
            override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
            override suspend fun listTools() = listOf(descriptor.copy(title = "new title"))
            override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext) = error("not called")
        }
        val registry = ToolProviderRegistry(listOf(provider))
        val context = MaterializerContext(sessionId = "s", runId = "r", requestId = "q")
        val tool = registry.allTools(context).single()
        val card = Json.parseToJsonElement(registry.toolCardJson(tool.qualifiedName, context)!!).jsonObject
        assertEquals(Json.parseToJsonElement(output), card["outputSchema"])
        assertFalse(registry.toolSchema(tool.qualifiedName, context)!!.schemaJson.contains("songs"))
        assertFalse(registry.toolsIndexJson(context).contains("songs"))
    }
}

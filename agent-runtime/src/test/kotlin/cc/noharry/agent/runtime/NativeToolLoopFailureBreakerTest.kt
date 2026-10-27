@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.context.*
import cc.noharry.agent.kernel.core.loop.*
import cc.noharry.agent.kernel.core.memory.*
import cc.noharry.agent.kernel.core.plugin.*
import cc.noharry.agent.kernel.core.state.*
import cc.noharry.agent.kernel.core.tools.*
import cc.noharry.agent.kernel.core.trace.*
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeToolLoopFailureBreakerTest {
    @Test
    fun deliveredBusinessRefusalsKeepRecoveryQueryCallableAndPreserveTypedResults() = runBlocking {
        val backend = BusinessRefusalMcpProvider()
        val requests = mutableListOf<ModelRequest>()
        val rounds = listOf(listOf(schemaReadCall(), ModelEvent.Completed)) +
            (1..5).map { listOf(backendCall("refusal-$it", 20 + it), ModelEvent.Completed) } + listOf(
                listOf(ModelEvent.ToolCall("query", qualifiedMcpTool("set_temperature"), """{"action":"query"}"""), ModelEvent.Completed),
                listOf(finishCall(), ModelEvent.Completed),
            )
        val runtime = AgentRuntimeBuilder.create().workspaceRoot(Files.createTempDirectory("business-refusal-").toFile())
            .toolProvider(backend).modelClient(FakeModelClient(eventsByRound = rounds, seenRequests = requests)).build()
        val result = runtime.run(agentRequest()).toList().filterIsInstance<AgentEvent.ResultAvailable>().single().result
        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals(6, backend.calls)
        val toolResults = requests.last().messages.filter { it.role == "tool" && it.toolName == qualifiedMcpTool("set_temperature") }
        assertEquals(6, toolResults.size)
        assertTrue(toolResults.take(5).all { it.content == BUSINESS_REFUSAL })
        assertTrue(toolResults.take(5).all { !backendToolCallResult(it.content).ok })
        assertTrue(toolResults.last().content.contains("currentValue"))
    }

    @Test
    fun identicalFailingBackendCallIsSuppressedAfterThreshold() = runBlocking {
        val backend = FailingMcpProvider()
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("failure-breaker-").toFile())
            .toolProvider(backend)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(schemaReadCall(), ModelEvent.Completed),
                        listOf(backendCall("backend-1"), ModelEvent.Completed),
                        listOf(backendCall("backend-2"), ModelEvent.Completed),
                        listOf(backendCall("backend-3"), ModelEvent.Completed),
                        listOf(finishCall(), ModelEvent.Completed),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals("空调暂时控制不了", result.response)
        assertEquals(listOf("set_temperature", "set_temperature"), backend.calledTools)
        val suppressedResult = seenRequests.last().messages.last {
            it.role == "tool" && it.toolName == qualifiedMcpTool("set_temperature")
        }
        assertTrue(suppressedResult.content.contains("Do not retry"))
        assertTrue(suppressedResult.content.contains(""""retryable":false"""))
    }

    @Test
    fun mcpStyleIsErrorEnvelopeTripsToolTierAcrossVaryingArguments() = runBlocking {
        val backend = IsErrorEnvelopeMcpProvider()
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("failure-breaker-iserror-").toFile())
            .toolProvider(backend)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(schemaReadCall(), ModelEvent.Completed),
                        listOf(backendCall("backend-1", degreesCelsius = 24), ModelEvent.Completed),
                        listOf(backendCall("backend-2", degreesCelsius = 23), ModelEvent.Completed),
                        listOf(backendCall("backend-3", degreesCelsius = 22), ModelEvent.Completed),
                        listOf(backendCall("backend-4", degreesCelsius = 21), ModelEvent.Completed),
                        listOf(backendCall("backend-5", degreesCelsius = 20), ModelEvent.Completed),
                        listOf(finishCall(), ModelEvent.Completed),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals(4, backend.calledTools.size)
        val suppressedResult = seenRequests.last().messages.last {
            it.role == "tool" && it.toolName == qualifiedMcpTool("set_temperature")
        }
        assertTrue(suppressedResult.content.contains("Do not call this tool again"))
        assertTrue(suppressedResult.content.contains(""""retryable":false"""))
    }

    @Test
    fun differentArgumentsAreStillExecuted() = runBlocking {
        val backend = FailingMcpProvider()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("failure-breaker-args-").toFile())
            .toolProvider(backend)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(schemaReadCall(), ModelEvent.Completed),
                        listOf(backendCall("backend-1"), ModelEvent.Completed),
                        listOf(backendCall("backend-2"), ModelEvent.Completed),
                        listOf(backendCall("backend-3", degreesCelsius = 18), ModelEvent.Completed),
                        listOf(finishCall(), ModelEvent.Completed),
                    ),
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals(3, backend.calledTools.size)
    }
}

private const val BUSINESS_REFUSAL = """{"error":null,"result":{"isError":true,"structuredContent":{"status":{"name":"INVALID_ARGUMENT","details":{"reason":"limit_reached"}},"upstreamCode":10001}}}"""

private class BusinessRefusalMcpProvider : McpClientProvider {
    override val serverId = TEST_MCP_SERVER_ID
    override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
    var calls = 0
    override suspend fun listTools() = listOf(mcpTool("set_temperature").copy(
        inputSchemaJson = """{"type":"object","additionalProperties":true}""",
    ))
    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        calls++
        return if (argumentsJson.contains("query")) ToolCallResult(true, """{"ok":true,"currentValue":22}""")
        else ToolCallResult(false, BUSINESS_REFUSAL)
    }
}

private class FailingMcpProvider : McpClientProvider {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override val serverId: String = TEST_MCP_SERVER_ID
    override val displayName: String = TEST_MCP_SERVER_ID
    val calledTools = mutableListOf<String>()

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(
            mcpTool("set_temperature").copy(inputSchemaJson = """{"type":"object","additionalProperties":true}"""),
        )

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult {
        calledTools += toolName
        return ToolCallResult(success = false, resultJson = "device offline", retryable = true)
    }
}

private class IsErrorEnvelopeMcpProvider : McpClientProvider {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override val serverId: String = TEST_MCP_SERVER_ID
    override val displayName: String = TEST_MCP_SERVER_ID
    val calledTools = mutableListOf<String>()

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(
            mcpTool("set_temperature").copy(inputSchemaJson = """{"type":"object","additionalProperties":true}"""),
        )

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult {
        calledTools += toolName
        return ToolCallResult(
            success = true,
            resultJson = """{"result":{"content":[{"type":"text","text":"出了点小问题，请稍后再试"}],"isError":true,"statusCode":0},"id":1,"jsonrpc":"2.0"}""",
        )
    }
}

private fun schemaReadCall(): ModelEvent.ToolCall =
    ModelEvent.ToolCall(
        "schema-1",
        "read_file",
        """{"path":"/mcp/tools/${qualifiedMcpTool("set_temperature")}/schema.json"}""",
    )

private fun backendCall(id: String, degreesCelsius: Int = 22): ModelEvent.ToolCall =
    ModelEvent.ToolCall(
        id,
        qualifiedMcpTool("set_temperature"),
        """{"degreesCelsius":$degreesCelsius}""",
    )

private fun finishCall(): ModelEvent.ToolCall =
    ModelEvent.ToolCall("finish-1", "finish", """{"response":"空调暂时控制不了"}""")

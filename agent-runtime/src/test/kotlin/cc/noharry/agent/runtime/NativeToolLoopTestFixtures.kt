@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal class FakeModelClient(
    private val eventsByRound: List<List<ModelEvent>>,
    private val seenRequests: MutableList<ModelRequest> = mutableListOf(),
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    constructor(
        vararg events: ModelEvent,
    ) : this(listOf(events.toList()), mutableListOf())

    private var round = 0

    override fun complete(request: ModelRequest): Flow<ModelEvent> {
        seenRequests += request.copy(
            messages = request.messages.toList(),
            tools = request.tools.toList(),
        )
        val events = eventsByRound.getOrElse(round) { listOf(ModelEvent.Completed) }
        round += 1
        return flowOf(*events.toTypedArray())
    }
}

internal class LoopMcpProvider(
    private val resultJson: String = """{"ok":true}""",
    private val exposedTools: List<ToolDescriptor> = emptyList(),
    private val schemaJson: String = """{"type":"object","additionalProperties":true}""",
    private val schemaFailures: Set<String> = emptySet(),
) : McpClientProvider {
    override val serverId: String = TEST_MCP_SERVER_ID
    override val displayName: String = TEST_MCP_SERVER_ID
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    val calledTools = mutableListOf<String>()
    val calledArguments = mutableListOf<String>()
    val inspectedTools = mutableListOf<String>()
    val inspectCount: Int
        get() = inspectedTools.size

    override suspend fun listTools(): List<ToolDescriptor> =
        exposedTools.map { tool ->
            if (tool.name in schemaFailures) {
                tool.copy(inputSchemaJson = "")
            } else {
                tool.copy(inputSchemaJson = schemaJson)
            }
        }

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult {
        calledTools += toolName
        calledArguments += argumentsJson
        return ToolCallResult(success = true, resultJson = resultJson)
    }

    fun recordInspected(toolName: String) {
        inspectedTools += originalMcpToolName(toolName)
    }
}

internal fun mcpTool(name: String): ToolDescriptor =
    ToolDescriptor(name = name, description = name, inputSchemaJson = "{}")

internal fun qualifiedMcpTool(name: String): String =
    "mcp__${TEST_MCP_SERVER_ID}__$name"

internal fun schemaRead(toolName: String): ModelEvent.ToolCall =
    ModelEvent.ToolCall(
        id = "schema-$toolName",
        name = "read_file",
        argumentsJson = """{"path":"/mcp/tools/${qualifiedMcpTool(toolName)}/schema.json"}""",
    )

internal fun schemaThenBackendRounds(
    toolName: String = "set_temperature",
    argumentsJson: String = "{}",
    finish: Boolean = false,
): List<List<ModelEvent>> = buildList {
    add(listOf(schemaRead(toolName), ModelEvent.Completed))
    add(
        listOf(
            ModelEvent.ToolCall("backend-1", qualifiedMcpTool(toolName), argumentsJson),
            ModelEvent.Completed,
        ),
    )
    if (finish) {
        add(listOf(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""), ModelEvent.Completed))
    }
}

internal fun originalMcpToolName(qualifiedName: String): String =
    qualifiedName.substringAfter("mcp__${TEST_MCP_SERVER_ID}__", qualifiedName)

internal fun agentRequest(): AgentRequest = AgentRequest(
    requestId = "request-1",
    sessionId = "session-1",
    input = "finish with done",
)

internal const val TEST_MCP_SERVER_ID = "default"

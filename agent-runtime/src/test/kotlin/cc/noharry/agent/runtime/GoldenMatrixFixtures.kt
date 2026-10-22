@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ConfirmationChoice
import cc.noharry.agent.kernel.api.ConfirmationDecision
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.policy.PolicyAction
import cc.noharry.agent.policy.PolicyContext
import cc.noharry.agent.policy.PolicyDecision
import cc.noharry.agent.policy.PolicyRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal class GoldenScriptedModel(
    private val eventsByRound: List<List<ModelEvent>>,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    val emittedToolNames = mutableListOf<String>()
    val seenRequests = mutableListOf<ModelRequest>()
    private var round = 0

    override fun complete(request: ModelRequest): Flow<ModelEvent> {
        seenRequests += request.copy(
            messages = request.messages.toList(),
            tools = request.tools.toList(),
        )
        val events = eventsByRound.getOrElse(round) { listOf(ModelEvent.Completed) }
        emittedToolNames += events.filterIsInstance<ModelEvent.ToolCall>().map { it.name }
        round += 1
        return flowOf(*events.toTypedArray())
    }
}

internal class GoldenMcpProvider(
    private val schemas: Map<String, String> = emptyMap(),
    private val failingTools: Set<String> = emptySet(),
) : McpClientProvider {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override val serverId: String = TEST_MCP_SERVER_ID
    override val displayName: String = TEST_MCP_SERVER_ID
    val calledTools = mutableListOf<String>()
    val calledArguments = mutableListOf<String>()

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(
            mcpTool("set_temperature").withSchema(),
            mcpTool("read_cabin").withSchema(),
            mcpTool("seat_adjust").withSchema(),
        )

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult {
        calledTools += toolName
        calledArguments += argumentsJson
        return if (toolName in failingTools) {
            ToolCallResult(false, """{"error":"backend_failed"}""")
        } else {
            ToolCallResult(true, """{"tool":"$toolName","args":$argumentsJson}""")
        }
    }

    private fun ToolDescriptor.withSchema(): ToolDescriptor =
        copy(inputSchemaJson = schemas[name] ?: "{}")
}

internal fun goldenRequest(
    requestId: String = "request-1",
    sessionId: String = "session-1",
): AgentRequest = AgentRequest(
    requestId = requestId,
    sessionId = sessionId,
    input = "run golden case",
)

internal fun tool(name: String, argumentsJson: String): ModelEvent.ToolCall =
    ModelEvent.ToolCall(id = "$name-call", name = name, argumentsJson = argumentsJson)

internal fun finish(response: String = "done"): ModelEvent.ToolCall =
    tool("finish", """{"response":"$response"}""")

internal fun round(vararg events: ModelEvent): List<ModelEvent> =
    events.toList() + ModelEvent.Completed

internal fun approve(pendingId: String, requestId: String = "resume-1"): ConfirmationDecision =
    ConfirmationDecision(pendingId, requestId, ConfirmationChoice.APPROVE)

internal fun deny(pendingId: String, requestId: String = "resume-1"): ConfirmationDecision =
    ConfirmationDecision(pendingId, requestId, ConfirmationChoice.DENY)

internal fun confirmUnlessApproved(): PolicyRule = PolicyRule { action, context: PolicyContext ->
    val source = (action as? PolicyAction.Call)?.source
    if (source != "BACKEND" || context.approvedConfirmation) {
        PolicyDecision.Allow
    } else {
        PolicyDecision.RequireConfirmation("Needs approval", "Approve this action?")
    }
}

internal const val TEMPERATURE_SCHEMA =
    """{"type":"object","required":["degreesCelsius"],"properties":{"degreesCelsius":{"type":"number"}}}"""

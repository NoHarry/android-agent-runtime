@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.ExecEngine
import cc.noharry.agent.kernel.api.ExecRequest
import cc.noharry.agent.kernel.api.ExecResult
import cc.noharry.agent.kernel.api.ExecToolBridge
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.CompletionImpact
import cc.noharry.agent.kernel.api.RuntimeEffectKind
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import cc.noharry.agent.kernel.core.failed
import cc.noharry.agent.kernel.core.cancelledToolResult

class NativeExecToolTest {
    @Test
    fun execOutputDeltaIsEmittedFromHostEngine() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("exec-output-").toFile())
            .execEngine(outputOnlyExecEngine("script started"))
            .modelClient(execThenFinishModel())
            .build()

        val events = runtime.run(agentRequest()).toList()
        val output = events.filterIsInstance<AgentEvent.ExecOutputDelta>().single()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result

        assertEquals("request-1", output.runId)
        assertEquals("script started", output.text)
        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
    }

    @Test
    fun execNestedToolCallRoutesThroughPolicyDenyWithoutCallingBackend() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("exec-policy-").toFile())
            .toolProvider(backend)
            .policyConfigJson(BACKEND_DENY_POLICY)
            .execEngine(callBackendExecEngine())
            .modelClient(schemaExecThenFinishModel(seenRequests))
            .build()

        runtime.run(agentRequest()).toList()

        assertTrue(backend.calledTools.isEmpty())
        assertTrue(seenRequests.containsToolResult(RuntimeErrorCode.POLICY_DENIED.wireCode))
    }

    @Test
    fun execNestedToolCallConsumesLoopBudgetBeforeBackendExecution() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("exec-budget-").toFile())
            .toolProvider(backend)
            .execEngine(callBackendExecEngine())
            .modelClient(schemaExecThenFinishModel(seenRequests))
            .loopBudget(maxToolCallsPerRun = 2)
            .build()

        runtime.run(agentRequest()).toList()

        assertTrue(backend.calledTools.isEmpty())
        assertTrue(seenRequests.containsToolResult(RuntimeErrorCode.MODEL_LOOP_BUDGET_EXCEEDED.wireCode))
    }

    @Test
    fun execNestedBackendFailureBlocksMemoryCandidate() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("exec-memory-candidate-").toFile()
        val backend = SequencedExecMcpProvider()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspaceRoot)
            .toolProvider(backend)
            .execEngine(callBackendExecEngine())
            .modelClient(schemaDirectExecThenFinishModel())
            .build()

        runtime.run(agentRequest()).toList()

        val pendingContent = workspaceRoot.resolve("memory/lessons")
            .walkTopDown()
            .filter { it.name == "pending.json" }
            .joinToString("\n") { it.readText() }
        assertEquals(2, backend.callCount)
        assertFalse(pendingContent.contains("Observed capability set"))
    }

    @Test
    fun execNestedMissingToolBlocksMemoryCandidate() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("exec-missing-memory-candidate-").toFile()
        val backend = LoopMcpProvider(
            resultJson = """{"ok":true}""",
            exposedTools = listOf(mcpTool("set_temperature")),
        )
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspaceRoot)
            .toolProvider(backend)
            .execEngine(successThenMissingExecEngine())
            .modelClient(schemaExecThenFinishModel(mutableListOf()))
            .build()

        runtime.run(agentRequest()).toList()

        val pendingContent = workspaceRoot.resolve("memory/lessons")
            .walkTopDown()
            .filter { it.name == "pending.json" }
            .joinToString("\n") { it.readText() }
        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertFalse(pendingContent.contains("Observed capability set"))
    }

    @Test
    fun execEngineFailureAfterNestedSuccessBlocksMemoryCandidate() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("exec-engine-failure-candidate-").toFile()
        val backend = LoopMcpProvider(
            resultJson = """{"ok":true}""",
            exposedTools = listOf(mcpTool("set_temperature")),
        )
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspaceRoot)
            .toolProvider(backend)
            .execEngine(successThenFailedExecEngine())
            .modelClient(schemaExecThenFinishModel(mutableListOf()))
            .build()

        runtime.run(agentRequest()).toList()

        val pendingContent = workspaceRoot.resolve("memory/lessons")
            .walkTopDown()
            .filter { it.name == "pending.json" }
            .joinToString("\n") { it.readText() }
        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertFalse(pendingContent.contains("Observed capability set"))
    }

    @Test
    fun execNestedRequiredSuccessThenFinishIsSucceeded() = runBlocking {
        val result = runRequiredExecNested(
            firstResult = """{"ok":true,"committed":true}""",
            finishArguments = """{"response":"remembered"}""",
        )
        assertTrue(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertEquals(RuntimeCompletionOutcome.SUCCEEDED, result.completion.outcome)
        assertEquals(0, result.completion.requiredUnresolved)
    }

    @Test
    fun execNestedRequiredDeniedIsUnresolved() = runBlocking {
        val result = runRequiredExecNested(
            firstResult = """{"ok":true,"committed":true}""",
            finishArguments = """{"response":"done"}""",
            extraRound = listOf(
                ModelEvent.ToolCall("f2", "finish", """{"response":"still done"}"""),
                ModelEvent.Completed,
            ),
            policyConfigJson = REQUIRED_BACKEND_DENY_POLICY,
        )
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertTrue(result.completion.requiredUnresolved >= 1)
    }

    @Test
    fun execNestedRequiredFailureIsUnresolved() = runBlocking {
        val result = runRequiredExecNested(
            firstResult = """{"ok":false,"code":"NESTED"}""",
            finishArguments = """{"response":"done"}""",
            extraRound = listOf(
                ModelEvent.ToolCall("f2", "finish", """{"response":"still done"}"""),
                ModelEvent.Completed,
            ),
        )
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertTrue(result.completion.requiredUnresolved >= 1)
    }

    @Test
    fun execNestedRequiredCancelReceiptIsUnresolved() = runBlocking {
        val result = runRequiredExecNested(
            firstResult = cancelledToolResult("remember_fact"),
            finishArguments = """{"response":"done"}""",
            extraRound = listOf(
                ModelEvent.ToolCall("f2", "finish", """{"response":"still done"}"""),
                ModelEvent.Completed,
            ),
        )
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertTrue(result.completion.requiredUnresolved >= 1)
    }

    @Test
    fun execNestedRequiredNotCommittedIsUnresolved() = runBlocking {
        val result = runRequiredExecNested(
            firstResult = """{"ok":true,"committed":false}""",
            finishArguments = """{"response":"done"}""",
            extraRound = listOf(
                ModelEvent.ToolCall("f2", "finish", """{"response":"still done"}"""),
                ModelEvent.Completed,
            ),
        )
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertTrue(result.completion.requiredUnresolved >= 1)
    }

    @Test
    fun execNestedEquivalentRetrySatisfiesSameObligation() = runBlocking {
        val result = runRequiredExecNested(
            firstResult = """{"ok":false,"code":"TEMP"}""",
            finishArguments = """{"response":"done"}""",
            extraRound = listOf(
                ModelEvent.ToolCall("exec-2", "exec_js", """{"code":"tools.remember_fact()"}"""),
                ModelEvent.Completed,
            ),
            thirdRound = listOf(
                ModelEvent.ToolCall("f2", "finish", """{"response":"done"}"""),
                ModelEvent.Completed,
            ),
            secondResult = """{"ok":true,"committed":true}""",
        )
        assertEquals(RuntimeCompletionOutcome.SUCCEEDED, result.completion.outcome)
        assertEquals(0, result.completion.requiredUnresolved)
    }

    @Test
    fun execNestedDistinctArgumentsDoNotSatisfyEachOther() = runBlocking {
        val result = runRequiredExecNested(
            firstResult = """{"ok":false,"code":"TEMP"}""",
            finishArguments = """{"response":"done"}""",
            extraRound = listOf(
                ModelEvent.ToolCall("exec-2", "exec_js", """{"code":"tools.remember_other()"}"""),
                ModelEvent.Completed,
            ),
            thirdRound = listOf(
                ModelEvent.ToolCall("f2", "finish", """{"response":"done"}"""),
                ModelEvent.Completed,
            ),
            secondResult = """{"ok":true,"committed":true}""",
            secondArgumentsJson = """{"value":"other"}""",
        )
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertTrue(result.completion.requiredUnresolved >= 1)
    }

    private fun outputOnlyExecEngine(output: String): ExecEngine = object : ExecEngine {
        override val providerConcurrency: ProviderConcurrencyDeclaration =
            ProviderConcurrencyDeclaration.serialized()
        override suspend fun run(request: ExecRequest, bridge: ExecToolBridge): ExecResult {
            bridge.emitOutput(output)
            return ExecResult(ok = true, output = output)
        }
    }

    private fun callBackendExecEngine(): ExecEngine = object : ExecEngine {
        override val providerConcurrency: ProviderConcurrencyDeclaration =
            ProviderConcurrencyDeclaration.serialized()
        override suspend fun run(request: ExecRequest, bridge: ExecToolBridge): ExecResult {
            val result = bridge.callTool(qualifiedMcpTool("set_temperature"), """{"degreesCelsius":22}""")
            bridge.emitOutput(result)
            return ExecResult(ok = true, output = result)
        }
    }

    private fun execThenFinishModel(): FakeModelClient =
        FakeModelClient(
            eventsByRound = listOf(
                listOf(ModelEvent.ToolCall("exec-1", "exec_js", """{"code":"emit('script started')"}"""), ModelEvent.Completed),
                listOf(ModelEvent.ToolCall("finish-1", "finish", """{"response":"exec complete"}"""), ModelEvent.Completed),
            ),
        )

    private fun schemaExecThenFinishModel(seenRequests: MutableList<ModelRequest>): FakeModelClient =
        FakeModelClient(
            eventsByRound = listOf(
                listOf(
                    ModelEvent.ToolCall(
                        "schema-1",
                        "read_file",
                        """{"path":"/mcp/tools/${qualifiedMcpTool("set_temperature")}/schema.json"}""",
                    ),
                    ModelEvent.Completed,
                ),
                listOf(ModelEvent.ToolCall("exec-1", "exec_js", """{"code":"tools.set_temperature()"}"""), ModelEvent.Completed),
                listOf(ModelEvent.ToolCall("finish-1", "finish", """{"response":"exec complete"}"""), ModelEvent.Completed),
            ),
            seenRequests = seenRequests,
        )

    private fun schemaDirectExecThenFinishModel(): FakeModelClient =
        FakeModelClient(
            eventsByRound = listOf(
                listOf(
                    ModelEvent.ToolCall(
                        "schema-1",
                        "read_file",
                        """{"path":"/mcp/tools/${qualifiedMcpTool("set_temperature")}/schema.json"}""",
                    ),
                    ModelEvent.Completed,
                ),
                listOf(
                    ModelEvent.ToolCall(
                        "direct-1",
                        qualifiedMcpTool("set_temperature"),
                        """{"degreesCelsius":23}""",
                    ),
                    ModelEvent.Completed,
                ),
                listOf(ModelEvent.ToolCall("exec-1", "exec_js", """{"code":"tools.set_temperature()"}"""), ModelEvent.Completed),
                listOf(ModelEvent.ToolCall("finish-1", "finish", """{"response":"exec complete"}"""), ModelEvent.Completed),
            ),
        )

    private fun List<ModelRequest>.containsToolResult(token: String): Boolean =
        any { request -> request.messages.any { it.role == "tool" && it.content.contains(token) } }
}

private class SequencedExecMcpProvider : McpClientProvider {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override val serverId: String = TEST_MCP_SERVER_ID
    override val displayName: String = TEST_MCP_SERVER_ID
    var callCount: Int = 0

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(mcpTool("set_temperature"))

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult {
        callCount += 1
        val resultJson = if (callCount == 1) {
            """{"ok":true}"""
        } else {
            """{"jsonrpc":"2.0","result":{"content":[],"isError":true}}"""
        }
        return ToolCallResult(success = true, resultJson = resultJson)
    }

}

private fun successThenMissingExecEngine(): ExecEngine = object : ExecEngine {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override suspend fun run(request: ExecRequest, bridge: ExecToolBridge): ExecResult {
        val success = bridge.callTool(qualifiedMcpTool("set_temperature"), """{"degreesCelsius":22}""")
        val missing = bridge.callTool("missing_tool", "{}")
        return ExecResult(ok = true, output = "$success\n$missing")
    }
}

private fun successThenFailedExecEngine(): ExecEngine = object : ExecEngine {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override suspend fun run(request: ExecRequest, bridge: ExecToolBridge): ExecResult {
        bridge.callTool(qualifiedMcpTool("set_temperature"), """{"degreesCelsius":22}""")
        return ExecResult(ok = false, errorCode = "SCRIPT_FAILED", message = "script failed")
    }
}

private val BACKEND_DENY_POLICY = """
    {"rules":[{"match":{"tools":["mcp__default__set_*"],"sources":["BACKEND"]},"decision":"deny","userMessage":"policy blocked"}]}
""".trimIndent()

private val REQUIRED_BACKEND_DENY_POLICY = """
    {"rules":[{"match":{"tools":["mcp__default__remember_*"],"sources":["BACKEND"]},"decision":"deny","userMessage":"policy blocked"}]}
""".trimIndent()

private suspend fun runRequiredExecNested(
    firstResult: String,
    finishArguments: String,
    extraRound: List<ModelEvent>? = null,
    thirdRound: List<ModelEvent>? = null,
    secondResult: String = firstResult,
    secondArgumentsJson: String = """{"value":"home"}""",
    policyConfigJson: String? = null,
): RuntimeResult {
    val provider = RequiredExecMcpProvider(mutableListOf(firstResult, secondResult))
    val qualified = qualifiedMcpTool("remember_fact")
    val rounds = mutableListOf(
        listOf(
            ModelEvent.ToolCall("schema-1", "read_file", """{"path":"/mcp/tools/$qualified/schema.json"}"""),
            ModelEvent.Completed,
        ),
        listOf(ModelEvent.ToolCall("exec-1", "exec_js", """{"code":"tools.remember_fact()"}"""), ModelEvent.Completed),
        listOf(ModelEvent.ToolCall("finish-1", "finish", finishArguments), ModelEvent.Completed),
    )
    extraRound?.let(rounds::add)
    thirdRound?.let(rounds::add)
    val builder = AgentRuntimeBuilder.create()
        .workspaceRoot(Files.createTempDirectory("exec-obligation-").toFile())
        .toolProvider(provider)
        .execEngine(requiredNestedExecEngine(secondArgumentsJson))
        .modelClient(FakeModelClient(eventsByRound = rounds))
    policyConfigJson?.let(builder::policyConfigJson)
    return builder.build().run(agentRequest()).toList()
        .filterIsInstance<AgentEvent.ResultAvailable>().single().result
}

private fun requiredNestedExecEngine(secondArgumentsJson: String): ExecEngine = object : ExecEngine {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    private var calls = 0

    override suspend fun run(request: ExecRequest, bridge: ExecToolBridge): ExecResult {
        calls += 1
        val arguments = if (calls == 1) """{"value":"home"}""" else secondArgumentsJson
        val result = bridge.callTool(qualifiedMcpTool("remember_fact"), arguments)
        return ExecResult(ok = true, output = result)
    }
}

private class RequiredExecMcpProvider(
    private val results: MutableList<String>,
) : McpClientProvider {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override val serverId: String = TEST_MCP_SERVER_ID
    override val displayName: String = TEST_MCP_SERVER_ID

    override suspend fun listTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = "remember_fact",
            inputSchemaJson = """{"type":"object","required":["value"],"properties":{"value":{"type":"string"}}}""",
            effectKind = RuntimeEffectKind.MUTATING.name,
            completionImpact = CompletionImpact.REQUIRED_ON_CALL.name,
        ),
    )

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult {
        val payload = if (results.isEmpty()) """{"ok":true}""" else results.removeAt(0)
        return ToolCallResult(success = !payload.contains("\"ok\":false"), resultJson = payload)
    }
}

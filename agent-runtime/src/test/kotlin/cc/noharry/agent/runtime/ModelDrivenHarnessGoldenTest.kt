@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ConfirmationResumeResult
import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.ToolSource
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.state.encodeFileName
import cc.noharry.agent.runtime.workspace.MAX_WORKSPACE_WRITE_FILE_BYTES
import cc.noharry.agent.runtime.workspace.VirtualWorkspace
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDrivenHarnessGoldenTest {
    @Test
    fun tc101DirectBackendToolCallDoesNotUseExecutePlan() = runBlocking {
        val model = GoldenScriptedModel(
            listOf(
                round(modelDrivenSchemaRead("set_temperature")),
                round(tool(qualifiedMcpTool("set_temperature"), "{}")),
                round(finish("done")),
            ),
        )
        val backend = GoldenMcpProvider()
        val runtime = runtime(model, backend, workspace()).build()

        val events = runtime.run(goldenRequest()).toList()

        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertFalse(model.emittedToolNames.contains("execute_plan"))
        assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRuntimeResult("request-1")?.status)
    }

    @Test
    fun tc102WorkspaceWriteSucceedsAndBudgetFailureIsRecoverable() = runBlocking {
        val root = workspace()
        val model = GoldenScriptedModel(
            listOf(
                round(tool("write_file", """{"path":"/scratch/out.txt","content":"hello"}""")),
                round(finish("wrote")),
            ),
        )
        val runtime = runtime(model, GoldenMcpProvider(), root).build()

        runtime.run(goldenRequest()).toList()
        val overBudget = VirtualWorkspace(root).executeJson(
            "write_file",
            """{"path":"/scratch/large.txt","content":"${"x".repeat((MAX_WORKSPACE_WRITE_FILE_BYTES + 1).toInt())}"}""",
        )
        val workpad = requireNotNull((runtime as DefaultAgentRuntime).getRuntimeWorkpad("request-1"))

        assertFalse(File(root, "scratch/${encodeFileName("request-1")}/out.txt").exists())
        assertTrue(workpad.writeArtifacts.any { it.contains("path=/scratch/out.txt") })
        assertTrue(overBudget.contains(RuntimeErrorCode.WORKSPACE_WRITE_BUDGET_EXCEEDED.wireCode))
    }

    @Test
    fun tc103PreToolUseDenySkipsBackendAndFeedsModel() = runBlocking {
        val seen = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspace())
            .toolProvider(backend)
            .hook(denyTool("set_temperature"))
            .modelClient(backendThenFinishModel(seen))
            .build()

        runtime.run(goldenRequest()).toList()

        assertTrue(backend.calledTools.isEmpty())
        assertTrue(seen.any { request ->
            request.messages.any { it.content.contains(RuntimeErrorCode.HOOK_DENIED.wireCode) }
        })
    }

    @Test
    fun tc104RequireConfirmationApprovesSameToolUseOnce() = runBlocking {
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspace())
            .toolProvider(backend)
            .hook(confirmUntilApproved())
            .modelClient(backendThenFinishModel(mutableListOf()))
            .build()
        val pending = runtime.run(goldenRequest()).toList().filterIsInstance<AgentEvent.ConfirmationRequired>().single()

        assertEquals(ConfirmationResumeResult.Accepted, runtime.resumeConfirmation(approve(pending.pending.pendingId)))
        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRuntimeResult("request-1")?.status)
    }

    @Test
    fun tc105PolicyConfigCoversBackendGlobAndMemoryWrite() = runBlocking {
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val confirmRuntime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspace())
            .toolProvider(backend)
            .policyConfigJson(BACKEND_CONFIRM_POLICY)
            .modelClient(backendThenFinishModel(mutableListOf()))
            .build()
        val pending = confirmRuntime.run(goldenRequest()).toList()
            .filterIsInstance<AgentEvent.ConfirmationRequired>()
            .single()
        val root = workspace()
        val writeModel = GoldenScriptedModel(
            listOf(round(tool("write_file", """{"path":"/memory/note.txt","content":"ok"}""")), round(finish())),
        )

        assertEquals(ConfirmationResumeResult.Accepted, confirmRuntime.resumeConfirmation(approve(pending.pending.pendingId)))
        runtime(writeModel, GoldenMcpProvider(), root).policyConfigJson(MEMORY_WRITE_ALLOW_POLICY).build()
            .run(goldenRequest()).toList()
        assertEquals("ok", File(root, "memory/note.txt").readText())
    }

    @Test
    fun tc106InvalidPolicyConfigFailsFast() {
        val result = runCatching { AgentRuntimeBuilder.create().policyConfigJson("""{"rules":[{"decision":"unknown"}]}""").build() }

        assertTrue(requireNotNull(result.exceptionOrNull()).message.orEmpty().contains("POLICY_CONFIG_INVALID"))
    }

    @Test
    fun tc107BootstrapPromptReachesModelRequest() = runBlocking {
        val seen = mutableListOf<ModelRequest>()
        AgentRuntimeBuilder.create().systemPrompt("host append").modelClient(finishOnlySeenModel(seen)).build()
            .run(goldenRequest()).toList()

        val system = seen.single().system.orEmpty()
        assertTrue(system.contains("Model-driven harness protocol v1."))
        assertTrue(system.contains("write_file"))
        assertTrue(system.contains("host append"))
        assertFalse(system.contains("Actions must be performed through execute_plan"))
    }

    @Test
    fun tc108LoopBudgetWarningAndTimeoutUseSignedCodes() = runBlocking {
        val seen = mutableListOf<ModelRequest>()
        val budgetRuntime = AgentRuntimeBuilder.create()
            .toolProvider(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .modelClient(backendOnlySeenModel(seen))
            .loopBudget(maxModelRounds = 1)
            .build()
        var now = 0L
        val timeoutRuntime = AgentRuntimeBuilder.create().nowEpochMillis {
            val current = now
            now += 2
            current
        }.modelClient(finishOnlyModel())
            .loopBudget(maxWallClockMillis = 1)
            .build()

        budgetRuntime.run(goldenRequest()).toList()
        timeoutRuntime.run(goldenRequest("request-timeout")).toList()

        assertTrue(seen.single().messages.any { it.content.contains(""""budgetWarning"""") })
        assertEquals(RuntimeErrorCode.MODEL_LOOP_TIMEOUT.wireCode, timeoutRuntime.getRuntimeResult("request-timeout")?.errorCode)
    }

    @Test
    fun tc109ConfirmationResumeAfterRestartIsUnsupported() = runBlocking {
        val sameRuntimeSeen = mutableListOf<ModelRequest>()
        val sameRuntime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspace())
            .toolProvider(LoopMcpProvider(exposedTools = listOfTools()))
            .modelClient(backendThenFinishModel(sameRuntimeSeen))
            .build()
        sameRuntime.run(goldenRequest()).toList()
        val sameRuntimeMessages = sameRuntimeSeen.last().messages
        assertTrue(sameRuntimeMessages.any { it.role == "tool" && it.content.contains("ok") })

        val root = workspace()
        val first = AgentRuntimeBuilder.create().reliabilityStorageRoot(root)
            .workspaceRoot(root)
            .toolProvider(LoopMcpProvider(exposedTools = listOfTools()))
            .hook(confirmUntilApproved())
            .modelClient(backendThenFinishModel(mutableListOf()))
            .build()
        val pending = first.run(goldenRequest()).toList()
            .filterIsInstance<AgentEvent.ConfirmationRequired>()
            .single()
        val seen = mutableListOf<ModelRequest>()
        val restored = AgentRuntimeBuilder.create()
            .reliabilityStorageRoot(root)
            .modelClient(finishOnlySeenModel(seen))
            .build()
        val result = restored.resumeConfirmation(approve(pending.pending.pendingId))

        assertTrue(
            "process-restored confirmation must not continue the loop: $result",
            result is ConfirmationResumeResult.Rejected || result is ConfirmationResumeResult.NotFound,
        )
        assertTrue(seen.isEmpty())
    }

    @Test
    fun tc110DirectBackendToolUsesBackendSourceOnHook() = runBlocking {
        val seenSources = mutableListOf<ToolSource>()
        val runtime = runtime(
            GoldenScriptedModel(
                listOf(
                    round(schemaRead("set_temperature")),
                    round(planCall("set_temperature", "{}")),
                    round(finish()),
                ),
            ),
            GoldenMcpProvider(),
            workspace(),
        )
            .hook(sourceRecorder(seenSources))
            .build()

        runtime.run(goldenRequest()).toList()

        assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRuntimeResult("request-1")?.status)
        assertTrue(seenSources.contains(ToolSource.BACKEND))
    }

    private fun runtime(model: GoldenScriptedModel, backend: GoldenMcpProvider, root: File): AgentRuntimeBuilder =
        AgentRuntimeBuilder.create().workspaceRoot(root).toolProvider(backend).modelClient(model)

    private fun workspace(): File = Files.createTempDirectory("agent-model-driven-golden-").toFile().canonicalFile

    private fun planCall(toolName: String, argumentsJson: String): ModelEvent.ToolCall =
        tool(qualifiedMcpTool(toolName), argumentsJson)

    private fun sourceRecorder(sources: MutableList<ToolSource>): RuntimeHook = object : RuntimeHook {
        override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
            sources += call.source
            return HookDecision.Allow()
        }
    }
}

private fun finishOnlySeenModel(seen: MutableList<ModelRequest>): FakeModelClient =
    FakeModelClient(
        eventsByRound = listOf(listOf(ModelEvent.ToolCall("tool-finish", "finish", """{"response":"done"}"""), ModelEvent.Completed)),
        seenRequests = seen,
    )

private fun backendOnlySeenModel(seen: MutableList<ModelRequest>): FakeModelClient =
    FakeModelClient(
        eventsByRound = listOf(
            listOf(ModelEvent.ToolCall("tool-backend", qualifiedMcpTool("set_temperature"), "{}"), ModelEvent.Completed),
        ),
        seenRequests = seen,
    )

private fun listOfTools() = listOf(mcpTool("set_temperature"))

private fun modelDrivenSchemaRead(toolName: String): ModelEvent.ToolCall =
    tool("read_file", """{"path":"/mcp/tools/${qualifiedMcpTool(toolName)}/schema.json"}""")

private val BACKEND_CONFIRM_POLICY =
    """{"rules":[{"match":{"tools":["${qualifiedMcpTool("set_temperature")}"],"sources":["BACKEND"]},"decision":"require_confirmation","prompt":"Approve backend?"}]}"""

private const val MEMORY_WRITE_ALLOW_POLICY =
    """{"rules":[{"match":{"tools":["write_file"],"sources":["NATIVE_WRITE"],"pathPrefix":"/memory/"},"decision":"allow"}]}"""

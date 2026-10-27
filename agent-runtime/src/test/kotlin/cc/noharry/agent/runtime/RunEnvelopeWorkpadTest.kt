@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeBlocker
import cc.noharry.agent.kernel.api.reliability.RuntimeHandoff
import cc.noharry.agent.kernel.api.reliability.RuntimeRunPhase
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.api.reliability.SuggestedNextAction
import cc.noharry.agent.kernel.core.loop.BackendToolFailureSummary
import cc.noharry.agent.kernel.core.loop.NativeLoopSummary
import cc.noharry.agent.kernel.core.loop.RuntimeMemoryMatchSummary
import cc.noharry.agent.kernel.core.state.MAX_WORKPAD_ATTEMPTED_TOOLS
import cc.noharry.agent.kernel.core.state.MAX_WORKPAD_BLOCKERS
import cc.noharry.agent.kernel.core.state.MAX_WORKPAD_EVIDENCE_REFS
import cc.noharry.agent.kernel.core.state.MAX_WORKPAD_HANDOFF_ITEMS
import cc.noharry.agent.kernel.core.state.MAX_WORKPAD_PLAN_SUMMARIES
import cc.noharry.agent.kernel.core.state.MAX_WORKPAD_READ_PATHS
import cc.noharry.agent.kernel.core.state.MAX_WORKPAD_TEXT_CHARS
import cc.noharry.agent.kernel.core.state.MAX_WORKPAD_TOOL_REGISTRY_ITEMS
import cc.noharry.agent.kernel.core.state.MAX_WORKPAD_WRITE_ARTIFACTS
import cc.noharry.agent.kernel.core.state.RunArtifactsController
import cc.noharry.agent.kernel.core.state.WORKPAD_TRUNCATION_SUFFIX
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunEnvelopeWorkpadTest {
    @Test
    fun terminalResultUpdatesRunEnvelopeBeforeResultIsQueryable() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                EnvelopeModel(
                    ModelEvent.ToolCall(
                        id = "tool-1",
                        name = "finish",
                        argumentsJson = """{"response":"done"}""",
                    ),
                    ModelEvent.Completed,
                ),
            )
            .build() as DefaultAgentRuntime

        runtime.run(agentRequest()).toList()

        val envelope = runtime.getRunEnvelope("request-1")
        val workpad = runtime.getRuntimeWorkpad("request-1")
        val traceNames = runtime.getRuntimeTraceEvents("request-1").map { it.eventName }

        assertEquals(RuntimeRunStatus.COMPLETED, envelope?.status)
        assertEquals(RuntimeRunPhase.TERMINAL, envelope?.phase)
        assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRuntimeResult("request-1")?.status)
        assertTrue(workpad?.completionBar?.single { it.id == "terminal-result" }?.done == true)
        assertTrue(traceNames.contains("RUN_CREATED"))
        assertTrue(traceNames.contains("RUN_STATUS_CHANGED"))
        assertTrue(traceNames.contains("WORKPAD_UPDATED"))
    }

    @Test
    fun workpadRecordsToolSummariesWithoutRawPlanArguments() = runBlocking {
        val workspace = Files.createTempDirectory("agent-runtime-envelope-").toFile()
        File(workspace, "note.txt").writeText("workspace content")
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspace)
            .toolProvider(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .modelClient(
                EnvelopeModel(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-1",
                                name = "read_file",
                                argumentsJson = """{"path":"/note.txt"}""",
                            ),
                            schemaRead("set_temperature"),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-2",
                                name = qualifiedMcpTool("set_temperature"),
                                argumentsJson = """{"degreesCelsius":22}""",
                            ),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-3",
                                name = "finish",
                                argumentsJson = """{"response":"done"}""",
                            ),
                            ModelEvent.Completed,
                        ),
                    ),
                ),
            )
            .build() as DefaultAgentRuntime

        runtime.run(agentRequest()).toList()
        val workpad = requireNotNull(runtime.getRuntimeWorkpad("request-1"))

        assertTrue(workpad.readPaths.contains("/note.txt"))
        assertTrue(workpad.attemptedTools.contains("read_file"))
        assertTrue(workpad.attemptedTools.contains(qualifiedMcpTool("set_temperature")))
        assertTrue(workpad.attemptedTools.contains("finish"))
        assertTrue(workpad.planSummaries.isEmpty())
        assertTrue(workpad.attemptedTools.none { it.contains("degreesCelsius") })
    }

    @Test
    fun confirmationKeepsEnvelopeNonTerminalAndRecordsWorkpadBlocker() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("envelope-confirm-").toFile())
            .toolProvider(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .hook(confirmUntilApproved())
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(schemaRead("set_temperature"), ModelEvent.Completed),
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-1",
                                name = qualifiedMcpTool("set_temperature"),
                                argumentsJson = "{}",
                            ),
                            ModelEvent.Completed,
                        ),
                    ),
                ),
            )
            .build() as DefaultAgentRuntime

        val events = runtime.run(agentRequest()).toList()
        val envelope = runtime.getRunEnvelope("request-1")
        val workpad = requireNotNull(runtime.getRuntimeWorkpad("request-1"))

        assertEquals(RuntimeRunStatus.WAITING_CONFIRMATION, envelope?.status)
        assertEquals(RuntimeRunPhase.WAITING_CONFIRMATION, envelope?.phase)
        assertEquals(null, runtime.getRuntimeResult("request-1"))
        assertTrue(events.filterIsInstance<AgentEvent.Finished>().isEmpty())
        assertTrue(workpad.attemptedTools.contains(qualifiedMcpTool("set_temperature")))
        assertTrue(events.any { it is AgentEvent.ConfirmationRequired })
        assertTrue(workpad.completionBar.single { it.id == "terminal-result" }.done.not())
    }

    @Test
    fun workpadTrimsGrowthWithoutDroppingNewestContext() {
        var now = 1L
        val controller = RunArtifactsController(nowEpochMillis = { now++ })
        controller.createWorkpad("run-1", now)

        controller.recordNativeLoopSummary(
            "run-1",
            NativeLoopSummary(
                readPaths = indexedStrings("/path-", MAX_WORKPAD_READ_PATHS + EXTRA_ITEMS),
                writeArtifacts = indexedStrings("write-", MAX_WORKPAD_WRITE_ARTIFACTS + EXTRA_ITEMS),
                attemptedTools = indexedStrings("tool-", MAX_WORKPAD_ATTEMPTED_TOOLS + EXTRA_ITEMS),
                toolRegistryWarnings = indexedStrings("warning-", MAX_WORKPAD_TOOL_REGISTRY_ITEMS + EXTRA_ITEMS),
                toolRegistryFailures = indexedStrings("failure-", MAX_WORKPAD_TOOL_REGISTRY_ITEMS + EXTRA_ITEMS),
                planSummaries = indexedStrings("plan-", MAX_WORKPAD_PLAN_SUMMARIES + EXTRA_ITEMS),
            ),
        )
        repeat(MAX_WORKPAD_BLOCKERS + EXTRA_ITEMS) { index ->
            controller.recordBlocker(workpadBlocker(index))
        }

        val workpad = requireNotNull(controller.workpad("run-1"))
        val firstBlocker = workpad.blockers.first()

        assertEquals(MAX_WORKPAD_READ_PATHS, workpad.readPaths.size)
        assertTrue(workpad.readPaths.first().startsWith("$TRIMMED_PREFIX$EXTRA_ITEMS"))
        assertTrue(workpad.readPaths.first().endsWith(WORKPAD_TRUNCATION_SUFFIX))
        assertEquals(MAX_WORKPAD_WRITE_ARTIFACTS, workpad.writeArtifacts.size)
        assertEquals(MAX_WORKPAD_ATTEMPTED_TOOLS, workpad.attemptedTools.size)
        assertEquals("tool-$EXTRA_ITEMS", workpad.attemptedTools.first())
        assertEquals(MAX_WORKPAD_TOOL_REGISTRY_ITEMS, workpad.toolRegistryWarnings.size)
        assertEquals(MAX_WORKPAD_TOOL_REGISTRY_ITEMS, workpad.toolRegistryFailures.size)
        assertEquals(MAX_WORKPAD_PLAN_SUMMARIES, workpad.planSummaries.size)
        assertEquals(MAX_WORKPAD_BLOCKERS, workpad.blockers.size)
        assertEquals("TEST_$EXTRA_ITEMS", firstBlocker.code)
        assertTrue(firstBlocker.message.endsWith(WORKPAD_TRUNCATION_SUFFIX))
        assertTrue(firstBlocker.message.length <= MAX_WORKPAD_TEXT_CHARS)
        assertEquals(MAX_WORKPAD_HANDOFF_ITEMS, firstBlocker.handoff?.nextActions?.size)
        assertEquals(MAX_WORKPAD_EVIDENCE_REFS, firstBlocker.handoff?.evidenceRefs?.size)
    }

    @Test
    fun loopSummaryRecordsMemoryAndBackendDecisionEvidence() {
        val controller = RunArtifactsController(nowEpochMillis = { 1L })
        controller.createWorkpad("run-memory", 1L)

        controller.recordNativeLoopSummary(
            "run-memory",
            NativeLoopSummary(
                modelRoundCount = 2,
                backendToolFailures = listOf(BackendToolFailureSummary("mcp__default__ac", "MCP_TOOL_IS_ERROR")),
                memoryRetrievalEvaluated = true,
                retrievedMemoryMatches = listOf(RuntimeMemoryMatchSummary("lesson-1", 0.8f, listOf("hvac"))),
                memoryCapabilityQueries = listOf("ac set temperature"),
                memoryHintTools = listOf("mcp__default__command_ac_SetTemperature"),
            ),
        )

        val traceNames = controller.traceEvents("run-memory").map { it.eventName }
        assertTrue(traceNames.contains("MODEL_ROUND_SUMMARY"))
        assertTrue(traceNames.contains("MEMORY_LESSON_RETRIEVED"))
        assertTrue(traceNames.contains("MEMORY_TOOL_PREACTIVATED"))
        assertTrue(traceNames.contains("BACKEND_TOOL_FAILURE"))
    }

    private fun agentRequest(): AgentRequest = AgentRequest(
        requestId = "request-1",
        sessionId = "session-1",
        input = "run",
    )
}

private fun indexedStrings(prefix: String, count: Int): List<String> =
    (0 until count).map { index ->
        if (prefix == TRIMMED_PREFIX) {
            "$prefix$index${LONG_WORKPAD_TEXT}"
        } else {
            "$prefix$index"
        }
    }

private fun workpadBlocker(index: Int): RuntimeBlocker =
    RuntimeBlocker(
        runId = "run-1",
        code = "TEST_$index",
        message = "message-$index$LONG_WORKPAD_TEXT",
        suggestedNextAction = SuggestedNextAction.INSPECT_BLOCKER,
        handoff = RuntimeHandoff(
            runId = "run-1",
            sessionId = "session-1",
            summary = "summary-$index$LONG_WORKPAD_TEXT",
            done = indexedStrings("done-", MAX_WORKPAD_HANDOFF_ITEMS + EXTRA_ITEMS),
            nextActions = indexedStrings("next-", MAX_WORKPAD_HANDOFF_ITEMS + EXTRA_ITEMS),
            evidenceRefs = indexedStrings("evidence-", MAX_WORKPAD_EVIDENCE_REFS + EXTRA_ITEMS),
        ),
    )

private const val EXTRA_ITEMS = 3
private const val TRIMMED_PREFIX = "/path-"
private val LONG_WORKPAD_TEXT = "x".repeat(MAX_WORKPAD_TEXT_CHARS + 80)

private class EnvelopeModel(
    private val eventsByRound: List<List<ModelEvent>>,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    constructor(vararg events: ModelEvent) : this(listOf(events.toList()))

    private var round = 0

    override fun complete(request: ModelRequest): Flow<ModelEvent> {
        val events = eventsByRound.getOrElse(round) { listOf(ModelEvent.Completed) }
        round += 1
        return flowOf(*events.toTypedArray())
    }
}

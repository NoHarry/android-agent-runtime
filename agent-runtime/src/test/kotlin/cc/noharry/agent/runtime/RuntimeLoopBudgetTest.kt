@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.ConfirmationResumeResult
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.context.MAX_HOST_SYSTEM_PROMPT_BYTES
import cc.noharry.agent.kernel.core.loop.DEFAULT_MAX_TOOL_CALLS_PER_RUN
import cc.noharry.agent.kernel.core.loop.LoopBudgetConfig
import cc.noharry.agent.kernel.core.loop.LoopBudgetTracker
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLoopBudgetTest {
    @Test
    fun builderRejectsHostSystemPromptOverSixteenKib() {
        val result = runCatching {
            AgentRuntimeBuilder.create().systemPrompt("x".repeat(MAX_HOST_SYSTEM_PROMPT_BYTES + 1))
        }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun modelRoundBudgetFailsWithStructuredWarningOnLastRound() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .toolProvider(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .modelClient(backendOnlyModel(seenRequests))
            .loopBudget(maxModelRounds = 1)
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events
            .filterIsInstance<AgentEvent.ResultAvailable>()
            .single()
            .result

        assertEquals(RuntimeRunStatus.FAILED, result.status)
        assertEquals(RuntimeErrorCode.MODEL_LOOP_BUDGET_EXCEEDED.wireCode, result.errorCode)
        val warning = events.filterIsInstance<AgentEvent.BudgetWarning>().single()
        assertEquals("request-1", warning.runId)
        assertEquals(0, warning.roundsRemaining)
        // Only the round cap was set; tool calls keep the default, which is no cap.
        assertEquals(DEFAULT_MAX_TOOL_CALLS_PER_RUN, warning.toolCallsRemaining)
        val messages = seenRequests.single().messages.joinToString("\n") { it.content }
        assertTrue(messages.contains(""""budgetWarning""""))
        assertTrue(messages.contains(""""roundsRemaining":0"""))
    }

    @Test
    fun toolCallBudgetFailsWhenModelExceedsConfiguredLimit() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    ModelEvent.ToolCall("tool-1", "unknown_one", "{}"),
                    ModelEvent.ToolCall("tool-2", "unknown_two", "{}"),
                    ModelEvent.Completed,
                ),
            )
            .loopBudget(maxToolCallsPerRun = 1)
            .build()

        val result = runtime.run(agentRequest()).toList()
            .filterIsInstance<AgentEvent.ResultAvailable>()
            .single()
            .result

        assertEquals(RuntimeRunStatus.FAILED, result.status)
        assertEquals(RuntimeErrorCode.MODEL_LOOP_BUDGET_EXCEEDED.wireCode, result.errorCode)
    }

    @Test
    fun inclusiveInputUsageIsRecordedWithoutOutputOrReasoningForCompaction() {
        val tracker = LoopBudgetTracker(LoopBudgetConfig(), nowEpochMillis = { 100L })

        tracker.recordUsage(ModelEvent.UsageDelta(inputTokens = 70, outputTokens = 20, reasoningTokens = 5))

        assertEquals(70, tracker.latestUsageTokensForCompaction())
        assertEquals(70, tracker.snapshot().latestUsageTokens)
    }

    @Test
    fun perCallInputAccumulatesDeltasAndResetsOnRetryOrNewCall() {
        val tracker = LoopBudgetTracker(LoopBudgetConfig(), nowEpochMillis = { 100L })
        tracker.beginModelCall()
        tracker.recordUsage(ModelEvent.UsageDelta(inputTokens = 700, cacheReadTokens = 600))
        tracker.recordUsage(ModelEvent.UsageDelta(inputTokens = 100, outputTokens = 8000))
        tracker.recordUsage(ModelEvent.UsageDelta(outputTokens = 9000))
        assertEquals(800, tracker.latestUsageTokensForCompaction())
        tracker.beginModelCall()
        assertEquals(null, tracker.latestUsageTokensForCompaction())
        tracker.recordUsage(ModelEvent.UsageDelta(inputTokens = 20))
        assertEquals(20, tracker.latestUsageTokensForCompaction())
    }

    @Test
    fun configuredContextWindowUsesCeilingOfEightyPercentInActualLoop() = runBlocking {
        for ((window, input, expected) in listOf(Triple(1000L, 799, 0), Triple(1000L, 800, 1),
            Triple(1001L, 800, 0), Triple(1001L, 801, 1))) {
            val root = workspace()
            root.resolve("note.txt").writeText("context".repeat(100))
            val model = FakeModelClient(eventsByRound = listOf(
                listOf(ModelEvent.UsageDelta(inputTokens = input), ModelEvent.ToolCall("r1", "read_file", """{"path":"/note.txt"}"""), ModelEvent.Completed),
                listOf(ModelEvent.UsageDelta(inputTokens = input), ModelEvent.ToolCall("r2", "read_file", """{"path":"/note.txt"}"""), ModelEvent.Completed),
                listOf(ModelEvent.ToolCall("done", "finish", """{"response":"done"}"""), ModelEvent.Completed),
            ))
            val runtime = AgentRuntimeBuilder.create().workspaceRoot(root).modelClient(model).contextWindow(window).build()
            val events = runtime.run(agentRequest()).toList()
            assertEquals("window=$window input=$input", expected, events.filterIsInstance<AgentEvent.CompactionApplied>().size)
            assertEquals(RuntimeRunStatus.COMPLETED, events.filterIsInstance<AgentEvent.ResultAvailable>().single().result.status)
        }
    }

    @Test
    fun wallClockBudgetFailsWithTimeoutCode() = runBlocking {
        var now = 0L
        val runtime = AgentRuntimeBuilder.create()
            .nowEpochMillis {
                val current = now
                now += 2
                current
            }
            .modelClient(finishOnlyModel())
            .loopBudget(maxWallClockMillis = 1)
            .build()

        val result = runtime.run(agentRequest()).toList()
            .filterIsInstance<AgentEvent.ResultAvailable>()
            .single()
            .result

        assertEquals(RuntimeRunStatus.FAILED, result.status)
        assertEquals(RuntimeErrorCode.MODEL_LOOP_TIMEOUT.wireCode, result.errorCode)
    }

    @Test
    fun confirmedToolResumeContinuesWithSavedLoopHistory() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .toolProvider(backend)
            .workspaceRoot(workspace())
            .hook(confirmUntilApproved())
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        val pending = runtime.run(agentRequest()).toList()
            .filterIsInstance<AgentEvent.ConfirmationRequired>()
            .single()
            .pending
        val accepted = runtime.resumeConfirmation(approve(pending.pendingId))
        val resumedMessages = seenRequests.last().messages
        val assistantIndex = resumedMessages.indexOfFirst { it.toolCallId == "tool-1" }

        assertEquals(ConfirmationResumeResult.Accepted, accepted)
        assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRuntimeResult("request-1")?.status)
        assertTrue(assistantIndex >= 0)
        assertEquals("assistant", resumedMessages[assistantIndex].role)
        assertEquals("tool", resumedMessages[assistantIndex + 1].role)
        assertTrue(resumedMessages[assistantIndex + 1].content.contains("ok"))
    }
}

private fun backendOnlyModel(seenRequests: MutableList<ModelRequest>): FakeModelClient =
    FakeModelClient(
        eventsByRound = listOf(
            listOf(ModelEvent.ToolCall("tool-1", "set_temperature", "{}"), ModelEvent.Completed),
        ),
        seenRequests = seenRequests,
    )

private fun workspace() =
    Files.createTempDirectory("agent-runtime-loop-budget-").toFile().canonicalFile

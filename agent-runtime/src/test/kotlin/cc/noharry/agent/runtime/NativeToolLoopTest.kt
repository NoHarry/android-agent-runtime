package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelClientException
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRef
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.context.*
import cc.noharry.agent.kernel.core.loop.*
import cc.noharry.agent.kernel.core.memory.*
import cc.noharry.agent.kernel.core.plugin.*
import cc.noharry.agent.kernel.core.state.*
import cc.noharry.agent.kernel.core.tools.*
import cc.noharry.agent.kernel.core.trace.*
import cc.noharry.agent.runtime.run.TodoStore
import cc.noharry.agent.vfs.VfsFileSystem
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeToolLoopTest {
    @Test
    fun runUsesFinishToolResponseFromModelClient() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    ModelEvent.ToolCall(
                        id = "tool-1",
                        name = "finish",
                        argumentsJson = """{"response":"done"}""",
                    ),
                    ModelEvent.Completed,
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val resultEvent = events.filterIsInstance<AgentEvent.ResultAvailable>().single()
        val result = resultEvent.result
        val responseText = events.filterIsInstance<AgentEvent.TextDelta>().single()

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals("done", result.response)
        assertEquals("done", responseText.text)
        assertTrue(events.indexOf(responseText) < events.indexOf(resultEvent))
        assertTrue(events.last() is AgentEvent.Finished)
    }

    @Test
    fun runEmitsModelStatusDeltasBeforeNativeToolEvents() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    ModelEvent.ThinkingDelta("thinking secret=sk-toolsecret123"),
                    ModelEvent.TextDelta("visible status"),
                    ModelEvent.ToolCall(
                        id = "tool-1",
                        name = "finish",
                        argumentsJson = """{"response":"done"}""",
                    ),
                    ModelEvent.Completed,
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val thinking = events.filterIsInstance<AgentEvent.ThinkingDelta>().single()
        val text = events.filterIsInstance<AgentEvent.TextDelta>().first()
        val toolStarted = events.filterIsInstance<AgentEvent.NativeToolCallStarted>().single()

        assertTrue(thinking.text.contains("REDACTED"))
        assertFalse(thinking.text.contains("sk-toolsecret123"))
        assertEquals("visible status", text.text)
        assertEquals("tool-1", toolStarted.toolCallId)
        assertTrue(events.indexOf(thinking) < events.indexOf(text))
        assertTrue(events.indexOf(text) < events.indexOf(toolStarted))
    }

    @Test
    fun runDoesNotDuplicateFinishResponseWhenSameTextAlreadyStreamed() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    ModelEvent.TextDelta("done"),
                    ModelEvent.ToolCall(
                        id = "tool-1",
                        name = "finish",
                        argumentsJson = """{"response":"done"}""",
                    ),
                    ModelEvent.Completed,
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val texts = events.filterIsInstance<AgentEvent.TextDelta>().map { it.text }
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result

        assertEquals(listOf("done"), texts)
        assertEquals("done", result.response)
    }

    @Test
    fun finishPreservesUnverifiedTodoStateWithoutChangingFinishWireJson() = runBlocking {
        val workspace = Files.createTempDirectory("finish-preserves-todo-").toFile()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspace)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall(
                                "todo-1",
                                "update_todo",
                                """
                                    {
                                      "todos": [
                                        {"content":"Find parking","status":"in_progress"},
                                        {"content":"Start navigation","status":"pending"}
                                      ]
                                    }
                                """.trimIndent(),
                            ),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""),
                            ModelEvent.Completed,
                        ),
                    ),
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val state = TodoStore(workspace.resolve("memory")).load("session-1")
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        val text = events.filterIsInstance<AgentEvent.TextDelta>().single()
        val finishEvent = events.filterIsInstance<AgentEvent.NativeToolCallFinished>()
            .single { it.toolName == "finish" }
        val todoFinishEvents = events.filterIsInstance<AgentEvent.NativeToolCallFinished>()
            .filter { it.toolName == "update_todo" }

        assertNotNull(state)
        assertEquals(listOf(TodoStatus.in_progress, TodoStatus.pending), state?.items?.map { it.status })
        assertEquals("done", result.response)
        assertEquals("done", text.text)
        assertEquals("""{"redacted":true}""", finishEvent.result)
        assertEquals(1, todoFinishEvents.size)
        assertTodoFinishedCarriesNormalizedPayload(todoFinishEvents.single(), "Find parking")
    }

    @Test
    fun explicitSuccessFinishClosesTheRunsChecklistWithoutAnotherRound() = runBlocking {
        for ((outcome, expected) in listOf("success" to TodoStatus.completed, "partial" to TodoStatus.pending)) {
            val workspace = Files.createTempDirectory("finish-closes-todo-").toFile()
            val model = FakeModelClient(
                eventsByRound = listOf(
                    listOf(
                        ModelEvent.ToolCall("todo-1", "update_todo", """{"todos":[{"content":"Find parking","status":"completed"},{"content":"Start navigation","status":"pending"}]}"""),
                        ModelEvent.Completed,
                    ),
                    listOf(
                        ModelEvent.ToolCall("finish-1", "finish", """{"response":"done","outcome":"$outcome"}"""),
                        ModelEvent.Completed,
                    ),
                ),
            )
            val runtime = AgentRuntimeBuilder.create().workspaceRoot(workspace).modelClient(model).build()
            runtime.run(agentRequest()).toList()
            val state = TodoStore(workspace.resolve("memory")).load("session-1")
            assertEquals(outcome, listOf(TodoStatus.completed, expected), state?.items?.map { it.status })
            assertEquals(outcome, listOf("Find parking", "Start navigation"), state?.items?.map { it.content })
        }
    }

    @Test
    fun runRetriesPureNarrationFinishResponseOnce() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall(
                                "finish-1",
                                "finish",
                                """{"response":"All tasks completed! Let me update the todo and provide the final response."}""",
                            ),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall("finish-2", "finish", """{"response":"用户可见完整答复"}"""),
                            ModelEvent.Completed,
                        ),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        val retryToolResult = seenRequests[1].messages.single { it.role == "tool" && it.toolName == "finish" }

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals("用户可见完整答复", result.response)
        assertEquals(2, seenRequests.size)
        assertTrue(retryToolResult.content.contains(INVALID_FINISH_RESPONSE_MESSAGE))
    }

    @Test
    fun runRejectsSecondPureNarrationFinishResponseAfterRetryIsUsed() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val secondNarration = "Now let me update the todo list and finish with a comprehensive response."
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall(
                                "finish-1",
                                "finish",
                                """{"response":"All operations are complete. Let me now update the todo list and finish with a complete summary."}""",
                            ),
                            ModelEvent.Completed,
                        ),
                        listOf(ModelEvent.ToolCall("finish-2", "finish", """{"response":"$secondNarration"}"""), ModelEvent.Completed),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        val finalEvents = runtime.run(agentRequest()).toList()
        val result = finalEvents
            .filterIsInstance<AgentEvent.ResultAvailable>()
            .single()
            .result

        assertEquals(RuntimeRunStatus.FAILED, result.status)
        assertEquals(INVALID_FINISH_RESPONSE_CODE, result.errorCode)
        assertEquals(INVALID_FINISH_RESPONSE_MESSAGE, result.response)
        assertEquals(2, seenRequests.size)
        assertEquals(2, finalEvents.filterIsInstance<AgentEvent.NativeToolCallFinished>().count { it.toolName == "finish" })
    }

    @Test
    fun resumeRejectsPureNarrationFinishWhenRetryAlreadyExistsInHistory() = runBlocking {
        val historyStore = InMemoryLoopHistoryStore()
        val runId = "run-invalid-finish-resume"
        val secondNarration = "Now let me update the todo list and finish with a comprehensive response."
        historyStore.seedInvalidFinishRetry(runId)
        val seenRequests = mutableListOf<ModelRequest>()
        val loop = nativeLoopWithFinishResponse(
            response = secondNarration,
            seenRequests = seenRequests,
            historyStore = historyStore,
        )

        val outcome = loop.resumeUserInput(
            runId = runId,
            sessionId = "session-1",
            requestId = "request-1",
            answer = "continue",
            scene = null,
            modelRef = null,
        ) as NativeLoopOutcome.Terminal

        assertEquals(RuntimeRunStatus.FAILED, outcome.result.status)
        assertEquals(INVALID_FINISH_RESPONSE_CODE, outcome.result.errorCode)
        assertEquals(INVALID_FINISH_RESPONSE_MESSAGE, outcome.result.response)
        assertEquals(1, seenRequests.size)
    }

    @Test
    fun invalidFinishResponseDoesNotCompleteTodos() = runBlocking {
        val workspace = Files.createTempDirectory("invalid-finish-keeps-todo-").toFile()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspace)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall(
                                "todo-1",
                                "update_todo",
                                """{"todos":[{"content":"Find parking","status":"in_progress"}]}""",
                            ),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall(
                                "finish-1",
                                "finish",
                                """{"response":"All steps completed successfully! Let me update the todo list and provide the final summary."}""",
                            ),
                            ModelEvent.Completed,
                        ),
                        listOf(ModelEvent.Completed),
                    ),
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        val state = TodoStore(workspace.resolve("memory")).load("session-1")

        assertEquals(RuntimeRunStatus.FAILED, result.status)
        assertEquals(listOf(TodoStatus.in_progress), state?.items?.map { it.status })
    }

    @Test
    fun aTextAnswerWithoutFinishCompletesTheRun() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(listOf(ModelEvent.TextDelta("空调已经调到22度了。"), ModelEvent.Completed)),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals("空调已经调到22度了。", result.response)
        assertEquals(1, seenRequests.size)
        assertTrue(events.filterIsInstance<AgentEvent.EmptyCompletionObserved>().isEmpty())
    }

    @Test
    fun echoedJsonIsNotTakenAsTheAnswer() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(ModelEvent.TextDelta("""{"ok":true,"matches":[]}"""), ModelEvent.Completed),
                        listOf(ModelEvent.TextDelta("没有找到能读后视镜状态的功能。"), ModelEvent.Completed),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        val result = runtime.run(agentRequest()).toList().filterIsInstance<AgentEvent.ResultAvailable>().single().result

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals("没有找到能读后视镜状态的功能。", result.response)
        assertTrue(seenRequests[1].messages.any { it.role == "user" && it.content.contains(INVALID_FINISH_RESPONSE_CODE) })
    }

    @Test
    fun runContinuesOnceWhenModelCompletesWithoutFinishThenSucceeds() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(ModelEvent.Completed),
                        listOf(
                            ModelEvent.ToolCall("finish-1", "finish", """{"response":"done after continue"}"""),
                            ModelEvent.Completed,
                        ),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        val continueNudge = seenRequests[1].messages.single {
            it.role == "user" && it.content.contains(EMPTY_COMPLETION_CONTINUE_MARKER)
        }

        val probes = events.filterIsInstance<AgentEvent.EmptyCompletionObserved>()
        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals("done after continue", result.response)
        assertEquals(2, seenRequests.size)
        assertTrue(continueNudge.content.contains(EMPTY_COMPLETION_CONTINUE_MESSAGE))
        assertEquals(1, probes.size)
        assertEquals("EMPTY_NO_OUTPUT", probes.single().kind)
        assertTrue(probes.single().continued)
        assertEquals(1, probes.single().continueCount)
        assertEquals(0, probes.single().textChars)
        assertEquals(0, probes.single().thinkingChars)
    }

    @Test
    fun runFailsWhenEmptyCompletionContinueIsExhausted() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(ModelEvent.Completed),
                        listOf(ModelEvent.Completed),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        val probes = events.filterIsInstance<AgentEvent.EmptyCompletionObserved>()

        assertEquals(RuntimeRunStatus.FAILED, result.status)
        assertEquals(RuntimeErrorCode.MODEL_FINISHED_WITHOUT_RUNTIME_FINISH.wireCode, result.errorCode)
        assertEquals("Model completed without finish(response).", failed.message)
        assertEquals(2, seenRequests.size)
        assertTrue(
            seenRequests[1].messages.any {
                it.role == "user" && it.content.contains(EMPTY_COMPLETION_CONTINUE_MARKER)
            },
        )
        // First empty: continued; second empty: budget exhausted, continued=false.
        assertEquals(2, probes.size)
        assertTrue(probes[0].continued)
        assertEquals("EMPTY_NO_OUTPUT", probes[0].kind)
        assertFalse(probes[1].continued)
        assertEquals("EMPTY_NO_OUTPUT", probes[1].kind)
        assertEquals(1, probes[1].continueCount)
    }

    @Test
    fun runEmitsThinkingOnlyEmptyCompletionProbeBeforeContinue() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ThinkingDelta("(same reasoning as previous tool call)"),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall("finish-1", "finish", """{"response":"recovered"}"""),
                            ModelEvent.Completed,
                        ),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val probes = events.filterIsInstance<AgentEvent.EmptyCompletionObserved>()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals(1, probes.size)
        assertEquals("THINKING_ONLY", probes.single().kind)
        assertTrue(probes.single().continued)
        assertTrue(probes.single().thinkingChars > 0)
        assertEquals(0, probes.single().textChars)
        assertTrue(
            seenRequests[1].messages.any {
                it.role == "user" && it.content.contains(EMPTY_COMPLETION_CONTINUE_MARKER)
            },
        )
    }

    @Test
    fun runFailsWhenModelCompletesWithoutFinish() = runBlocking {
        // Backward-compatible name: an empty completion gets one continue nudge, then fails. Text alone is an answer.
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(ModelEvent.Completed),
                        listOf(ModelEvent.Completed),
                    ),
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()

        assertEquals(RuntimeRunStatus.FAILED, result.status)
        assertEquals("Model completed without finish(response).", failed.message)
    }

}

/**
 * update_todo is the display/progress protocol: its finished event carries the normalized
 * todo list for hosts instead of the metadata redaction placeholder.
 */
private fun assertTodoFinishedCarriesNormalizedPayload(
    event: AgentEvent.NativeToolCallFinished,
    expectedContent: String,
) {
    assertTrue(event.result.contains(""""ok":true"""))
    assertTrue(event.result.contains(expectedContent))
    assertTrue(event.result.contains("in_progress"))
}

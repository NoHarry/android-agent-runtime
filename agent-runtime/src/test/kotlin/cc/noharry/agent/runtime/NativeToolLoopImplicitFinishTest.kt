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

class NativeToolLoopImplicitFinishTest {
    @Test
    fun implicitPlanningResponseRequiresCorrectionAndCannotPassOnSecondAttempt() = runBlocking {
        val planning = "All done. Now finish with a concise Chinese report covering 329.9 kilometers, music played."
        val runtime = AgentRuntimeBuilder.create().modelClient(FakeModelClient(eventsByRound = listOf(
            listOf(ModelEvent.SearchActivity("provider", "route", 1), ModelEvent.TextDelta(planning), ModelEvent.Completed),
            listOf(ModelEvent.SearchActivity("provider", "route", 1), ModelEvent.TextDelta(planning), ModelEvent.Completed),
        ))).build()
        val result = runtime.run(agentRequest()).toList().filterIsInstance<AgentEvent.ResultAvailable>().single().result
        assertEquals(RuntimeRunStatus.FAILED, result.status)
        assertEquals(INVALID_FINISH_RESPONSE_CODE, result.errorCode)
        assertFalse(result.response.contains("329.9"))
    }

    @Test
    fun runImplicitlyFinishesPlainTextAfterHostedSearchActivity() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    ModelEvent.SearchActivity(provider = "Anthropic", queryPreview = "AAPL", resultCount = 10),
                    ModelEvent.TextDelta("AAPL latest price is available from hosted search."),
                    ModelEvent.Completed,
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        val search = events.filterIsInstance<AgentEvent.SearchActivity>().single()

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals("AAPL latest price is available from hosted search.", result.response)
        assertEquals("Anthropic", search.provider)
        assertEquals(10, search.resultCount)
        assertTrue(events.none { it is AgentEvent.Failed })
    }

    @Test
    fun clientShapedWebSearchToolCallEmitsSearchActivityNotBackendFailure() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall(
                                id = "dsml-web_search-21",
                                name = "web_search",
                                argumentsJson = """{"query":"深圳北站 停车场"}""",
                            ),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall(
                                id = "finish-1",
                                name = "finish",
                                argumentsJson = """{"response":"done"}""",
                            ),
                            ModelEvent.Completed,
                        ),
                    ),
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val search = events.filterIsInstance<AgentEvent.SearchActivity>().single()
        val nativeStarts = events.filterIsInstance<AgentEvent.NativeToolCallStarted>()
            .filter { it.toolName == "web_search" }

        assertEquals("深圳北站 停车场", search.queryPreview)
        assertTrue(nativeStarts.isEmpty())
        assertEquals(RuntimeRunStatus.COMPLETED, events.filterIsInstance<AgentEvent.ResultAvailable>().single().result.status)
        assertTrue(events.none { it is AgentEvent.Failed })
    }

    @Test
    fun runImplicitlyFinishesPlainTextAfterBusinessToolResult() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(
            resultJson = """{"ok":true,"changed":true}""",
            exposedTools = listOf(mcpTool("set_temperature")),
        )
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("implicit-finish-business-").toFile())
            .toolProvider(backend)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            setTemperatureSchemaCall(),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            setTemperatureBackendCall(),
                            ModelEvent.Completed,
                        ),
                        listOf(ModelEvent.TextDelta("已完成调温。"), ModelEvent.Completed),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals("已完成调温。", result.response)
        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertTrue(events.none { it is AgentEvent.Failed })
        assertEquals(3, seenRequests.size)
    }

    @Test
    fun runImplicitlyFinishesAfterFinalTodoOnlyRoundAndPlainText() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("implicit-finish-after-todo-").toFile())
            .toolProvider(backend)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(setTemperatureSchemaCall(), ModelEvent.Completed),
                        listOf(setTemperatureBackendCall(), ModelEvent.Completed),
                        listOf(finalCompletedTodoCall(), ModelEvent.Completed),
                        listOf(ModelEvent.TextDelta("全部已执行完成。"), ModelEvent.Completed),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        val result = runtime.run(agentRequest()).toList()
            .filterIsInstance<AgentEvent.ResultAvailable>()
            .single()
            .result

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals("全部已执行完成。", result.response)
        assertEquals(4, seenRequests.size)
    }

    @Test
    fun runImplicitlyFinishesPlainTextUsingSummaryEvenWhenHistoryDroppedToolRows() = runBlocking {
        // Regression: compaction / history rewrite may drop tool messages while
        // summaryBuilder still knows backend work succeeded — must still COMPLETE.
        val backend = LoopMcpProvider(
            resultJson = """{"ok":true,"changed":true}""",
            exposedTools = listOf(mcpTool("set_temperature")),
        )
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("implicit-finish-summary-guard-").toFile())
            .toolProvider(backend)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(setTemperatureSchemaCall(), ModelEvent.Completed),
                        listOf(setTemperatureBackendCall(), ModelEvent.Completed),
                        listOf(ModelEvent.TextDelta("业务已完成，地图已切换。"), ModelEvent.Completed),
                    ),
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals("业务已完成，地图已切换。", result.response)
        assertTrue(events.none { it is AgentEvent.Failed })
    }

    @Test
    fun runDoesNotImplicitlyFinishWhenPlainTextRoundAlsoContainsToolCall() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("implicit-finish-tool-round-").toFile())
            .toolProvider(backend)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            setTemperatureSchemaCall(),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            setTemperatureBackendCall(),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.TextDelta("已完成调温。"),
                            ModelEvent.ToolCall("todo-1", "update_todo", """{"todos":[]}"""),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall("finish-1", "finish", """{"response":"explicit final"}"""),
                            ModelEvent.Completed,
                        ),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        val result = runtime.run(agentRequest()).toList()
            .filterIsInstance<AgentEvent.ResultAvailable>()
            .single()
            .result

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals("explicit final", result.response)
        assertEquals(4, seenRequests.size)
    }

}

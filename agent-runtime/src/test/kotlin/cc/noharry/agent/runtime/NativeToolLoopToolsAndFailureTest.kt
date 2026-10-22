@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelClientException
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelRef
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.vfs.VfsFileSystem
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
import java.io.File
import java.nio.file.Files
import cc.noharry.agent.kernel.core.*

class NativeToolLoopToolsAndFailureTest {
    @Test
    fun runFeedsWorkspaceToolResultBackToModel() = runBlocking {
        val workspace = Files.createTempDirectory("agent-runtime-loop-").toFile()
        File(workspace, "note.txt").writeText("workspace content")
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspace)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-1",
                                name = "read_file",
                                argumentsJson = """{"path":"/note.txt"}""",
                            ),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-2",
                                name = "finish",
                                argumentsJson = """{"response":"read complete"}""",
                            ),
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

        assertEquals("read complete", result.response)
        assertTrue(seenRequests[1].messages.last().content.contains("workspace content"))
    }

    @Test
    fun runFeedsDirectBackendToolResultBackToModel() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("direct-backend-feed-").toFile())
            .toolProvider(backend)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(schemaRead("set_temperature"), ModelEvent.Completed),
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-1",
                                name = qualifiedMcpTool("set_temperature"),
                                argumentsJson = """{"degreesCelsius":22}""",
                            ),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-2",
                                name = "finish",
                                argumentsJson = """{"response":"plan complete"}""",
                            ),
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

        assertEquals("plan complete", result.response)
        assertTrue(seenRequests[2].messages.last().content.contains("completed") || seenRequests[2].messages.last().content.contains("ok"))
        assertTrue(seenRequests[2].messages.any { it.role == "assistant" && it.toolName == qualifiedMcpTool("set_temperature") })
        assertTrue(seenRequests[2].messages.any { it.role == "tool" && it.toolName == qualifiedMcpTool("set_temperature") })
        assertEquals(listOf("set_temperature"), backend.calledTools)
    }

    @Test
    fun runEmitsRedactedNativeToolPublicEvents() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("direct-backend-redact-").toFile())
            .toolProvider(
                LoopMcpProvider(
                    exposedTools = listOf(mcpTool("set_temperature")),
                    resultJson = """{"token":"sk-toolsecret123","phone":"13800138000"}""",
                ),
            )
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(schemaRead("set_temperature"), ModelEvent.Completed),
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-1",
                                name = qualifiedMcpTool("set_temperature"),
                                argumentsJson = """{"token":"sk-toolsecret123"}""",
                            ),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-2",
                                name = "finish",
                                argumentsJson = """{"response":"done phone=13800138000"}""",
                            ),
                            ModelEvent.Completed,
                        ),
                    ),
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val publicText = (
            events.filterIsInstance<AgentEvent.NativeToolCallStarted>() +
                events.filterIsInstance<AgentEvent.NativeToolCallFinished>()
            ).toString()

        assertTrue(events.any { it is AgentEvent.NativeToolCallFinished })
        assertTrue(events.filterIsInstance<AgentEvent.NativeToolCallStarted>().any { it.toolCallId == "tool-1" })
        assertTrue(events.filterIsInstance<AgentEvent.NativeToolCallFinished>().any { it.toolCallId == "tool-1" })
        assertTrue(publicText.contains("redacted"))
        assertFalse(publicText.contains("sk-toolsecret123"))
        assertFalse(publicText.contains("13800138000"))
    }

    @Test
    fun providerFailureBecomesRuntimeFailureEvent() = runBlocking {
        var calls = 0
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(object : ModelClient {
                override val providerConcurrency: ProviderConcurrencyDeclaration =
                    ProviderConcurrencyDeclaration.serialized()
                override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
                    calls += 1
                    throw ModelClientException(
                        code = RuntimeErrorCode.PROVIDER_AUTH_FAILED,
                        recoverable = false,
                        message = "OpenAI provider HTTP 401.",
                    )
                }
            })
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()

        assertEquals(RuntimeRunStatus.FAILED, result.status)
        assertEquals(RuntimeErrorCode.PROVIDER_AUTH_FAILED.wireCode, result.errorCode)
        assertFalse(result.recoverable)
        assertEquals(RuntimeErrorCode.PROVIDER_AUTH_FAILED.wireCode, failed.code)
        assertEquals("OpenAI provider HTTP 401.", failed.message)
        assertFalse(failed.recoverable)
        assertEquals(1, calls)
        assertTrue(events.none { it is AgentEvent.RetryAttempted })
    }

    @Test
    fun recoverableModelFailureRetriesAndCompletes() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .modelRetry(maxAttempts = 2, initialBackoffMillis = 0L, maxBackoffMillis = 0L)
            .modelClient(RecoverableOnceModelClient(seenRequests))
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        val retry = events.filterIsInstance<AgentEvent.RetryAttempted>().single()

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals("done after retry", result.response)
        assertEquals(2, seenRequests.size)
        assertEquals(2, retry.attempt)
        assertEquals(0L, retry.delayMillis)
        assertEquals(null, retry.modelRef)
        assertEquals(null, retry.fallbackModelRef)
    }

    @Test
    fun recoverableModelFailureFallsBackToConfiguredModelRef() = runBlocking {
        val primary = ModelRef("openai", "primary")
        val fallback = ModelRef("openai", "fallback")
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .modelRetry(
                maxAttempts = 1,
                initialBackoffMillis = 0L,
                maxBackoffMillis = 0L,
                fallbackChain = listOf(fallback),
            )
            .modelClient(FallbackModelClient(seenRequests))
            .build()

        val events = runtime.run(agentRequest().copy(modelRef = primary)).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        val retry = events.filterIsInstance<AgentEvent.RetryAttempted>().single()

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals("done on fallback", result.response)
        assertEquals(listOf("primary", "fallback"), seenRequests.map { it.modelRef?.modelId })
        assertEquals(1, retry.attempt)
        assertEquals(primary, retry.modelRef)
        assertEquals(fallback, retry.fallbackModelRef)
    }

    @Test
    fun recoverableModelFailureAfterCommittedToolResultDoesNotRetry() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("retry-after-tool-").toFile())
            .toolProvider(backend)
            .modelRetry(maxAttempts = 2, initialBackoffMillis = 0L, maxBackoffMillis = 0L)
            .modelClient(ToolThenRecoverableFailureModelClient(seenRequests))
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result

        assertEquals(RuntimeRunStatus.FAILED, result.status)
        assertEquals(RuntimeErrorCode.PROVIDER_TRANSPORT_FAILED.wireCode, result.errorCode)
        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertEquals(2, seenRequests.size)
        assertTrue(events.none { it is AgentEvent.RetryAttempted })
    }

    @Test
    fun cancellationAfterModelToolCallSavesSyntheticCancelledResultForResume() = runBlocking {
        val historyStore = InMemoryLoopHistoryStore()
        val firstLoop = nativeLoop(
            modelClient = object : ModelClient {
                override val providerConcurrency: ProviderConcurrencyDeclaration =
                    ProviderConcurrencyDeclaration.serialized()
                override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
                    emit(ModelEvent.ToolCall("cancel-stream", "set_temperature", """{"degreesCelsius":21}"""))
                    throw CancellationException("stream cancelled")
                }
            },
            historyStore = historyStore,
            mcpRegistry = ToolProviderRegistry(listOf(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))),
        )

        val error = try {
            firstLoop.run(agentRequest(), runId = "request-1")
            null
        } catch (error: CancellationException) {
            error
        }
        val savedHistory = requireNotNull(historyStore.read("request-1")).messages

        assertTrue(error is CancellationException)
        assertCancelledToolResultClosed(savedHistory, "cancel-stream", "set_temperature")

        val resumeRequests = mutableListOf<ModelRequest>()
        val resumedLoop = nativeLoop(
            modelClient = finishModel("resumed", resumeRequests),
            historyStore = historyStore,
        )
        val resumed = resumedLoop.resumeUserInput(
            runId = "request-1",
            sessionId = "session-1",
            requestId = "request-1",
            answer = "continue",
            scene = null,
            modelRef = null,
        ) as NativeLoopOutcome.Terminal

        assertEquals(RuntimeRunStatus.COMPLETED, resumed.result.status)
        assertCancelledToolResultClosed(resumeRequests.single().messages, "cancel-stream", "set_temperature")
    }

    @Test
    fun cancellationDuringBackendToolSavesSyntheticCancelledResultForResume() = runBlocking {
        val historyStore = InMemoryLoopHistoryStore()
        val backend = CancellingMcpProvider()
        val firstLoop = nativeLoop(
            modelClient = FakeModelClient(
                eventsByRound = listOf(
                    listOf(setTemperatureSchemaCall(), ModelEvent.Completed),
                    listOf(setTemperatureBackendCall(), ModelEvent.Completed),
                ),
            ),
            historyStore = historyStore,
            mcpRegistry = ToolProviderRegistry(listOf(backend)),
            withWorkspace = true,
        )

        val error = try {
            firstLoop.run(agentRequest(), runId = "request-1")
            null
        } catch (error: CancellationException) {
            error
        }
        val savedHistory = requireNotNull(historyStore.read("request-1")).messages

        assertTrue(error is CancellationException)
        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertCancelledToolResultClosed(savedHistory, "backend-1", qualifiedMcpTool("set_temperature"))

        val resumeRequests = mutableListOf<ModelRequest>()
        val resumedLoop = nativeLoop(
            modelClient = finishModel("resumed", resumeRequests),
            historyStore = historyStore,
        )
        val resumed = resumedLoop.resumeUserInput(
            runId = "request-1",
            sessionId = "session-1",
            requestId = "request-1",
            answer = "continue",
            scene = null,
            modelRef = null,
        ) as NativeLoopOutcome.Terminal

        assertEquals(RuntimeRunStatus.COMPLETED, resumed.result.status)
        assertCancelledToolResultClosed(resumeRequests.single().messages, "backend-1", qualifiedMcpTool("set_temperature"))
    }

    @Test
    fun runPropagatesModelCancellation() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(object : ModelClient {
                override val providerConcurrency: ProviderConcurrencyDeclaration =
                    ProviderConcurrencyDeclaration.serialized()
                override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
                    throw CancellationException("cancelled")
                }
            })
            .build()

        val error = try {
            runtime.run(agentRequest()).toList()
            null
        } catch (error: CancellationException) {
            error
        }

        assertTrue(error is CancellationException)
    }

}

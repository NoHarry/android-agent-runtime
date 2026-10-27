@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.ToolUseResult
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
import cc.noharry.agent.vfs.VfsEntry
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsListResult
import cc.noharry.agent.vfs.VfsReadResult
import cc.noharry.agent.vfs.VfsSearchResult
import cc.noharry.agent.vfs.VfsSource
import cc.noharry.agent.vfs.VfsSourceResolver
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeToolLoopBatchTest {
    @Test
    fun runExecutesConsecutiveReadOnlyToolsConcurrentlyAndReturnsMessagesInOrder() = runBlocking {
        val source = ConcurrentReadProbeSource()
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .virtualWorkspace(batchReadFileSystem(source))
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall("tool-slow", "read_file", """{"path":"/probe/slow.txt"}"""),
                            ModelEvent.ToolCall("tool-fast", "read_file", """{"path":"/probe/fast.txt"}"""),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall("tool-finish", "finish", """{"response":"done"}"""),
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
        val toolMessages = seenRequests[1].messages.filter { it.role == "tool" }

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertTrue("read_file executions should overlap inside one batch", source.concurrent.get())
        assertEquals(listOf("tool-slow", "tool-fast"), toolMessages.map { it.toolCallId })
        assertTrue(toolMessages[0].content.contains("content-slow.txt"))
        assertTrue(toolMessages[1].content.contains("content-fast.txt"))
    }

    @Test
    fun runKeepsWritesAndTodoOutsideReadOnlyBatches() = runBlocking {
        val source = ConcurrentReadProbeSource()
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .virtualWorkspace(batchReadFileSystem(source))
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall("tool-before", "read_file", """{"path":"/probe/before.txt"}"""),
                            ModelEvent.ToolCall("tool-write", "write_file", """{"path":"/probe/write.txt","content":"x"}"""),
                            ModelEvent.ToolCall("tool-todo", "update_todo", """{"items":[]}"""),
                            ModelEvent.ToolCall("tool-after", "read_file", """{"path":"/probe/after.txt"}"""),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall("tool-finish", "finish", """{"response":"done"}"""),
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
        val toolMessages = seenRequests[1].messages.filter { it.role == "tool" }

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertFalse("read_file calls separated by write/update_todo must not overlap", source.concurrent.get())
        assertEquals(
            listOf("tool-before", "tool-write", "tool-todo", "tool-after"),
            toolMessages.map { it.toolCallId },
        )
    }

    @Test
    fun runLaunchesEarlierBatchReadBeforePreparingLaterRead() = runBlocking {
        val source = PrepareGateReadProbeSource()
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .virtualWorkspace(batchReadFileSystem(source))
            .hook(object : RuntimeHook {
                override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
                    if (call.toolCallId == "tool-second") {
                        source.firstReadStarted.await()
                    }
                    return HookDecision.Allow()
                }
            })
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall("tool-first", "read_file", """{"path":"/probe/first.txt"}"""),
                            ModelEvent.ToolCall("tool-second", "read_file", """{"path":"/probe/second.txt"}"""),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall("tool-finish", "finish", """{"response":"done"}"""),
                            ModelEvent.Completed,
                        ),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        val result = withTimeout(BATCH_FINISH_TEST_TIMEOUT_MS) {
            runtime.run(agentRequest()).toList()
                .filterIsInstance<AgentEvent.ResultAvailable>()
                .single()
                .result
        }
        val toolMessages = seenRequests[1].messages.filter { it.role == "tool" }

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals(listOf("tool-first", "tool-second"), toolMessages.map { it.toolCallId })
    }

    @Test
    fun runFinishesOrderedBatchResultBeforeWaitingForLaterReads() = runBlocking {
        val source = FinishGateReadProbeSource()
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .virtualWorkspace(batchReadFileSystem(source))
            .hook(object : RuntimeHook {
                override suspend fun onPostToolUse(call: ToolUse, result: ToolUseResult, context: HookContext) {
                    if (call.toolCallId == "tool-fast") {
                        source.releaseSlowRead.complete(Unit)
                    }
                }
            })
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall("tool-fast", "read_file", """{"path":"/probe/fast.txt"}"""),
                            ModelEvent.ToolCall("tool-slow", "read_file", """{"path":"/probe/slow.txt"}"""),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall("tool-finish", "finish", """{"response":"done"}"""),
                            ModelEvent.Completed,
                        ),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        val result = withTimeout(BATCH_FINISH_TEST_TIMEOUT_MS) {
            runtime.run(agentRequest()).toList()
                .filterIsInstance<AgentEvent.ResultAvailable>()
                .single()
                .result
        }
        val toolMessages = seenRequests[1].messages.filter { it.role == "tool" }

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals(listOf("tool-fast", "tool-slow"), toolMessages.map { it.toolCallId })
        assertTrue(toolMessages[0].content.contains("content-fast.txt"))
        assertTrue(toolMessages[1].content.contains("content-slow.txt"))
    }

    @Test
    fun runExecutesConsecutiveBackendToolsConcurrentlyAndReturnsMessagesInOrder() = runBlocking {
        val backend = ConcurrentBackendProvider()
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(java.nio.file.Files.createTempDirectory("backend-batch-").toFile())
            .toolProvider(backend)
            .modelClient(backendBatchModel(seenRequests))
            .build()

        val result = runtime.run(
            AgentRequest(
                requestId = "request-backend-batch",
                sessionId = "session-1",
                input = "run slow backend and fast backend",
            ),
        ).toList()
            .filterIsInstance<AgentEvent.ResultAvailable>()
            .single()
            .result
        val toolMessages = seenRequests[2].messages
            .filter { it.role == "tool" && it.toolCallId.orEmpty().startsWith("backend-") }

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertTrue("backend executions should overlap inside one batch", backend.concurrent.get())
        assertEquals(listOf("backend-slow", "backend-fast"), toolMessages.map { it.toolCallId })
        assertTrue(toolMessages[0].content.contains("result-slow_backend"))
        assertTrue(toolMessages[1].content.contains("result-fast_backend"))
    }

    @Test
    fun toolConcurrencyOneSerializesReadOnlyBackendBatchAndPreservesOrder() = runBlocking {
        val backend = ConcurrentBackendProvider()
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(java.nio.file.Files.createTempDirectory("backend-capped-batch-").toFile())
            .toolProvider(backend)
            .modelClient(backendBatchModel(seenRequests))
            .toolConcurrency(1)
            .build()

        val result = runtime.run(agentRequest()).toList()
            .filterIsInstance<AgentEvent.ResultAvailable>()
            .single()
            .result
        val toolMessages = seenRequests[2].messages
            .filter { it.role == "tool" && it.toolCallId.orEmpty().startsWith("backend-") }

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertFalse("runtime-wide cap should serialize read-only backend calls", backend.concurrent.get())
        assertEquals(listOf("backend-slow", "backend-fast"), toolMessages.map { it.toolCallId })
    }

    @Test
    fun runDoesNotBatchBackendToolsWithoutReadOnlyHint() = runBlocking {
        val backend = ConcurrentBackendProvider(schemaJson = NO_HINT_BACKEND_SCHEMA)
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(java.nio.file.Files.createTempDirectory("backend-nohint-").toFile())
            .toolProvider(backend)
            .modelClient(backendBatchModel(seenRequests))
            .build()

        val result = runtime.run(agentRequest()).toList()
            .filterIsInstance<AgentEvent.ResultAvailable>()
            .single()
            .result
        val toolMessages = seenRequests[2].messages
            .filter { it.role == "tool" && it.toolCallId.orEmpty().startsWith("backend-") }

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertFalse("backend tools without readOnlyHint should run sequentially", backend.concurrent.get())
        assertEquals(listOf("backend-slow", "backend-fast"), toolMessages.map { it.toolCallId })
    }

    @Test
    fun runBatchesBackendToolsTheProviderDeclaresReadOnly() = runBlocking {
        // MCP puts readOnlyHint in the tool's annotations, which the client turns into effectKind, not in its input schema.
        val backend = ConcurrentBackendProvider(schemaJson = NO_HINT_BACKEND_SCHEMA, effectKind = "READ_ONLY")
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(java.nio.file.Files.createTempDirectory("backend-declared-read-").toFile())
            .toolProvider(backend)
            .modelClient(backendBatchModel(seenRequests))
            .build()

        val result = runtime.run(agentRequest()).toList()
            .filterIsInstance<AgentEvent.ResultAvailable>()
            .single()
            .result

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertTrue("tools declared read-only should overlap inside one batch", backend.concurrent.get())
    }

    @Test
    fun runFeedsSameRoundTodoAndBackendResultsBackInOrder() = runBlocking {
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(java.nio.file.Files.createTempDirectory("todo-backend-same-round-").toFile())
            .toolProvider(backend)
            .modelClient(todoAndBackendSameRoundModel(seenRequests))
            .build()

        val result = runtime.run(agentRequest()).toList()
            .filterIsInstance<AgentEvent.ResultAvailable>()
            .single()
            .result
        val toolMessages = seenRequests[2].messages
            .filter { it.role == "tool" && it.toolCallId in setOf("todo-1", "backend-1") }

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertTrue(seenRequests[1].tools.any { it.name == qualifiedMcpTool("set_temperature") })
        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertEquals(listOf("todo-1", "backend-1"), toolMessages.map { it.toolCallId })
        assertTrue(toolMessages[0].content.contains("Check route"))
        assertTrue(toolMessages[1].content.contains("ok"))
    }

    private fun backendBatchModel(seenRequests: MutableList<ModelRequest>): FakeModelClient =
        FakeModelClient(
            eventsByRound = listOf(
                listOf(
                    backendSchemaCall("schema-slow", "slow_backend"),
                    backendSchemaCall("schema-fast", "fast_backend"),
                    ModelEvent.Completed,
                ),
                listOf(
                    backendToolCall("backend-slow", "slow_backend", "slow"),
                    backendToolCall("backend-fast", "fast_backend", "fast"),
                    ModelEvent.Completed,
                ),
                listOf(ModelEvent.ToolCall("tool-finish", "finish", """{"response":"done"}"""), ModelEvent.Completed),
            ),
            seenRequests = seenRequests,
        )

    private fun todoAndBackendSameRoundModel(seenRequests: MutableList<ModelRequest>): FakeModelClient =
        FakeModelClient(
            eventsByRound = listOf(
                listOf(
                    backendSchemaCall("schema-1", "set_temperature"),
                    ModelEvent.Completed,
                ),
                listOf(
                    ModelEvent.ToolCall(
                        "todo-1",
                        "update_todo",
                        """
                            {
                              "todos": [
                                {"content":"Check route","status":"in_progress"}
                              ]
                            }
                        """.trimIndent(),
                    ),
                    backendToolCall("backend-1", "set_temperature", "22"),
                    ModelEvent.Completed,
                ),
                listOf(ModelEvent.ToolCall("tool-finish", "finish", """{"response":"done"}"""), ModelEvent.Completed),
            ),
            seenRequests = seenRequests,
        )

    private fun backendSchemaCall(id: String, toolName: String): ModelEvent.ToolCall =
        ModelEvent.ToolCall(id, "read_file", """{"path":"/mcp/tools/${qualifiedMcpTool(toolName)}/schema.json"}""")

    private fun backendToolCall(id: String, toolName: String, value: String): ModelEvent.ToolCall =
        ModelEvent.ToolCall(id, qualifiedMcpTool(toolName), """{"value":"$value"}""")
}

private fun batchReadFileSystem(source: VfsSource): VfsFileSystem =
    VfsFileSystem.fromJson(
        jsonText = """
            {
              "schemaVersion": 1,
              "vfsContractVersion": "1.0.0",
              "workspaceRoot": "/",
              "mounts": [
                {"id":"probe","source":"memory://probe","target":"/probe","mode":"readOnly","priority":10}
              ]
            }
        """.trimIndent(),
        sourceResolver = VfsSourceResolver { if (it == "memory://probe") source else null },
    )

private class ConcurrentReadProbeSource : VfsSource {
    private val activeReads = AtomicInteger(0)
    val concurrent = AtomicBoolean(false)

    override suspend fun list(relativePath: String): VfsListResult =
        VfsListResult.Entries(listOf(VfsEntry("slow.txt", "file"), VfsEntry("fast.txt", "file")))

    override suspend fun read(relativePath: String, offset: Long, maxBytes: Int): VfsReadResult {
        if (activeReads.incrementAndGet() > 1) concurrent.set(true)
        return try {
            Thread.sleep(BATCH_READ_TEST_DELAY_MS)
            val text = "content-$relativePath"
            VfsReadResult.Found(
                text = text,
                bytesRead = text.toByteArray(Charsets.UTF_8).size,
                totalBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
            )
        } finally {
            activeReads.decrementAndGet()
        }
    }

    override suspend fun search(
        relativePath: String,
        pattern: String,
        maxMatches: Int,
        maxScanBytes: Long,
    ): VfsSearchResult = VfsSearchResult.NotFound
}

private class PrepareGateReadProbeSource : VfsSource {
    val firstReadStarted = CompletableDeferred<Unit>()

    override suspend fun list(relativePath: String): VfsListResult =
        VfsListResult.Entries(listOf(VfsEntry("first.txt", "file"), VfsEntry("second.txt", "file")))

    override suspend fun read(relativePath: String, offset: Long, maxBytes: Int): VfsReadResult {
        if (relativePath == "first.txt") {
            firstReadStarted.complete(Unit)
        }
        val text = "content-$relativePath"
        return VfsReadResult.Found(
            text = text,
            bytesRead = text.toByteArray(Charsets.UTF_8).size,
            totalBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
        )
    }

    override suspend fun search(
        relativePath: String,
        pattern: String,
        maxMatches: Int,
        maxScanBytes: Long,
    ): VfsSearchResult = VfsSearchResult.NotFound
}

private class FinishGateReadProbeSource : VfsSource {
    val releaseSlowRead = CompletableDeferred<Unit>()

    override suspend fun list(relativePath: String): VfsListResult =
        VfsListResult.Entries(listOf(VfsEntry("fast.txt", "file"), VfsEntry("slow.txt", "file")))

    override suspend fun read(relativePath: String, offset: Long, maxBytes: Int): VfsReadResult {
        if (relativePath == "slow.txt") {
            releaseSlowRead.await()
        }
        val text = "content-$relativePath"
        return VfsReadResult.Found(
            text = text,
            bytesRead = text.toByteArray(Charsets.UTF_8).size,
            totalBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
        )
    }

    override suspend fun search(
        relativePath: String,
        pattern: String,
        maxMatches: Int,
        maxScanBytes: Long,
    ): VfsSearchResult = VfsSearchResult.NotFound
}

private class ConcurrentBackendProvider(
    private val schemaJson: String = READ_ONLY_BACKEND_SCHEMA,
    private val effectKind: String = "UNKNOWN",
) : McpClientProvider {
    private val activeCalls = AtomicInteger(0)
    val concurrent = AtomicBoolean(false)

    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe(2)
    override val serverId: String = TEST_MCP_SERVER_ID
    override val displayName: String = TEST_MCP_SERVER_ID

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(
            mcpTool("slow_backend").copy(description = "slow backend", inputSchemaJson = schemaJson, effectKind = effectKind),
            mcpTool("fast_backend").copy(description = "fast backend", inputSchemaJson = schemaJson, effectKind = effectKind),
        )

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult {
        if (activeCalls.incrementAndGet() > 1) concurrent.set(true)
        return try {
            Thread.sleep(BATCH_READ_TEST_DELAY_MS)
            ToolCallResult(success = true, resultJson = """{"ok":true,"value":"result-$toolName"}""")
        } finally {
            activeCalls.decrementAndGet()
        }
    }
}

private const val BATCH_READ_TEST_DELAY_MS = 80L
private const val BATCH_FINISH_TEST_TIMEOUT_MS = 1_000L
private const val READ_ONLY_BACKEND_SCHEMA =
    """{"type":"object","annotations":{"readOnlyHint":true},"additionalProperties":true}"""
private const val NO_HINT_BACKEND_SCHEMA = """{"type":"object","additionalProperties":true}"""

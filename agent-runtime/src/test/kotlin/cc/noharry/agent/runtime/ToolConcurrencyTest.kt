@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ExecEngine
import cc.noharry.agent.kernel.api.ExecRequest
import cc.noharry.agent.kernel.api.ExecResult
import cc.noharry.agent.kernel.api.ExecToolBridge
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolSource
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.model.ModelClient
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
import cc.noharry.agent.runtime.run.RuntimeHookChain
import cc.noharry.agent.runtime.workspace.VirtualWorkspace
import cc.noharry.agent.transport.WebFetchRequest
import cc.noharry.agent.transport.WebFetchResponse
import cc.noharry.agent.transport.WebFetchTransport
import cc.noharry.agent.vfs.VfsEntry
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsListResult
import cc.noharry.agent.vfs.VfsReadResult
import cc.noharry.agent.vfs.VfsSearchResult
import cc.noharry.agent.vfs.VfsSource
import cc.noharry.agent.vfs.VfsSourceResolver
import cc.noharry.agent.vfs.VfsWriteMode
import cc.noharry.agent.vfs.VfsWriteResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolConcurrencyTest {
    @Test
    fun toolConcurrencyRejectsNonPositiveCaps() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimeBuilder.create().toolConcurrency(0)
        }
    }

    @Test
    fun toolConcurrencyOneIsSharedAcrossConcurrentRuns() = runBlocking {
        val source = ToolConcurrencyProbeSource()
        val runtime = runtime(source, maxConcurrentCalls = 1)

        val results = coroutineScope {
            listOf("first", "second").map { name ->
                async { runtime.run(toolConcurrencyRequest(name)).toList() }
            }.awaitAll()
        }

        assertEquals(1, source.maxConcurrentReads.get())
        results.forEach { events ->
            val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
            assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        }
    }

    @Test
    fun cancellingReadReleasesPermitForNextRun() = runBlocking {
        val source = ToolConcurrencyProbeSource(blockedPath = "blocked.txt")
        val runtime = runtime(source, maxConcurrentCalls = 1)
        val blockedRun = launch {
            runtime.run(toolConcurrencyRequest("blocked")).collect()
        }
        source.blockedReadStarted.await()

        blockedRun.cancelAndJoin()

        val events = withTimeout(TOOL_CONCURRENCY_TEST_TIMEOUT_MS) {
            runtime.run(toolConcurrencyRequest("next")).toList()
        }
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
    }

    @Test
    fun toolConcurrencyOneCapsNativeWritesAcrossRuns() = runBlocking {
        val source = ToolConcurrencyProbeSource()
        val controller = NativeToolUseController(
            mcpRegistry = ToolProviderRegistry(emptyList()),
            workspace = VirtualWorkspace(toolConcurrencyWritableFileSystem(source)),
            hookChain = RuntimeHookChain.empty(),
            toolExecutionLimiter = ToolExecutionLimiter(1),
        )

        coroutineScope {
            listOf("write-first", "write-second").map { name ->
                async {
                    controller.executeWorkspaceToolWithTelemetry(
                        ToolUse(
                            toolName = "write_file",
                            argumentsJson = """{"path":"/memory/$name.txt","content":"content-$name"}""",
                            source = ToolSource.NATIVE_WRITE,
                        ),
                        MaterializerContext(runId = name, requestId = name),
                    )
                }
            }.awaitAll()
        }

        assertEquals(1, source.maxConcurrentWrites.get())
    }

    @Test
    fun toolConcurrencyOneCapsNetworkTransportCalls() = runBlocking {
        val transport = ToolConcurrencyWebFetchTransport()
        val controller = NativeToolUseController(
            mcpRegistry = ToolProviderRegistry(emptyList()),
            workspace = null,
            networkToolBackend = NetworkToolBackend(
                transport = transport,
                confirmationPolicy = NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS,
            ),
            hookChain = RuntimeHookChain.empty(),
            toolExecutionLimiter = ToolExecutionLimiter(1),
        )

        coroutineScope {
            (1..2).map { index ->
                async {
                    controller.executeNetworkToolWithTelemetry(
                        ToolUse(
                            toolName = "web_fetch",
                            argumentsJson = """{"url":"https://example.com/$index"}""",
                            source = ToolSource.NETWORK,
                        ),
                    )
                }
            }.awaitAll()
        }

        assertEquals(1, transport.maxConcurrentCalls.get())
    }

    @Test
    fun toolConcurrencyOneCapsExecChainsAndAllowsNestedTools() = runBlocking {
        val source = ToolConcurrencyProbeSource()
        val engine = ToolConcurrencyExecEngine()
        val runtime = runtime(source, maxConcurrentCalls = 1, execEngine = engine)

        withTimeout(TOOL_CONCURRENCY_TEST_TIMEOUT_MS) {
            coroutineScope {
                listOf("exec-first", "exec-second").map { name ->
                    async { runtime.run(toolConcurrencyRequest(name)).toList() }
                }.awaitAll()
            }
        }

        assertEquals(1, engine.maxConcurrentCalls.get())
        assertEquals(1, source.maxConcurrentReads.get())
    }

    @Test
    fun toolConcurrencyOneCapsCompatibilityPlanBackendCalls() = runBlocking {
        val source = ToolConcurrencyProbeSource()
        val provider = ToolConcurrencyMcpProvider()
        val runtime = runtime(source, maxConcurrentCalls = 1, mcpProvider = provider)

        coroutineScope {
            listOf("plan-first", "plan-second").map { name ->
                async { runtime.run(toolConcurrencyRequest(name)).toList() }
            }.awaitAll()
        }

        assertEquals(1, provider.maxConcurrentCalls.get())
    }

    private fun runtime(
        source: VfsSource,
        maxConcurrentCalls: Int,
        execEngine: ExecEngine? = null,
        mcpProvider: McpClientProvider? = null,
    ) = AgentRuntimeBuilder.create()
            .virtualWorkspace(toolConcurrencyFileSystem(source))
            .modelClient(ToolConcurrencyModelClient())
            .toolConcurrency(maxConcurrentCalls)
            .apply {
                execEngine?.let { engine -> execEngine(engine) }
                mcpProvider?.let { provider -> toolProvider(provider) }
            }
            .build()
}

private class ToolConcurrencyModelClient : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        val runName = request.messages.first { it.role == "user" }.content
        val toolCall = when {
            runName.startsWith("write-") && request.messages.none { it.role == "tool" } ->
                ModelEvent.ToolCall(
                    id = "write-$runName",
                    name = "write_file",
                    argumentsJson = """{"path":"/memory/$runName.txt","content":"content-$runName"}""",
                )
            runName.startsWith("exec-") && request.messages.none { it.role == "tool" } ->
                ModelEvent.ToolCall(
                    id = "exec-$runName",
                    name = "exec_js",
                    argumentsJson = """{"code":"return '$runName'"}""",
                )
            runName.startsWith("plan-") && request.messages.none { it.toolName == "read_file" } ->
                schemaRead(TOOL_CONCURRENCY_MCP_TOOL)
            runName.startsWith("plan-") && request.messages.none { it.toolName == qualifiedMcpTool(TOOL_CONCURRENCY_MCP_TOOL) } ->
                ModelEvent.ToolCall(
                    id = "plan-$runName",
                    name = qualifiedMcpTool(TOOL_CONCURRENCY_MCP_TOOL),
                    argumentsJson = "{}",
                )
            request.messages.none { it.role == "tool" } ->
                ModelEvent.ToolCall(
                    id = "read-$runName",
                    name = "read_file",
                    argumentsJson = """{"path":"/probe/$runName.txt"}""",
                )
            else -> ModelEvent.ToolCall("finish-$runName", "finish", """{"response":"done-$runName"}""")
        }
        emit(toolCall)
        emit(ModelEvent.Completed)
    }
}

private class ToolConcurrencyProbeSource(
    private val blockedPath: String? = null,
) : VfsSource {
    private val activeReads = AtomicInteger(0)
    val maxConcurrentReads = AtomicInteger(0)
    private val activeWrites = AtomicInteger(0)
    val maxConcurrentWrites = AtomicInteger(0)
    val blockedReadStarted = CompletableDeferred<Unit>()

    override suspend fun list(relativePath: String): VfsListResult =
        VfsListResult.Entries(listOf(VfsEntry("probe.txt", "file")))

    override suspend fun read(relativePath: String, offset: Long, maxBytes: Int): VfsReadResult {
        val active = activeReads.incrementAndGet()
        maxConcurrentReads.updateAndGet { current -> maxOf(current, active) }
        return try {
            if (relativePath == blockedPath) {
                blockedReadStarted.complete(Unit)
                awaitCancellation()
            }
            delay(TOOL_CONCURRENCY_READ_DELAY_MS)
            val text = "content-$relativePath"
            VfsReadResult.Found(
                text = text,
                bytesRead = text.toByteArray().size,
                totalBytes = text.toByteArray().size.toLong(),
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

    override suspend fun write(
        relativePath: String,
        content: String,
        mode: VfsWriteMode,
        expectedRevision: String?,
    ): VfsWriteResult {
        val active = activeWrites.incrementAndGet()
        maxConcurrentWrites.updateAndGet { current -> maxOf(current, active) }
        return try {
            delay(TOOL_CONCURRENCY_READ_DELAY_MS)
            VfsWriteResult.Written(content.toByteArray().size.toLong())
        } finally {
            activeWrites.decrementAndGet()
        }
    }
}

private class ToolConcurrencyWebFetchTransport : WebFetchTransport {
    private val activeCalls = AtomicInteger(0)
    val maxConcurrentCalls = AtomicInteger(0)

    override suspend fun execute(request: WebFetchRequest): WebFetchResponse {
        val active = activeCalls.incrementAndGet()
        maxConcurrentCalls.updateAndGet { current -> maxOf(current, active) }
        return try {
            delay(TOOL_CONCURRENCY_READ_DELAY_MS)
            WebFetchResponse(statusCode = 200, body = "ok".toByteArray())
        } finally {
            activeCalls.decrementAndGet()
        }
    }
}

private class ToolConcurrencyExecEngine : ExecEngine {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    private val activeCalls = AtomicInteger(0)
    val maxConcurrentCalls = AtomicInteger(0)

    override suspend fun run(request: ExecRequest, bridge: ExecToolBridge): ExecResult {
        val active = activeCalls.incrementAndGet()
        maxConcurrentCalls.updateAndGet { current -> maxOf(current, active) }
        return try {
            delay(TOOL_CONCURRENCY_READ_DELAY_MS)
            val nested = bridge.callTool(
                "read_file",
                """{"path":"/probe/${request.runId}.txt"}""",
            )
            ExecResult(ok = true, output = nested)
        } finally {
            activeCalls.decrementAndGet()
        }
    }
}

private class ToolConcurrencyMcpProvider : McpClientProvider {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override val serverId: String = TEST_MCP_SERVER_ID
    private val activeCalls = AtomicInteger(0)
    val maxConcurrentCalls = AtomicInteger(0)

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(mcpTool(TOOL_CONCURRENCY_MCP_TOOL))

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult {
        val active = activeCalls.incrementAndGet()
        maxConcurrentCalls.updateAndGet { current -> maxOf(current, active) }
        return try {
            delay(TOOL_CONCURRENCY_READ_DELAY_MS)
            ToolCallResult(success = true, resultJson = """{"ok":true}""")
        } finally {
            activeCalls.decrementAndGet()
        }
    }
}

private fun toolConcurrencyFileSystem(source: VfsSource): VfsFileSystem =
    VfsFileSystem.fromJson(
        jsonText = """
            {
              "schemaVersion": 1,
              "vfsContractVersion": "1.0.0",
              "workspaceRoot": "/",
              "mounts": [
                {"id":"probe","source":"memory://tool-concurrency","target":"/probe","mode":"readOnly","priority":10}
              ]
            }
        """.trimIndent(),
        sourceResolver = VfsSourceResolver { if (it == "memory://tool-concurrency") source else null },
    )

private fun toolConcurrencyWritableFileSystem(source: VfsSource): VfsFileSystem =
    VfsFileSystem.fromJson(
        jsonText = """
            {
              "schemaVersion": 1,
              "vfsContractVersion": "1.0.0",
              "workspaceRoot": "/",
              "mounts": [
                {"id":"memory","source":"memory://tool-concurrency","target":"/memory","mode":"readWrite","priority":10}
              ]
            }
        """.trimIndent(),
        sourceResolver = VfsSourceResolver { if (it == "memory://tool-concurrency") source else null },
    )

private fun toolConcurrencyRequest(name: String): AgentRequest = AgentRequest(
    requestId = "request-$name",
    sessionId = "session-$name",
    input = name,
)

private const val TOOL_CONCURRENCY_READ_DELAY_MS = 100L
private const val TOOL_CONCURRENCY_TEST_TIMEOUT_MS = 5_000L
private const val TOOL_CONCURRENCY_MCP_TOOL = "probe_tool"

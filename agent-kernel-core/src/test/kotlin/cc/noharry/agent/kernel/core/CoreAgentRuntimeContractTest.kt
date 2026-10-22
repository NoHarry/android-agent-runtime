@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.AgentCapabilities
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentPlugin
import cc.noharry.agent.kernel.api.AgentPluginDescriptor
import cc.noharry.agent.kernel.api.AgentPluginScope
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ExecEngine
import cc.noharry.agent.kernel.api.ExecRequest
import cc.noharry.agent.kernel.api.ExecResult
import cc.noharry.agent.kernel.api.ExecToolBridge
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.KernelApiRange
import cc.noharry.agent.kernel.api.KernelApiVersions
import cc.noharry.agent.kernel.api.PluginVersion
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.SessionCapabilityContext
import cc.noharry.agent.kernel.api.SessionCapabilityFactory
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.api.WorkspaceCallContext
import cc.noharry.agent.kernel.api.WorkspaceExecutionResult
import cc.noharry.agent.kernel.api.WorkspacePort
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreAgentRuntimeContractTest {
    @Test
    fun collectAfterCloseEmitsRuntimeClosedWithoutStarted() = runBlocking {
        val runtime = coreRuntime()
        runtime.close()
        val events = runtime.run(request("after-close")).toList()
        assertTrue(events.none { it is AgentEvent.Started })
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        assertEquals(RuntimeErrorCode.RUNTIME_CLOSED.wireCode, failed.code)
        assertTrue(!failed.recoverable)
        assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRuntimeResult("request-after-close")?.status)
        assertEquals(
            RuntimeErrorCode.RUNTIME_CLOSED.wireCode,
            runtime.getRuntimeResult("request-after-close")?.errorCode,
        )
        runtime.close()
    }

    @Test
    fun closeRaceCancelsActiveJobAndClosesResourcesOnceAfterRunLeaves() = runBlocking {
        val closed = AtomicInteger(0)
        val model = BarrierModelClient()
        val release = CompletableDeferred<Unit>()
        model.hold = release
        val runtime = coreRuntime(
            model = model,
            plugins = listOf(ownedPlugin("close-race") { closed.incrementAndGet() }),
        )
        val collector = launch { runtime.run(request("hold")).toList() }
        withTimeout(TEST_TIMEOUT_MS) { model.entered.await() }
        assertEquals(1, runtime.activeRunCountForTest())
        assertTrue(!runtime.resourcesClosedForTest())
        assertEquals(0, closed.get())

        runtime.close()
        assertTrue(runtime.resourcesClosedForTest().not())
        assertEquals(0, closed.get())
        release.complete(Unit)
        collector.join()
        withTimeout(TEST_TIMEOUT_MS) {
            while (!runtime.resourcesClosedForTest()) yield()
        }
        assertEquals(0, runtime.activeRunCountForTest())
        assertEquals(1, closed.get())
        assertEquals(
            RuntimeErrorCode.RUNTIME_CLOSED.wireCode,
            runtime.getRuntimeResult("request-hold")?.errorCode,
        )
        runtime.close()
        assertEquals(1, closed.get())
    }

    @Test
    fun closeCancelsActiveFlowWithoutRequiringTerminalEvent() = runBlocking {
        val model = BarrierModelClient()
        val release = CompletableDeferred<Unit>()
        model.hold = release
        val runtime = coreRuntime(model)
        val events = mutableListOf<AgentEvent>()
        val collector = launch {
            try {
                runtime.run(request("close-cancel")).collect { events += it }
            } catch (_: CancellationException) {
            }
        }
        withTimeout(TEST_TIMEOUT_MS) { model.entered.await() }
        runtime.close()
        collector.join()
        assertTrue(
            events.none {
                it is AgentEvent.ResultAvailable || it is AgentEvent.Finished || it is AgentEvent.Failed
            },
        )
        assertEquals(
            RuntimeErrorCode.RUNTIME_CLOSED.wireCode,
            runtime.getRuntimeResult("request-close-cancel")?.errorCode,
        )
        release.complete(Unit)
        Unit
    }

    @Test
    fun lastLeaveOverlappingAdmitKeepsSingleFlightAndDoesNotCloseProviderMidRun() = runBlocking {
        val probe = SessionOverlapProbe()
        val runtime = coreRuntime(
            model = probe.model,
            plugins = listOf(toolFactoryPlugin { probe.newProvider(it.sessionId) }),
        )
        probe.runtime = runtime
        try {
            coroutineScope {
                repeat(OVERLAP_ADMIT_RUNS) { index ->
                    launch(Dispatchers.Default) {
                        runtime.run(request("overlap-$index", session = SHARED_OVERLAP_SESSION)).toList()
                    }
                }
            }
            assertEquals(1, probe.maxExecuting.get())
            assertEquals(0, probe.closedDuringExecute.get())
            assertTrue(probe.created.get() > 0)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun sameSessionIsSingleFlightAndDifferentSessionsMayOverlap() = runBlocking {
        val model = BarrierModelClient(ProviderConcurrencyDeclaration.threadSafe(4))
        val release = CompletableDeferred<Unit>()
        model.hold = release
        val runtime = coreRuntime(model)
        val first = launch { runtime.run(request("s1-a", session = "session-shared")).toList() }
        withTimeout(TEST_TIMEOUT_MS) { model.entered.await() }
        val secondStarted = CompletableDeferred<Unit>()
        val second = launch {
            runtime.run(request("s1-b", session = "session-shared")).toList()
            secondStarted.complete(Unit)
        }
        repeat(16) { yield() }
        delay(20)
        assertTrue(!secondStarted.isCompleted)
        assertEquals(1, model.maxActive.get())

        release.complete(Unit)
        first.join()
        withTimeout(TEST_TIMEOUT_MS) { second.join() }
        runtime.close()
    }

    @Test
    fun serializedModelDeclarationIsEnforcedAcrossSessions() = runBlocking {
        val model = BarrierModelClient(ProviderConcurrencyDeclaration.serialized())
        val runtime = coreRuntime(model)
        val release = CompletableDeferred<Unit>()
        model.hold = release
        val jobs = List(2) { index ->
            launch { runtime.run(request("cap-$index", session = "session-$index")).toList() }
        }
        withTimeout(TEST_TIMEOUT_MS) { model.entered.await() }
        repeat(8) { yield() }
        assertEquals(1, model.maxActive.get())
        release.complete(Unit)
        jobs.forEach { it.join() }
        assertEquals(1, model.maxActive.get())
        runtime.close()
    }

    @Test
    fun serializedExecDeclarationIsEnforced() = runBlocking {
        val engine = BarrierExecEngine(ProviderConcurrencyDeclaration.serialized())
        val runtime = coreRuntime(
            model = ExecThenFinishModel(),
            plugins = listOf(execPlugin(engine)),
        )
        val release = CompletableDeferred<Unit>()
        engine.hold = release
        coroutineScope {
            val jobs = listOf("exec-a", "exec-b").map { name ->
                async { runtime.run(request(name, session = "session-$name")).toList() }
            }
            withTimeout(TEST_TIMEOUT_MS) { engine.entered.await() }
            assertEquals(1, engine.active.get())
            release.complete(Unit)
            jobs.awaitAll()
        }
        assertEquals(1, engine.maxActive.get())
        assertTrue(runtime.limitedExecForTest() is ConcurrencyLimitedExecEngine)
        runtime.close()
    }

    @Test
    fun sessionFactoryDrainsAfterSessionLeavesAndCloseIsIdempotent() = runBlocking {
        val closed = mutableListOf<String>()
        val instances = mutableListOf<TrackingToolProvider>()
        val runtime = coreRuntime(
            plugins = listOf(
                toolFactoryPlugin { context ->
                    TrackingToolProvider("live-${context.sessionId}", closed).also { instances += it }
                },
            ),
        )
        val live = runtime.blueprintForTest().resolveSessionAll(
            SessionCapabilityContext("session-live"),
            AgentCapabilities.TOOL_PROVIDER,
        ).single() as TrackingToolProvider
        assertTrue(!live.closed)
        runtime.run(request("live", session = "session-live")).toList()
        assertTrue(live.closed)
        assertEquals(listOf("live-session-live"), closed.toList())

        val leftover = runtime.blueprintForTest().resolveSessionAll(
            SessionCapabilityContext("session-left"),
            AgentCapabilities.TOOL_PROVIDER,
        ).single() as TrackingToolProvider
        assertTrue(!leftover.closed)
        runtime.close()
        assertTrue(leftover.closed)
        runtime.close()
        assertEquals(1, leftover.closeCount)
    }

    @Test
    fun duplicateActiveAndTerminalRunIdIsRejected() = runBlocking {
        val model = BarrierModelClient()
        val release = CompletableDeferred<Unit>()
        model.hold = release
        val runtime = coreRuntime(model)
        val first = launch { runtime.run(request("dup")).toList() }
        withTimeout(TEST_TIMEOUT_MS) { model.entered.await() }
        val activeDuplicate = runtime.run(request("dup")).toList()
        val activeFailed = activeDuplicate.filterIsInstance<AgentEvent.Failed>().single()
        assertEquals(RuntimeErrorCode.DUPLICATE_RUN_ID.wireCode, activeFailed.code)
        assertTrue(!activeFailed.recoverable)
        release.complete(Unit)
        first.join()
        val terminalDuplicate = runtime.run(request("dup")).toList()
        assertEquals(
            RuntimeErrorCode.DUPLICATE_RUN_ID.wireCode,
            terminalDuplicate.filterIsInstance<AgentEvent.Failed>().single().code,
        )
        runtime.close()
    }

    @Test
    fun terminalRetentionIsBounded() = runBlocking {
        val runtime = CoreAgentRuntime(
            blueprint = compiledBlueprint(finishModel(), ContractWorkspace()),
            modelClient = finishModel(),
            workspace = ContractWorkspace(),
            maxTerminalRuns = 2,
        )
        try {
            runtime.run(request("keep-1")).toList()
            runtime.run(request("keep-2")).toList()
            runtime.run(request("keep-3")).toList()
            assertNull(runtime.getRuntimeResult("request-keep-1"))
            assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRuntimeResult("request-keep-2")?.status)
            assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRuntimeResult("request-keep-3")?.status)
            val reused = runtime.run(request("keep-1")).toList()
            assertTrue(reused.any { it is AgentEvent.Finished })
        } finally {
            runtime.close()
        }
    }

    @Test
    fun workspaceIdentityFailsClosedForOtherIds() = runBlocking {
        val runtime = coreRuntime(workspaceId = "alpha")
        try {
            assertEquals("alpha", runtime.configuredWorkspaceIdForTest())
            val acceptedNull = runtime.run(request("null-ws").copy(workspaceId = null)).toList()
            assertTrue(acceptedNull.any { it is AgentEvent.Finished })
            val accepted = runtime.run(request("alpha-ws").copy(workspaceId = "alpha")).toList()
            assertTrue(accepted.any { it is AgentEvent.Finished })
            val rejected = runtime.run(request("beta-ws").copy(workspaceId = "beta")).toList()
            val failed = rejected.filterIsInstance<AgentEvent.Failed>().single()
            assertEquals(RuntimeErrorCode.WORKSPACE_NOT_CONFIGURED.wireCode, failed.code)
            assertTrue(!failed.recoverable)
            assertEquals(
                RuntimeErrorCode.WORKSPACE_NOT_CONFIGURED.wireCode,
                runtime.getRuntimeResult("request-beta-ws")?.errorCode,
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun publicQueryAndFailedEventRedactSecretPhoneAndLocation() = runBlocking {
        val runtime = coreRuntime(
            object : ModelClient {
                override val providerConcurrency: ProviderConcurrencyDeclaration =
                    ProviderConcurrencyDeclaration.serialized()

                override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
                    throw IllegalStateException(
                        "loop failed token=sk-coresecret123 phone=13800138000 lat=31.2304",
                    )
                }
            },
        )
        try {
            val events = runtime.run(request("pii")).toList()
            val failed = events.filterIsInstance<AgentEvent.Failed>().single()
            val result = requireNotNull(runtime.getRuntimeResult("request-pii"))
            val publicText = listOf(failed.message, result.response).joinToString()
            assertFalse(publicText.contains("sk-coresecret123"))
            assertFalse(publicText.contains("13800138000"))
            assertFalse(publicText.contains("31.2304"))
            assertTrue(publicText.contains("[REDACTED_SECRET]"))
            assertTrue(publicText.contains("[REDACTED_PHONE]"))
            assertTrue(publicText.contains("[REDACTED_LOCATION]"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun hostModelAndWorkspaceKeepIdentityUnderOneRuntime() {
        val model = finishModel()
        val workspace = ContractWorkspace()
        val runtime = coreRuntime(model, workspace = workspace)
        try {
            assertSame(model, runtime.modelClientForTest())
            assertSame(workspace, runtime.workspaceForTest())
            assertTrue(runtime.limitedModelForTest() is ConcurrencyLimitedModelClient)
        } finally {
            runtime.close()
        }
    }
}

private fun coreRuntime(
    model: ModelClient = finishModel(),
    workspace: WorkspacePort = ContractWorkspace(),
    workspaceId: String = KernelRuntimeBuilder.DEFAULT_WORKSPACE_ID,
    plugins: List<AgentPlugin> = emptyList(),
): CoreAgentRuntime {
    val builder = KernelRuntimeBuilder()
        .model(model)
        .workspace(workspace)
        .workspaceId(workspaceId)
    plugins.forEach(builder::plugin)
    return builder.build() as CoreAgentRuntime
}

private fun compiledBlueprint(model: ModelClient, workspace: WorkspacePort): RuntimeBlueprint {
    val declarations = RuntimeCapabilityDeclarations.Collector().apply {
        modelClient(model)
        workspace(workspace)
    }.snapshot()
    return ProfileCompiler().compile(
        testProfile("core-contract"),
        listOfNotNull(DirectCapabilityPlugin.from(declarations)),
    )
}

private fun request(name: String, session: String = "session-$name") = AgentRequest(
    requestId = "request-$name",
    sessionId = session,
    input = name,
)

private class ContractWorkspace : WorkspacePort {
    override suspend fun execute(
        toolName: String,
        argumentsJson: String,
        context: WorkspaceCallContext,
    ): WorkspaceExecutionResult = WorkspaceExecutionResult("""{"ok":true}""")
}

private fun finishModel(): ModelClient =
    object : ModelClient {
        override val providerConcurrency: ProviderConcurrencyDeclaration =
            ProviderConcurrencyDeclaration.serialized()

        override fun complete(request: ModelRequest) =
            kotlinx.coroutines.flow.flowOf(
                ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""),
                ModelEvent.Completed,
            )
    }

private class BarrierModelClient(
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe(4),
) : ModelClient {
    val entered = CompletableDeferred<Unit>()
    var hold: CompletableDeferred<Unit>? = null
    val active = AtomicInteger(0)
    val maxActive = AtomicInteger(0)

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        entered.complete(Unit)
        val current = active.incrementAndGet()
        maxActive.updateAndGet { existing -> maxOf(existing, current) }
        try {
            hold?.await()
            emit(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""))
            emit(ModelEvent.Completed)
        } finally {
            active.decrementAndGet()
        }
    }
}

private class ExecThenFinishModel : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe(4)

    override fun complete(request: ModelRequest): Flow<ModelEvent> =
        kotlinx.coroutines.flow.flowOf(
            ModelEvent.ToolCall("exec-1", "exec_js", """{"code":"1"}"""),
            ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""),
            ModelEvent.Completed,
        )
}

private class BarrierExecEngine(
    override val providerConcurrency: ProviderConcurrencyDeclaration,
) : ExecEngine {
    val entered = CompletableDeferred<Unit>()
    var hold: CompletableDeferred<Unit>? = null
    val active = AtomicInteger(0)
    val maxActive = AtomicInteger(0)

    override suspend fun run(request: ExecRequest, bridge: ExecToolBridge): ExecResult {
        entered.complete(Unit)
        val current = active.incrementAndGet()
        maxActive.updateAndGet { existing -> maxOf(existing, current) }
        try {
            hold?.await()
            return ExecResult(ok = true, output = "ok")
        } finally {
            active.decrementAndGet()
        }
    }
}

private fun execPlugin(engine: ExecEngine): AgentPlugin =
    object : AgentPlugin {
        override val id: String = "exec"
        override val descriptor = AgentPluginDescriptor(
            id = "exec",
            pluginVersion = PluginVersion(1, 0, 0),
            kernelApiRange = KernelApiRange(KernelApiVersions.CURRENT, KernelApiVersions.CURRENT),
            provides = setOf(AgentCapabilities.EXEC_ENGINE),
        )

        override fun install(scope: AgentPluginScope) {
            scope.provide(AgentCapabilities.EXEC_ENGINE, engine, engine.providerConcurrency)
        }
    }

private fun ownedPlugin(id: String, onClose: () -> Unit): AgentPlugin =
    object : AgentPlugin {
        override val id: String = id
        override val descriptor = AgentPluginDescriptor(
            id = id,
            pluginVersion = PluginVersion(1, 0, 0),
            kernelApiRange = KernelApiRange(KernelApiVersions.CURRENT, KernelApiVersions.CURRENT),
        )

        override fun install(scope: AgentPluginScope) {
            scope.own(AutoCloseable { onClose() })
        }
    }

private fun toolFactoryPlugin(
    factory: (SessionCapabilityContext) -> ToolProvider,
): AgentPlugin {
    val key = AgentCapabilities.TOOL_PROVIDER
    return object : AgentPlugin {
        override val id: String = "session-tools"
        override val descriptor = AgentPluginDescriptor(
            id = "session-tools",
            pluginVersion = PluginVersion(1, 0, 0),
            kernelApiRange = KernelApiRange(KernelApiVersions.CURRENT, KernelApiVersions.CURRENT),
            provides = setOf(key),
        )

        override fun install(scope: AgentPluginScope) {
            scope.provide(key, SessionCapabilityFactory(factory))
        }
    }
}

private class SessionOverlapProbe {
    lateinit var runtime: CoreAgentRuntime
    val executing = AtomicInteger(0)
    val maxExecuting = AtomicInteger(0)
    val closedDuringExecute = AtomicInteger(0)
    val created = AtomicInteger(0)
    val model = OverlapModelClient(this)

    fun newProvider(sessionId: String): TrackingToolProvider {
        created.incrementAndGet()
        return TrackingToolProvider("live-$sessionId") {
            if (executing.get() > 0) closedDuringExecute.incrementAndGet()
        }
    }

    fun realizeSessionProvider() {
        runtime.blueprintForTest().resolveSessionAll(
            SessionCapabilityContext(SHARED_OVERLAP_SESSION),
            AgentCapabilities.TOOL_PROVIDER,
        )
    }
}

private class OverlapModelClient(
    private val probe: SessionOverlapProbe,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe(8)

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        val current = probe.executing.incrementAndGet()
        probe.maxExecuting.updateAndGet { existing -> maxOf(existing, current) }
        try {
            probe.realizeSessionProvider()
            delay(2)
            emit(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""))
            emit(ModelEvent.Completed)
        } finally {
            probe.executing.decrementAndGet()
        }
    }
}

private class TrackingToolProvider(
    override val providerId: String,
    private val closedIds: MutableList<String> = mutableListOf(),
    private val onClose: () -> Unit = {},
) : ToolProvider, AutoCloseable {
    var closed: Boolean = false
        private set
    var closeCount: Int = 0
        private set

    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(ToolDescriptor(name = "echo", description = "echo", inputSchemaJson = """{"type":"object"}"""))

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult = ToolCallResult(success = true, resultJson = "{}")

    override fun close() {
        closeCount += 1
        if (closed) return
        closed = true
        onClose()
        closedIds += providerId
    }
}

private const val TEST_TIMEOUT_MS = 5_000L
private const val OVERLAP_ADMIT_RUNS = 64
private const val SHARED_OVERLAP_SESSION = "session-overlap"

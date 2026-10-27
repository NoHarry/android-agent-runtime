@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.RuntimeCompletedTurn
import cc.noharry.agent.kernel.api.RuntimeTurnInput
import cc.noharry.agent.kernel.api.RuntimeTurnObserver
import cc.noharry.agent.kernel.api.SessionRunConflictPolicy
import cc.noharry.agent.kernel.api.TurnInputObservation
import cc.noharry.agent.kernel.api.TurnObservationReceipt
import cc.noharry.agent.runtime.probe.PROBES_JSONL
import cc.noharry.agent.runtime.probe.probeRunDirectory
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.policy.PolicyAction
import cc.noharry.agent.policy.PolicyContext
import cc.noharry.agent.policy.PolicyDecision
import cc.noharry.agent.policy.PolicyRule
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.runtime.eventlog.InMemorySessionEventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

internal const val SCHEDULING_TEST_TIMEOUT_MS = 8_000L
internal fun directBackendCallJson(): String = "{}"

internal fun runSchedulingTest(block: suspend CoroutineScope.() -> Unit) {
    runBlocking {
        withTimeout(SCHEDULING_TEST_TIMEOUT_MS) {
            block()
        }
    }
}

internal suspend fun awaitState(runtime: AgentRuntime, runId: String, status: RuntimeRunStatus) {
    withTimeout(SCHEDULING_TEST_TIMEOUT_MS) {
        while (runtime.getRunState(runId)?.status != status) {
            yield()
        }
    }
}

internal suspend fun awaitQueued(
    runtime: DefaultAgentRuntime,
    runId: String,
    minQueued: Int = 1,
) {
    withTimeout(SCHEDULING_TEST_TIMEOUT_MS) {
        while (true) {
            val persisted = runtime.getRunState(runId)?.status == RuntimeRunStatus.QUEUED
            val snap = runtime.sessionSchedulerSnapshotForTest()
            val parked = snap.queuedCount >= minQueued && !snap.owners.values.contains(runId)
            if (persisted && parked) return@withTimeout
            yield()
        }
    }
}

internal suspend fun assertIdle(runtime: DefaultAgentRuntime) {
    val snap = runtime.sessionSchedulerSnapshotForTest()
    assertEquals(0, snap.activeExecutionCount)
    assertEquals(0, snap.queuedCount)
    assertTrue(snap.owners.isEmpty())
    assertTrue(snap.waitingOwnerRunIds.isEmpty())
}

internal fun countProbeTerminals(root: File, runId: String): Int {
    val probes = File(probeRunDirectory(root, runId), PROBES_JSONL)
    if (!probes.isFile) return 0
    return probes.readLines().count { it.contains("\"stageId\":\"run.terminal\"") }
}

internal suspend fun assertIdleOwnersExcept(
    runtime: DefaultAgentRuntime,
    vararg owners: Pair<String, String>,
) {
    val snap = runtime.sessionSchedulerSnapshotForTest()
    assertEquals(owners.toMap(), snap.owners)
    assertEquals(0, snap.activeExecutionCount)
    assertEquals(owners.map { it.second }.toSet(), snap.waitingOwnerRunIds.toSet())
}

internal fun runtime(
    model: ModelClient,
    maxActive: Int = 4,
    maxQueuedPerSession: Int = 16,
    maxQueuedGlobal: Int = 64,
    observer: RuntimeTurnObserver? = null,
    eventLog: InMemorySessionEventLog? = null,
): DefaultAgentRuntime {
    val builder = AgentRuntimeBuilder.create()
        .modelClient(model)
        .modelConcurrency(maxConcurrentCalls = 4)
        .sessionConcurrency(
            maxActiveSessions = maxActive,
            maxQueuedRunsPerSession = maxQueuedPerSession,
            maxQueuedRunsGlobal = maxQueuedGlobal,
        )
    if (observer != null) {
        builder.turnObserver(observer)
    }
    if (eventLog != null) {
        builder.sessionEventLog(eventLog)
    }
    return builder.build() as DefaultAgentRuntime
}

internal fun planConfirmationRuntime(provider: GatedMcpProvider): DefaultAgentRuntime =
    AgentRuntimeBuilder.create()
        .workspaceRoot(java.nio.file.Files.createTempDirectory("sched-confirm-").toFile())
        .toolProvider(provider)
        .policyRule(confirmUnlessApprovedPolicy())
        .modelClient(directBackendCallModel())
        .sessionConcurrency(maxActiveSessions = 2)
        .build() as DefaultAgentRuntime

internal fun confirmationRuntime(): DefaultAgentRuntime =
    AgentRuntimeBuilder.create()
        .confirmationOnRun()
        .sessionConcurrency(maxActiveSessions = 2)
        .build() as DefaultAgentRuntime

internal fun request(
    runId: String,
    sessionId: String,
    policy: SessionRunConflictPolicy? = null,
): AgentRequest = AgentRequest(
    requestId = runId,
    sessionId = sessionId,
    input = runId,
    sessionRunConflictPolicy = policy,
)

private suspend fun FlowCollector<ModelEvent>.emitFinish(request: ModelRequest) {
    emit(
        ModelEvent.ToolCall(
            id = "finish-1",
            name = "finish",
            argumentsJson = """{"response":"${request.messages.last().content}"}""",
        ),
    )
    emit(ModelEvent.Completed)
}

internal fun finishModel(): ModelClient = object : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe()

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        emitFinish(request)
    }
}

internal fun directBackendCallModel(): ModelClient =
    FakeModelClient(
        eventsByRound = listOf(
            listOf(schemaRead("set_temperature"), ModelEvent.Completed),
            listOf(
                ModelEvent.ToolCall(
                    id = "backend-1",
                    name = qualifiedMcpTool("set_temperature"),
                    argumentsJson = directBackendCallJson(),
                ),
                ModelEvent.Completed,
            ),
            listOf(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""), ModelEvent.Completed),
        ),
    )

internal fun askThenConfirmModel(): ModelClient =
    FakeModelClient(
        eventsByRound = listOf(
            listOf(schemaRead("set_temperature"), ModelEvent.Completed),
            listOf(
                ModelEvent.ToolCall("backend-1", qualifiedMcpTool("set_temperature"), directBackendCallJson()),
                ModelEvent.Completed,
            ),
            listOf(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""), ModelEvent.Completed),
        ),
    )

internal fun confirmUnlessApprovedPolicy(): PolicyRule = PolicyRule { action, context: PolicyContext ->
    val source = (action as? PolicyAction.Call)?.source
    if (source != "BACKEND" || context.approvedConfirmation) {
        PolicyDecision.Allow
    } else {
        PolicyDecision.RequireConfirmation("Needs approval", "Approve this action?")
    }
}

internal class GatedFinishModel(
    private val gates: List<CompletableDeferred<Unit>>,
    private val entered: List<CompletableDeferred<Unit>>,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe()
    private val index = AtomicInteger(0)

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        val i = index.getAndIncrement()
        entered.getOrNull(i)?.complete(Unit)
        gates.getOrNull(i)?.await()
        emitFinish(request)
    }
}

internal class KeyedGateFinishModel(
    private val gates: Map<String, CompletableDeferred<Unit>>,
    private val entered: Map<String, CompletableDeferred<Unit>>,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe()

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        val name = request.messages.first { it.role == "user" }.content
        entered[name]?.complete(Unit)
        gates[name]?.await()
        emitFinish(request)
    }
}

internal class SingleGateFinishModel(
    private val gate: CompletableDeferred<Unit>,
    private val entered: CompletableDeferred<Unit>,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe()

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        entered.complete(Unit)
        gate.await()
        emitFinish(request)
    }
}

internal class FirstHoldFinishModel(
    private val firstHold: CompletableDeferred<Unit>,
    private val firstEntered: CompletableDeferred<Unit>,
    private val started: MutableList<String>,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe()
    private val count = AtomicInteger(0)

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        val name = request.messages.first { it.role == "user" }.content
        val index = count.getAndIncrement()
        started += name
        if (index == 0) {
            firstEntered.complete(Unit)
            firstHold.await()
        }
        emitFinish(request)
    }
}

internal class MixedWaitingAndHoldModel(
    private val holdA: CompletableDeferred<Unit>,
    private val enteredA: CompletableDeferred<Unit>,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe()

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        val name = request.messages.first { it.role == "user" }.content
        if (name == RUN_B1) {
            if (request.messages.none { it.role == "tool" }) {
                emit(schemaRead("set_temperature"))
            } else {
                emit(ModelEvent.ToolCall("backend-1", qualifiedMcpTool("set_temperature"), directBackendCallJson()))
            }
            emit(ModelEvent.Completed)
            return@flow
        }
        enteredA.complete(Unit)
        holdA.await()
        emitFinish(request)
    }
}

internal class GatedMcpProvider(
    private val gate: CompletableDeferred<Unit>,
    private val entered: CompletableDeferred<Unit>,
) : McpClientProvider {
    override val serverId: String = TEST_MCP_SERVER_ID
    override val displayName: String = TEST_MCP_SERVER_ID
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    private val calls = AtomicInteger(0)
    val callCount: Int
        get() = calls.get()

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(mcpTool("set_temperature").copy(inputSchemaJson = """{"type":"object","additionalProperties":true}"""))

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult {
        calls.incrementAndGet()
        entered.complete(Unit)
        gate.await()
        return ToolCallResult(success = true, resultJson = """{"ok":true}""")
    }
}

internal class UncooperativeHoldModel(
    private val hold: CompletableDeferred<Unit>,
    private val entered: CompletableDeferred<Unit>,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe()

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        entered.complete(Unit)
        withContext(NonCancellable) {
            hold.await()
        }
        emitFinish(request)
    }
}

internal class CountingFinishModel : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe()
    val calls = AtomicInteger(0)

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        calls.incrementAndGet()
        emitFinish(request)
    }
}

internal class HungTerminalObserver(
    private val entered: CompletableDeferred<Unit>,
    private val hang: CompletableDeferred<Unit>,
) : RuntimeTurnObserver {
    override val id: String = "hung-terminal"

    override suspend fun onTurnInput(input: RuntimeTurnInput): TurnInputObservation =
        TurnInputObservation.noop(id)

    override suspend fun onTurnFinished(turn: RuntimeCompletedTurn): TurnObservationReceipt {
        entered.complete(Unit)
        withContext(NonCancellable) {
            hang.await()
        }
        return TurnObservationReceipt(id, enqueued = true)
    }
}

internal class CountingTerminalObserver : RuntimeTurnObserver {
    override val id: String = "scheduling-count"
    val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    override suspend fun onTurnInput(input: RuntimeTurnInput): TurnInputObservation =
        TurnInputObservation.noop(id)

    override suspend fun onTurnFinished(turn: RuntimeCompletedTurn): TurnObservationReceipt {
        counts.merge(turn.runId, 1, Int::plus)
        return TurnObservationReceipt(id, enqueued = true)
    }
}

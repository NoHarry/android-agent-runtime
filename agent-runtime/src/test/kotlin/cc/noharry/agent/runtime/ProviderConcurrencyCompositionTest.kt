@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentPlugin
import cc.noharry.agent.kernel.api.AgentPluginScope
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ExecEngine
import cc.noharry.agent.kernel.api.ExecRequest
import cc.noharry.agent.kernel.api.ExecResult
import cc.noharry.agent.kernel.api.ExecToolBridge
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ProviderConcurrencyCompositionTest {
    @Test
    fun modelConcurrencyRejectsNonPositiveCaps() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimeBuilder.create().modelConcurrency(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimeBuilder.create().modelConcurrency(-1)
        }
    }

    @Test
    fun builtRuntimeGlobalModelCapAppliesAcrossConcurrentRuns() = runBlocking {
        val delegate = BarrierModelClient(ProviderConcurrencyDeclaration.threadSafe(4))
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(delegate)
            .modelConcurrency(1)
            .build()
        val release = CompletableDeferred<Unit>()
        delegate.hold = release

        val first = launch { runtime.run(request("first")).collect() }
        withTimeout(TEST_TIMEOUT_MS) { delegate.entered.await() }
        val secondStarted = CompletableDeferred<Unit>()
        val second = launch {
            runtime.run(request("second")).collect()
            secondStarted.complete(Unit)
        }
        repeat(16) { yield() }
        delay(20)
        assertTrue(!secondStarted.isCompleted)
        assertEquals(1, delegate.maxActive.get())
        release.complete(Unit)
        first.join()
        withTimeout(TEST_TIMEOUT_MS) { second.join() }
        assertEquals(1, delegate.maxActive.get())
        runtime.close()
    }

    @Test
    fun builtRuntimeProviderCapAppliesBelowGlobalBudget() = runBlocking {
        val delegate = BarrierModelClient(ProviderConcurrencyDeclaration.serialized())
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(delegate)
            .modelConcurrency(4)
            .build()
        val release = CompletableDeferred<Unit>()
        delegate.hold = release

        val jobs = List(2) { index ->
            launch { runtime.run(request("call-$index")).collect() }
        }
        withTimeout(TEST_TIMEOUT_MS) { delegate.entered.await() }
        assertEquals(1, delegate.maxActive.get())
        release.complete(Unit)
        jobs.forEach { it.join() }
        assertEquals(1, delegate.maxActive.get())
        runtime.close()
    }

    @Test
    fun cancellingModelCallReleasesPermitsForNextRun() = runBlocking {
        val delegate = BarrierModelClient(ProviderConcurrencyDeclaration.serialized())
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(delegate)
            .modelConcurrency(1)
            .build()
        val release = CompletableDeferred<Unit>()
        delegate.hold = release

        val blocked = launch { runtime.run(request("blocked")).collect() }
        withTimeout(TEST_TIMEOUT_MS) { delegate.entered.await() }
        blocked.cancelAndJoin()

        delegate.hold = null
        val events = withTimeout(TEST_TIMEOUT_MS) {
            runtime.run(request("next")).toList()
        }
        val result = events.filterIsInstance<cc.noharry.agent.kernel.api.AgentEvent.ResultAvailable>().single().result
        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        runtime.close()
    }

    @Test
    fun builtRuntimeExecSerializedDeclarationIsEnforced() = runBlocking {
        val engine = BarrierExecEngine(ProviderConcurrencyDeclaration.serialized())
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(ExecThenFinishModel())
            .execEngine(engine)
            .toolConcurrency(4)
            .modelConcurrency(4)
            .build()
        val release = CompletableDeferred<Unit>()
        engine.hold = release

        coroutineScope {
            val jobs = listOf("exec-first", "exec-second").map { name ->
                async { runtime.run(request(name)).toList() }
            }
            withTimeout(TEST_TIMEOUT_MS) { engine.entered.await() }
            assertEquals(1, engine.active.get())
            release.complete(Unit)
            jobs.awaitAll()
        }
        assertEquals(1, engine.maxActive.get())
        runtime.close()
    }

    @Test
    fun builtRuntimeRejectsDirectSessionFactoryModelAndExec() {
        val modelError = assertThrows(IllegalStateException::class.java) {
            AgentRuntimeBuilder.create()
                .modelClient(BarrierModelClient(ProviderConcurrencyDeclaration.sessionFactory(1)))
                .build()
        }
        assertTrue(modelError.message.orEmpty().contains("SESSION_FACTORY"))

        val execError = assertThrows(IllegalStateException::class.java) {
            AgentRuntimeBuilder.create()
                .modelClient(FinishModelClient())
                .execEngine(BarrierExecEngine(ProviderConcurrencyDeclaration.sessionFactory(1)))
                .build()
        }
        assertTrue(execError.message.orEmpty().contains("SESSION_FACTORY"))
    }

    @Test
    fun runtimeCloseClearsSharedLimiterBeforePluginClose() = runBlocking {
        val provider = CatalogMcpProvider()
        val limiterCountAtPluginClose = AtomicInteger(-1)
        lateinit var built: DefaultAgentRuntime
        built = AgentRuntimeBuilder.create()
            .modelClient(FinishModelClient())
            .toolProvider(provider)
            .plugin(
                object : AgentPlugin {
                    override val descriptor = testDescriptor("close-order")
                    override fun install(scope: AgentPluginScope) {
                        scope.own(
                            AutoCloseable {
                                limiterCountAtPluginClose.set(built.providerLimiterEntryCountForTests())
                            },
                        )
                    }
                },
            )
            .build() as DefaultAgentRuntime

        built.run(request("catalog")).toList()
        assertTrue(built.providerLimiterEntryCountForTests() >= 1)

        built.close()
        built.close()

        assertEquals(0, built.providerLimiterEntryCountForTests())
        assertEquals(0, limiterCountAtPluginClose.get())
    }
}

private class BarrierModelClient(
    override val providerConcurrency: ProviderConcurrencyDeclaration,
) : ModelClient {
    val active = AtomicInteger(0)
    val maxActive = AtomicInteger(0)
    val entered = CompletableDeferred<Unit>()
    var hold: CompletableDeferred<Unit>? = null

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        val current = active.incrementAndGet()
        maxActive.updateAndGet { maxOf(it, current) }
        if (!entered.isCompleted) entered.complete(Unit)
        try {
            hold?.await()
            emitFinish(request)
        } finally {
            active.decrementAndGet()
        }
    }
}

private class FinishModelClient : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        emitFinish(request)
    }
}

private class ExecThenFinishModel : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        val name = request.messages.first { it.role == "user" }.content
        if (request.messages.none { it.role == "tool" }) {
            emit(
                ModelEvent.ToolCall(
                    id = "exec-$name",
                    name = "exec_js",
                    argumentsJson = """{"code":"return 1"}""",
                ),
            )
        } else {
            emitFinish(request)
        }
        emit(ModelEvent.Completed)
    }
}

private class BarrierExecEngine(
    override val providerConcurrency: ProviderConcurrencyDeclaration,
) : ExecEngine {
    val active = AtomicInteger(0)
    val maxActive = AtomicInteger(0)
    val entered = CompletableDeferred<Unit>()
    var hold: CompletableDeferred<Unit>? = null

    override suspend fun run(request: ExecRequest, bridge: ExecToolBridge): ExecResult {
        val current = active.incrementAndGet()
        maxActive.updateAndGet { maxOf(it, current) }
        if (!entered.isCompleted) entered.complete(Unit)
        return try {
            hold?.await()
            ExecResult(ok = true, output = request.runId)
        } finally {
            active.decrementAndGet()
        }
    }
}

private class CatalogMcpProvider : McpClientProvider {
    override val serverId: String = "composition"
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe()

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(
            ToolDescriptor(
                name = "echo",
                description = "echo",
                inputSchemaJson = """{"type":"object"}""",
            ),
        )

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult = ToolCallResult(success = true, resultJson = "{}")
}

private fun finishEvents(request: ModelRequest): List<ModelEvent> {
    val name = request.messages.first { it.role == "user" }.content
    return listOf(
        ModelEvent.ToolCall(
            id = "finish-$name",
            name = "finish",
            argumentsJson = """{"response":"done-$name"}""",
        ),
        ModelEvent.Completed,
    )
}

private suspend fun kotlinx.coroutines.flow.FlowCollector<ModelEvent>.emitFinish(request: ModelRequest) {
    finishEvents(request).forEach { emit(it) }
}

private fun request(name: String): AgentRequest = AgentRequest(
    requestId = "request-$name",
    sessionId = "session-$name",
    input = name,
)

private const val TEST_TIMEOUT_MS = 5_000L

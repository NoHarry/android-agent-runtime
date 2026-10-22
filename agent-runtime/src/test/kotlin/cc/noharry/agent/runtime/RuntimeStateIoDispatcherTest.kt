@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration


import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidence
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunEnvelope
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeTraceEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeWorkpad
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import cc.noharry.agent.kernel.core.DefaultTraceRedactor
import cc.noharry.agent.kernel.core.ReliabilityStorage
import cc.noharry.agent.kernel.core.ToolProviderRegistry
import cc.noharry.agent.kernel.core.TraceVisibilityConfig

class RuntimeStateIoDispatcherTest {
    @Test
    fun reliabilityStorageRunsOnInjectedIoDispatcherAndResultPrecedesFinished() = runBlocking {
        val delegate = FileBackedReliabilityStorage(Files.createTempDirectory("runtime-state-io-").toFile())
        val storage = ThreadCheckingStorage(delegate)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, TEST_IO_THREAD_NAME).apply { isDaemon = true }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val runtime = DefaultAgentRuntime(
                modelClient = TerminalModel,
                mcpRegistry = ToolProviderRegistry(),
                confirmationTemplate = null,
                protocolRequestTemplate = null,
                nowEpochMillis = { System.currentTimeMillis() },
                hookChain = RuntimeHookChain.empty(),
                traceVisibilityConfig = TraceVisibilityConfig(),
                traceRedactor = DefaultTraceRedactor,
                reliabilityStorage = storage,
                runtimeIoDispatcher = dispatcher,
            )

            val events = runtime.run(agentRequest()).toList()
            runtime.getRunState(TEST_RUN_ID)
            runtime.getRuntimeResult(TEST_RUN_ID)
            runtime.getRuntimeTraceEvents(TEST_RUN_ID)

            assertTrue(storage.observedThreads.isNotEmpty())
            assertTrue(storage.observedThreads.all { it.startsWith(TEST_IO_THREAD_NAME) })
            assertEquals(emptyList<String>(), storage.violations)
            assertTrue(events.indexOfFirst { it is AgentEvent.ResultAvailable } < events.indexOfFirst { it is AgentEvent.Finished })
        } finally {
            dispatcher.close()
        }
    }

    private fun agentRequest(): AgentRequest = AgentRequest(
        requestId = TEST_RUN_ID,
        sessionId = "session-1",
        input = "run",
    )
}

private object TerminalModel : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override fun complete(request: ModelRequest): Flow<ModelEvent> = flowOf(
        ModelEvent.ToolCall("tool-1", "finish", """{"response":"done"}"""),
        ModelEvent.Completed,
    )
}

private class ThreadCheckingStorage(
    private val delegate: ReliabilityStorage,
) : ReliabilityStorage by delegate {
    val observedThreads = CopyOnWriteArrayList<String>()
    val violations = CopyOnWriteArrayList<String>()

    override fun saveRun(snapshot: RuntimeRunSnapshot, envelope: RuntimeRunEnvelope) = checked {
        delegate.saveRun(snapshot, envelope)
    }

    override fun readSnapshot(runId: String): RuntimeRunSnapshot? = checked {
        delegate.readSnapshot(runId)
    }

    override fun readEnvelope(runId: String): RuntimeRunEnvelope? = checked {
        delegate.readEnvelope(runId)
    }

    override fun latestSnapshot(sessionId: String, status: RuntimeRunStatus): RuntimeRunSnapshot? = checked {
        delegate.latestSnapshot(sessionId, status)
    }

    override fun saveWorkpad(workpad: RuntimeWorkpad) = checked {
        delegate.saveWorkpad(workpad)
    }

    override fun readWorkpad(runId: String): RuntimeWorkpad? = checked {
        delegate.readWorkpad(runId)
    }

    override fun saveResult(result: RuntimeResult) = checked {
        delegate.saveResult(result)
    }

    override fun readResult(runId: String): RuntimeResult? = checked {
        delegate.readResult(runId)
    }

    override fun saveEvidence(evidence: RuntimeEvidence) = checked {
        delegate.saveEvidence(evidence)
    }

    override fun readEvidence(evidenceId: String): RuntimeEvidence? = checked {
        delegate.readEvidence(evidenceId)
    }

    override fun appendTrace(event: RuntimeTraceEvent) = checked {
        delegate.appendTrace(event)
    }

    override fun readTrace(runId: String): List<RuntimeTraceEvent> = checked {
        delegate.readTrace(runId)
    }

    private fun <T> checked(block: () -> T): T {
        val threadName = Thread.currentThread().name
        observedThreads += threadName
        if (!threadName.startsWith(TEST_IO_THREAD_NAME)) {
            violations += threadName
        }
        return block()
    }
}

private const val TEST_IO_THREAD_NAME = "runtime-state-io-test"
private const val TEST_RUN_ID = "request-1"

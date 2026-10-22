@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SessionSchedulingCloseIntegrationTest {
    @Test
    fun closeTerminalizesActiveWaitingAndQueuedWithoutDoubleTerminal() = runSchedulingTest {
        val observer = CountingTerminalObserver()
        val holdA = CompletableDeferred<Unit>()
        val enteredA = CompletableDeferred<Unit>()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(java.nio.file.Files.createTempDirectory("sched-close-").toFile())
            .modelClient(MixedWaitingAndHoldModel(holdA, enteredA))
            .toolProvider(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .policyRule(confirmUnlessApprovedPolicy())
            .sessionConcurrency(maxActiveSessions = 1)
            .turnObserver(observer)
            .build() as DefaultAgentRuntime
        try {
            runtime.run(request(RUN_B1, SESSION_B)).toList()
            assertEquals(RuntimeRunStatus.WAITING_CONFIRMATION, runtime.getRunState(RUN_B1)?.status)

            val active = launch { runtime.run(request(RUN_A1, SESSION_A)).toList() }
            enteredA.await()
            val queued = launch { runtime.run(request(RUN_A2, SESSION_A)).toList() }
            awaitQueued(runtime, RUN_A2)

            runtime.close()
            active.join()
            queued.join()

            assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRunState(RUN_A1)?.status)
            assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRunState(RUN_A2)?.status)
            assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRunState(RUN_B1)?.status)
            assertEquals(RuntimeErrorCode.RUNTIME_CLOSED.wireCode, runtime.getRuntimeResult(RUN_A1)?.errorCode)
            assertEquals(RuntimeErrorCode.RUNTIME_CLOSED.wireCode, runtime.getRuntimeResult(RUN_A2)?.errorCode)
            assertEquals(RuntimeErrorCode.RUNTIME_CLOSED.wireCode, runtime.getRuntimeResult(RUN_B1)?.errorCode)
            assertEquals(RuntimeCompletionOutcome.UNKNOWN, runtime.getRuntimeResult(RUN_A1)?.completion?.outcome)
            assertEquals(1, observer.counts[RUN_A1])
            assertEquals(1, observer.counts[RUN_A2])
            assertEquals(1, observer.counts[RUN_B1])

            val afterClose = runtime.run(request(RUN_C1, SESSION_C)).toList()
            assertTrue(afterClose.none { it is AgentEvent.Started })
            assertEquals(
                RuntimeErrorCode.RUNTIME_CLOSED.wireCode,
                afterClose.filterIsInstance<AgentEvent.Failed>().single().code,
            )
            assertNull(runtime.getRunState(RUN_C1))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun closeUncooperativeJobSuppressesPostCloseEventsAndSecondTerminal() = runSchedulingTest {
        val observer = CountingTerminalObserver()
        val hold = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val events = CopyOnWriteArrayList<AgentEvent>()
        val runtime = runtime(UncooperativeHoldModel(hold, entered), observer = observer)
        try {
            val active = launch { runtime.run(request(RUN_A1, SESSION_A)).collect { events += it } }
            entered.await()
            runtime.close()
            val emittedDuringClose = events.size
            hold.complete(Unit)
            active.join()
            assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRunState(RUN_A1)?.status)
            assertEquals(RuntimeErrorCode.RUNTIME_CLOSED.wireCode, runtime.getRuntimeResult(RUN_A1)?.errorCode)
            assertEquals(1, observer.counts[RUN_A1])
            assertTrue(
                events.drop(emittedDuringClose).none {
                    it is AgentEvent.ResultAvailable || it is AgentEvent.Failed || it is AgentEvent.Finished
                },
            )
            // Documented survivor limitation: NonCancellable work may continue after close.
            // Close does not claim it can stop arbitrary non-cancellable side effects.
        } finally {
            runtime.close()
        }
    }

    @Test
    fun closeBetweenAttachAndPersistDoesNotStartOrExecute() = runSchedulingTest {
        val model = CountingFinishModel()
        val runtime = runtime(model, maxActive = 1)
        val events = CopyOnWriteArrayList<AgentEvent>()
        val attached = CompletableDeferred<Unit>()
        val releasePersist = CompletableDeferred<Unit>()
        try {
            runtime.sessionRunSchedulerForTest().afterAttachJobForTest = {
                attached.complete(Unit)
                releasePersist.await()
            }
            val active = launch(Dispatchers.Default) {
                runtime.run(request(RUN_A1, SESSION_A)).collect { events += it }
            }
            attached.await()
            runtime.close()
            releasePersist.complete(Unit)
            active.join()
            assertTrue(events.none { it is AgentEvent.Started })
            assertEquals(0, model.calls.get())
            assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRunState(RUN_A1)?.status)
            assertEquals(RuntimeErrorCode.RUNTIME_CLOSED.wireCode, runtime.getRuntimeResult(RUN_A1)?.errorCode)
            assertIdle(runtime)
        } finally {
            runtime.sessionRunSchedulerForTest().afterAttachJobForTest = null
            releasePersist.complete(Unit)
            runtime.close()
        }
    }

    @Test
    fun closeHungTerminalObserverReturnsAndFinalizesOnce() = runSchedulingTest {
        val entered = CompletableDeferred<Unit>()
        val hang = CompletableDeferred<Unit>()
        val cleanups = AtomicInteger(0)
        val observer = HungTerminalObserver(entered, hang)
        val runtime = runtime(finishModel(), observer = observer)
        runtime.beforeCleanupForTest = { cleanups.incrementAndGet() }
        try {
            val active = launch(Dispatchers.Default) {
                runtime.run(request(RUN_A1, SESSION_A)).toList()
            }
            entered.await()
            withTimeout(2_000) {
                withContext(Dispatchers.IO) { runtime.close() }
            }
            assertEquals(1, cleanups.get())
            assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRunState(RUN_A1)?.status)
            assertIdle(runtime)
            hang.complete(Unit)
            active.join()
            assertEquals(1, cleanups.get())
        } finally {
            runtime.beforeCleanupForTest = null
            hang.complete(Unit)
            runtime.close()
        }
    }
}

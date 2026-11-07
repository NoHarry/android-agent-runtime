@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.runtime.thread.DefaultThreadRuntime
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.SessionRunConflictPolicy
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class ThreadFinalizationTest {
    @Test fun completionHookDeletionTerminatesTheGrantedButNotStartedTurn() = runBlocking {
        assertGrantedTurnStopsNormally(closeRuntime = false)
    }

    @Test fun completionHookCloseTerminatesTheGrantedButNotStartedTurn() = runBlocking {
        assertGrantedTurnStopsNormally(closeRuntime = true)
    }

    @Test fun completedEventCollectorCanImmediatelyRunTheNextTurn() = runBlocking {
        threadBuilder(ThreadScriptModel(emptyList())).build().use { runtime ->
            val thread = runtime.startThread()
            val events = mutableListOf<ThreadEvent>()
            withTimeout(5000) {
                thread.runStreamed("first").collect { event ->
                    events += event
                    if (event is ThreadEvent.TurnCompleted) {
                        assertEquals(ThreadStatus.IDLE, thread.status())
                        val next = thread.run("next", TurnOptions(onConflict = SessionRunConflictPolicy.RESUME_CURRENT))
                        assertEquals(TurnStatus.COMPLETED, next.status)
                    }
                }
            }
            assertTurnEvents(events)
        }
    }

    @Test fun completionHookCanImmediatelyRunTheNextTurn() = runBlocking {
        lateinit var thread: AgentThread
        val next = CompletableDeferred<TurnResult>()
        val hook = object : RuntimeHook {
            override suspend fun onTurnFinished(result: TurnResult, context: HookContext) {
                if (result.turnId == "first") next.complete(thread.run("next", TurnOptions(turnId = "next")))
            }
        }
        threadBuilder(ThreadScriptModel(emptyList())).hook(hook).build().use { runtime ->
            thread = runtime.startThread()
            assertEquals(TurnStatus.COMPLETED, withTimeout(5000) { thread.run("first", TurnOptions(turnId = "first")) }.status)
            assertEquals(TurnStatus.COMPLETED, next.await().status)
            assertEquals(listOf("first", "next"), thread.items().filterIsInstance<ThreadItem.UserMessage>().map { it.turnId })
        }
    }

    @Test fun earlyInterruptStillEmitsStartedAndExactlyOneTerminal() = runBlocking {
        val dispatcher = QueuedTurnDispatcher()
        val model = ThreadScriptModel(emptyList())
        AgentRuntime.builder().model(model).build().use { runtime ->
            val thread = runtime.startThread()
            val result = async(dispatcher) { thread.runStreamed("interrupt before execution").toList() }
            dispatcher.until { thread.status() == ThreadStatus.RUNNING }
            thread.interrupt()
            dispatcher.until { result.isCompleted }
            val events = result.await()
            assertTurnEvents(events)
            assertEquals(TurnStatus.INTERRUPTED, (events.last() as ThreadEvent.TurnCompleted).result.status)
            assertTrue(model.requests.isEmpty())
        }
    }

    @Test fun collectorCanDeleteTheActiveThreadAndStillReceiveItsTerminal() = runBlocking {
        threadBuilder(ThreadScriptModel(emptyList())).build().use { runtime ->
            val thread = runtime.startThread()
            val events = mutableListOf<ThreadEvent>()
            withTimeout(5000) {
                thread.runStreamed("delete at first item").collect { event ->
                    events += event
                    if (event is ThreadEvent.ItemStarted && event.item is ThreadItem.UserMessage) runtime.deleteThread(thread.id)
                }
            }
            assertTurnEvents(events)
            assertEquals("THREAD_DELETED", (events.last() as ThreadEvent.TurnFailed).error.code)
            assertTrue(runtime.listThreads().isEmpty())
        }
    }

    @Test fun resultIsSavedExactlyOnceAndSaveFailureBecomesOneTerminal() = runBlocking {
        threadBuilder(ThreadScriptModel(emptyList())).build().use { runtime ->
            val store = (runtime as DefaultThreadRuntime).store
            val saves = AtomicInteger()
            store.beforeSaveTurnForTest = { saves.incrementAndGet() }
            val thread = runtime.startThread()
            thread.run("save once")
            assertEquals(1, saves.get())
            store.beforeSaveTurnForTest = { saves.incrementAndGet(); throw IOException("simulated full storage") }
            val events = thread.runStreamed("cannot save").toList()
            assertTurnEvents(events)
            assertEquals("STORAGE_FAILED", (events.last() as ThreadEvent.TurnFailed).error.code)
            assertEquals(2, saves.get())
            assertEquals("STORAGE_FAILED", thread.run("still cannot save").error?.code)
            assertEquals(3, saves.get())
            store.beforeSaveTurnForTest = null
            assertEquals(TurnStatus.COMPLETED, thread.run("storage available again").status)
        }
    }

    @Test fun cancellationFromCompletionHookCannotRemoveTheTerminalResult() = runBlocking {
        val hook = object : RuntimeHook {
            override suspend fun onTurnFinished(result: TurnResult, context: HookContext) {
                throw CancellationException("host observation stopped")
            }
        }
        threadBuilder(ThreadScriptModel(emptyList())).hook(hook).build().use { runtime ->
            val events = runtime.startThread().runStreamed("finish").toList()
            assertTurnEvents(events)
            assertEquals(TurnStatus.COMPLETED, (events.last() as ThreadEvent.TurnCompleted).result.status)
        }
    }
}

private class QueuedTurnDispatcher : CoroutineDispatcher() {
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
    suspend fun until(done: suspend () -> Boolean) = withTimeout(5000) {
        while (!done()) tasks.poll()?.run() ?: delay(1)
    }
}

/** runBlocking's single-thread event loop leaves the granted continuation queued while the first hook runs. */
private suspend fun CoroutineScope.assertGrantedTurnStopsNormally(closeRuntime: Boolean) {
    val model = GatedThreadModel()
    val grantedBeforeExecution = CompletableDeferred<Boolean>()
    lateinit var activeRuntime: AgentRuntime
    val hook = object : RuntimeHook {
        override suspend fun onTurnFinished(result: TurnResult, context: HookContext) {
            if (result.turnId != "first") return
            val runtime = activeRuntime as DefaultThreadRuntime
            grantedBeforeExecution.complete(runtime.admission.queuedCount() == 0 && runtime.store.executionSlot(context.sessionId).active == null)
            if (closeRuntime) runtime.close() else runtime.deleteThread(context.sessionId)
        }
    }
    limitedThreadBuilder(model).hook(hook).build().use { runtime ->
        activeRuntime = runtime
        val thread = runtime.startThread()
        val first = async { thread.run("first", TurnOptions(turnId = "first")) }
        model.entered.receive()
        val next = async { thread.runStreamed("next", TurnOptions(turnId = "next")).toList() }
        runtime.awaitQueued(1)
        model.release.send(Unit)
        assertEquals(TurnStatus.COMPLETED, withTimeout(5000) { first.await() }.status)
        val events = withTimeout(5000) { next.await() }
        assertTrue(grantedBeforeExecution.await())
        assertTurnEvents(events)
        assertEquals(if (closeRuntime) "RUNTIME_CLOSED" else "THREAD_DELETED", (events.last() as ThreadEvent.TurnFailed).error.code)
        assertEquals(1, model.requests.size)
    }
}

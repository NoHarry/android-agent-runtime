@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.android

import androidx.test.runner.AndroidJUnit4
import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.builder
import cc.noharry.agent.AgentThread
import cc.noharry.agent.ThreadStatus
import cc.noharry.agent.TurnStatus
import cc.noharry.agent.runtime.LoopSettings
import cc.noharry.agent.runtime.RuntimeConcurrency
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the public scheduler path on the device, including caller cancellation and runtime close. */
@RunWith(AndroidJUnit4::class)
class SessionRunSchedulerDeviceSmokeTest {
    @Test fun deviceSmokeProvesOverlapQueueCancelReleaseReadmitAndClose() = runBlocking {
        withTimeout(15_000) {
            val model = DeviceGateModel()
            AgentRuntime.builder().model(model).settings(LoopSettings(
                concurrency = RuntimeConcurrency(activeThreads = 2, queuedPerThread = 1, queuedTotal = 2),
            )).build().use { runtime ->
                val a = runtime.startThread()
                val b = runtime.startThread()
                proveOverlapAndCancel(model, a, b)
                model.release("readmit")
                assertEquals(TurnStatus.COMPLETED, runtime.startThread().run("readmit").status)
                proveClose(model, runtime, a)
            }
        }
    }
}

private suspend fun CoroutineScope.proveOverlapAndCancel(model: DeviceGateModel, a: AgentThread, b: AgentThread) {
    val first = async { a.run("a1") }
    val second = async { b.run("b1") }
    assertEquals(setOf("a1", "b1"), setOf(model.started.receive(), model.started.receive()))
    assertEquals(ThreadStatus.RUNNING, a.status())
    assertEquals(ThreadStatus.RUNNING, b.status())
    val queued = async(start = CoroutineStart.UNDISPATCHED) { a.run("cancelled") }
    delay(50)
    assertFalse(queued.isCompleted)
    queued.cancelAndJoin()
    model.release("a1")
    model.release("b1")
    assertEquals(TurnStatus.COMPLETED, first.await().status)
    assertEquals(TurnStatus.COMPLETED, second.await().status)
    assertFalse(model.seen.contains("cancelled"))
}

private suspend fun CoroutineScope.proveClose(model: DeviceGateModel, runtime: AgentRuntime, thread: AgentThread) {
    // Consume the already completed readmission before waiting for the new active request.
    assertEquals("readmit", model.started.receive())
    val owner = async { runCatching { thread.run("close-owner") } }
    assertEquals("close-owner", model.started.receive())
    val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { thread.run("close-queued") } }
    delay(50)
    runtime.close()
    owner.await()
    queued.await()
    assertFalse(model.seen.contains("close-queued"))
}

private class DeviceGateModel : ModelClient {
    override val providerConcurrency = ProviderConcurrencyDeclaration.threadSafe()
    val started = Channel<String>(Channel.UNLIMITED)
    val seen: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    fun release(input: String) { gates.getOrPut(input) { CompletableDeferred() }.complete(Unit) }

    override fun complete(request: ModelRequest) = flow {
        val input = request.messages.last { it.role == "user" }.content
        seen.add(input)
        started.send(input)
        gates.getOrPut(input) { CompletableDeferred() }.await()
        emit(ModelEvent.TextDelta("Finished $input."))
        emit(ModelEvent.Completed)
    }
}

@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.model.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class ThreadDeletionTest {
    @Test(timeout = 10_000) fun completionHookCannotWaitForItsOwnThreadDeletion() = runBlocking {
        for (throughAnotherRuntime in listOf(false, true)) {
            val root = threadRoot()
            val rejected = CompletableDeferred<Throwable>()
            lateinit var deletionTarget: AgentRuntime
            val hook = object : RuntimeHook {
                override suspend fun onTurnFinished(result: TurnResult, context: HookContext) {
                    try { deletionTarget.deleteThread(context.sessionId) }
                    catch (error: IllegalStateException) { rejected.complete(error) }
                }
            }
            threadBuilder(ThreadScriptModel(emptyList()), root).hook(hook).build().use { runtime ->
                threadBuilder(ThreadScriptModel(emptyList()), root).build().use { other ->
                    deletionTarget = if (throughAnotherRuntime) other else runtime
                    val thread = runtime.startThread()
                    assertEquals(TurnStatus.COMPLETED, withTimeout(2000) { thread.run("finish") }.status)
                    assertTrue(withTimeout(1000) { rejected.await() }.message.orEmpty().contains("own active turn"))
                    assertEquals(1, runtime.listThreads().size)
                    withTimeout(1000) { deletionTarget.deleteThread(thread.id) }
                    assertTrue(runtime.listThreads().isEmpty())
                }
            }
        }
    }

    @Test fun deletingInAnotherRuntimeInvalidatesOldHandlesEvenAfterIdIsReused() = runBlocking {
        val root = threadRoot()
        val modelA = ThreadScriptModel(emptyList())
        val modelB = ThreadScriptModel(emptyList())
        threadBuilder(modelA, root).build().use { a ->
            threadBuilder(modelB, root).build().use { b ->
                val oldA = a.startThread(ThreadOptions(threadId = "reused"))
                val oldB = b.resumeThread("reused")
                oldA.run("OLD_A"); oldB.run("OLD_B")
                b.deleteThread("reused")
                assertDeleted { oldA.run("must not run") }
                assertDeleted { oldA.items() }
                val freshA = a.startThread(ThreadOptions(threadId = "reused"))
                freshA.run("NEW_A")
                assertDeleted { oldA.interrupt() }
                assertDeleted { oldB.steer("must not steer") }
                assertDeleted { oldA.items() }
                val freshB = b.resumeThread("reused")
                freshB.run("NEW_B")
                assertFalse(modelA.requests.last().messages.any { it.content.contains("OLD_") })
                assertFalse(modelB.requests.last().messages.any { it.content.contains("OLD_") })
                assertEquals(2, freshB.items().filterIsInstance<ThreadItem.UserMessage>().size)
            }
        }
    }

    @Test fun deletionCancelsActiveTurnRejectsQueuedTurnsAndCannotBeUndoneByLateWrites() = runBlocking {
        val root = threadRoot()
        val model = GatedThreadModel()
        limitedThreadBuilder(model, root).build().use { a ->
            threadBuilder(ThreadScriptModel(emptyList()), root).build().use { b ->
                val oldA = a.startThread(ThreadOptions(threadId = "delete-active"))
                val oldB = b.resumeThread("delete-active")
                val active = async { oldA.runStreamed("OLD_ACTIVE").toList() }
                model.entered.receive()
                val queued = async { oldB.run("OLD_QUEUED") }
                a.awaitQueued(1)
                withTimeout(5000) { b.deleteThread("delete-active") }
                assertEquals("THREAD_DELETED", queued.await().error?.code)
                active.await()
                assertTrue(a.listThreads().isEmpty())
                assertEquals(1, model.requests.size)
                assertDeleted { oldA.pendingRequests() }
                val fresh = b.startThread(ThreadOptions(threadId = "delete-active"))
                fresh.run("NEW")
                assertEquals(listOf("NEW"), fresh.items().filterIsInstance<ThreadItem.UserMessage>().map { (it.input.single() as InputPart.Text).text })
                assertDeleted { oldA.run("late") }
            }
        }
    }
}

private suspend fun assertDeleted(block: suspend () -> Unit) {
    try { block(); fail("Deleted handle was accepted") } catch (error: IllegalStateException) {
        assertEquals("Thread was deleted", error.message)
    }
}

internal fun limitedThreadBuilder(model: ModelClient, root: File = threadRoot(), perThread: Int = 2, total: Int = 4): AgentRuntimeBuilder =
    threadBuilder(model, root).settings(LoopSettings(concurrency = RuntimeConcurrency(activeThreads = 1, queuedPerThread = perThread, queuedTotal = total)))

internal class GatedThreadModel : ModelClient {
    override val providerConcurrency = ProviderConcurrencyDeclaration.threadSafe()
    val requests = mutableListOf<ModelRequest>()
    val entered = Channel<Unit>(Channel.UNLIMITED)
    val release = Channel<Unit>(Channel.UNLIMITED)
    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        requests += request
        entered.send(Unit)
        release.receive()
        finishEvents("done").forEach { emit(it) }
    }
}

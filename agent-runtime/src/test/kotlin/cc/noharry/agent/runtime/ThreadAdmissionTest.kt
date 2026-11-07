@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.runtime.thread.DefaultThreadRuntime
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.RuntimeHook
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ThreadAdmissionTest {
    @Test fun sameThreadQueueHasOneBoundAndCancelledWaiterReleasesIt() = runBlocking {
        val model = GatedThreadModel()
        limitedThreadBuilder(model, perThread = 1, total = 3).build().use { runtime ->
            val thread = runtime.startThread()
            val first = async { thread.run("first") }
            model.entered.receive()
            val queued = async { thread.run("queued") }
            runtime.awaitQueued(1)
            assertEquals("SESSION_QUEUE_FULL", withTimeout(1000) { thread.run("overflow") }.error?.code)
            assertEquals(1, model.requests.size)
            queued.cancelAndJoin()
            runtime.awaitQueued(0)
            val replacement = async { thread.run("replacement") }
            runtime.awaitQueued(1)
            model.release.send(Unit)
            first.await()
            model.entered.receive()
            model.release.send(Unit)
            assertEquals(TurnStatus.COMPLETED, replacement.await().status)
        }
    }

    @Test fun globalQueueCountsThreadWaitersAndCapacityWaitersTogether() = runBlocking {
        val model = GatedThreadModel()
        limitedThreadBuilder(model, perThread = 3, total = 2).build().use { runtime ->
            val a = runtime.startThread()
            val b = runtime.startThread()
            val c = runtime.startThread()
            val first = async { a.run("first") }
            model.entered.receive()
            val same = async { a.run("same") }
            runtime.awaitQueued(1)
            val other = async { b.run("other") }
            runtime.awaitQueued(2)
            assertEquals("RUNTIME_BUSY", withTimeout(1000) { c.run("overflow") }.error?.code)
            same.cancelAndJoin()
            val replacement = async { c.run("replacement") }
            runtime.awaitQueued(2)
            model.release.send(Unit)
            first.await()
            repeat(2) { model.entered.receive(); model.release.send(Unit) }
            other.await(); replacement.await()
            assertEquals(3, model.requests.size)
        }
    }

    @Test fun cancelledExecutionKeepsItsPermitUntilTerminalCleanupFinishes() = runBlocking {
        val model = GatedThreadModel()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        limitedThreadBuilder(model).build().use { runtime ->
            (runtime as DefaultThreadRuntime).engine.beforeCleanupForTest = {
                cleanupEntered.complete(Unit)
                runBlocking { releaseCleanup.await() }
            }
            val first = launch { runtime.startThread().run("cancel me") }
            model.entered.receive()
            first.cancel()
            cleanupEntered.await()
            val next = async { runtime.startThread().run("next") }
            runtime.awaitQueued(1)
            assertEquals(1, model.requests.size)
            releaseCleanup.complete(Unit)
            first.join()
            model.entered.receive()
            model.release.send(Unit)
            assertEquals(TurnStatus.COMPLETED, next.await().status)
        }
    }

    @Test fun sharedStorageSharesQueueLimitsAndClosingWaiterRuntimeReleasesCapacity() = runBlocking {
        val root = threadRoot()
        val model = GatedThreadModel()
        limitedThreadBuilder(model, root, perThread = 1, total = 1).build().use { a ->
            val b = threadBuilder(ThreadScriptModel(emptyList()), root).build()
            try {
                val firstThread = a.startThread(ThreadOptions(threadId = "shared"))
                val active = async { firstThread.run("active") }
                model.entered.receive()
                val waiting = async { b.resumeThread("shared").run("waiting") }
                a.awaitQueued(1)
                assertEquals("SESSION_QUEUE_FULL", firstThread.run("overflow").error?.code)
                assertEquals("RUNTIME_BUSY", a.startThread().run("global overflow").error?.code)
                b.close()
                assertEquals("RUNTIME_CLOSED", waiting.await().error?.code)
                a.awaitQueued(0)
                model.release.send(Unit)
                active.await()
                Unit
            } finally { b.close() }
        }
    }
}

internal suspend fun AgentRuntime.awaitQueued(count: Int) = withTimeout(5000) {
    while ((this@awaitQueued as DefaultThreadRuntime).admission.queuedCount() != count) yield()
}

@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.model.ModelEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test

class ThreadChildDeletionTest {
    @Test fun aGrandchildCallbackCannotSynchronouslyDeleteItsAncestorConversation() = runBlocking {
        val subagents = Subagents(config = SubagentConfig(maxDepth = 2), launcher = { flow {
            emit(ThreadEvent.TurnCompleted(TurnResult("child", TurnStatus.COMPLETED, "report", emptyList())))
        } })
        threadBuilder(ThreadScriptModel(listOf(finishEvents("ready"), finishEvents("usable"))))
            .subagents(subagents).build().use { runtime ->
                val thread = runtime.startThread(ThreadOptions(threadId = "parent"))
                thread.run("hello")
                subagents.execute("spawn_agent", """{"task":"child"}""", SubagentCall("parent", "parent-run", null))
                withTimeout(5000) { while (subagents.statuses("parent").none { it.state.finished }) yield() }
                val childSession = subagents.statuses("parent").single().sessionId
                subagents.execute("spawn_agent", """{"task":"grandchild"}""", SubagentCall(childSession, "child-run", null))
                withTimeout(5000) { while (subagents.statuses(childSession).none { it.state.finished }) yield() }
                subagents.runEnded("child-run")
                var failure: Throwable? = null
                subagents.onReport { failure = runCatching { runBlocking { runtime.deleteThread("parent") } }.exceptionOrNull() }
                assertTrue(failure is IllegalStateException)
                assertTrue(failure?.message.orEmpty().contains("subagent callback"))
                assertEquals(TurnStatus.COMPLETED, thread.run("again").status)
                runtime.deleteThread("parent")
                assertTrue(runtime.listThreads().isEmpty())
            }
    }

    @Test fun synchronousDeletionFromAReportCallbackIsRejectedBeforeInvalidatingTheThread() = runBlocking {
        val finished = CompletableDeferred<Unit>()
        val subagents = Subagents(launcher = { flow {
            emit(ThreadEvent.TurnCompleted(TurnResult("child", TurnStatus.COMPLETED, "old report", emptyList())))
            finished.complete(Unit)
        } })
        threadBuilder(ThreadScriptModel(listOf(finishEvents("ready"), finishEvents("still usable"))))
            .subagents(subagents).build().use { runtime ->
                val thread = runtime.startThread(ThreadOptions(threadId = "parent"))
                thread.run("hello")
                subagents.execute("spawn_agent", """{"task":"work"}""", SubagentCall("parent", "parent-run", null))
                withTimeout(5000) { finished.await() }
                subagents.runEnded("parent-run")
                var failure: Throwable? = null
                subagents.onReport { failure = runCatching { runBlocking { runtime.deleteThread("parent") } }.exceptionOrNull() }
                assertTrue(failure is IllegalStateException)
                assertTrue(failure?.message.orEmpty().contains("subagent callback"))
                assertEquals(TurnStatus.COMPLETED, thread.run("again").status)
                runtime.deleteThread("parent")
                assertTrue(runtime.listThreads().isEmpty())
            }
    }

    @Test fun deletionWaitsForQueuedReportDeliveryOnAnUnrelatedRegistrationThread() = runBlocking {
        val finished = CompletableDeferred<Unit>()
        val subagents = Subagents(launcher = { flow {
            emit(ThreadEvent.TurnCompleted(TurnResult("child", TurnStatus.COMPLETED, "old report", emptyList())))
            finished.complete(Unit)
        } })
        val parent = SubagentCall("parent", "old-run", null)
        subagents.execute("spawn_agent", """{"task":"old task"}""", parent)
        withTimeout(5000) { finished.await() }
        subagents.runEnded(parent.runId)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val registration = pool.submit { subagents.onReport {
                entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            } }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val deletion = async { subagents.drainSession("parent") }
            assertNull(withTimeoutOrNull(100) { deletion.await(); "deleted" })
            release.countDown()
            withTimeout(5000) { deletion.await() }
            registration.get(5, java.util.concurrent.TimeUnit.SECONDS)
            val recreatedReports = mutableListOf<SubagentReport>()
            subagents.onReport { recreatedReports += it }
            subagents.execute("spawn_agent", """{"task":"new task"}""", SubagentCall("parent", "new-run", null))
            withTimeout(5000) { while (subagents.statuses("parent").none { it.state.finished }) yield() }
            subagents.runEnded("new-run")
            assertEquals(listOf("new task"), recreatedReports.flatMap { it.statuses }.map { it.task })
        } finally { release.countDown(); pool.shutdownNow(); subagents.drainSession("parent") }
    }

    @Test fun deletionWaitsForLateChildCleanupAndItsReportCannotReachTheRecreatedThread() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val reports = java.util.Collections.synchronizedList(mutableListOf<SubagentReport>())
        val subagents = Subagents(launcher = { flow {
            entered.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) {
                cleaning.complete(Unit); releaseCleanup.await(); cleaned.complete(Unit)
            } }
        } })
        subagents.onReport { reports += it }
        val model = ThreadScriptModel(listOf(
            listOf(ModelEvent.ToolCall("child", "spawn_agent", """{"task":"background work"}"""), ModelEvent.Completed),
            finishEvents("started"), finishEvents("fresh"),
        ))
        threadBuilder(model).subagents(subagents).build().use { runtime ->
            runtime.startThread(ThreadOptions(threadId = "parent")).run("delegate")
            withTimeout(5000) { entered.await() }
            val deleting = async { runtime.deleteThread("parent") }
            withTimeout(5000) { cleaning.await() }
            assertFalse(deleting.isCompleted)
            releaseCleanup.complete(Unit)
            withTimeout(5000) { deleting.await() }
            assertTrue(cleaned.isCompleted)
            assertFalse(subagents.hasUnfinished("parent"))
            assertEquals(TurnStatus.COMPLETED, runtime.startThread(ThreadOptions(threadId = "parent")).run("new conversation").status)
            assertTrue(reports.isEmpty())
        }
    }

    @Test fun deletionDiscardsReportsQueuedBeforeAListenerWasAttached() = runBlocking {
        val completed = CompletableDeferred<Unit>()
        val subagents = Subagents(launcher = { spec -> flow {
            emit(ThreadEvent.TurnCompleted(TurnResult("child-turn", TurnStatus.COMPLETED, "old report", emptyList())))
            completed.complete(Unit)
        } })
        val parent = SubagentCall("parent", "parent-run", null)
        subagents.execute("spawn_agent", """{"task":"finish quickly"}""", parent)
        withTimeout(5000) { completed.await() }
        subagents.runEnded(parent.runId)
        subagents.drainSession("parent")
        val reports = mutableListOf<SubagentReport>()
        subagents.onReport { reports += it }
        assertTrue(reports.isEmpty())
        assertTrue(subagents.statuses("parent").isEmpty())
    }
}

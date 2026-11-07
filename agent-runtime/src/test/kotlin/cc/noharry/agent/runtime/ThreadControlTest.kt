@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.SessionRunConflictPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class ThreadControlTest {
    @Test fun busyTurnFailsWithoutReplacingTheActiveTurn() = runBlocking {
        approvalBuilder().build().use { runtime ->
            val thread = runtime.startThread()
            val first = async { thread.runStreamed("set temperature", TurnOptions(turnId = "active")).toList() }
            withTimeout(5000) { while (thread.pendingRequests().isEmpty()) yield() }
            val failed = withTimeout(1000) { thread.run("another request", TurnOptions(onConflict = SessionRunConflictPolicy.RESUME_CURRENT)) }
            assertEquals("THREAD_BUSY", failed.error?.code)
            assertNotEquals("active", failed.turnId)
            assertEquals(ThreadStatus.WAITING_FOR_APPROVAL, thread.status())
            (thread.pendingRequests().single() as Approval).approve()
            assertEquals(TurnStatus.COMPLETED, (first.await().last() as ThreadEvent.TurnCompleted).result.status)
        }
    }

    @Test fun cancelAndReplaceInterruptsTheOwnerInAnotherRuntime() = runBlocking {
        val root = threadRoot()
        approvalBuilder(root).build().use { first ->
            threadBuilder(ThreadScriptModel(listOf(finishEvents("replacement"))), root).build().use { second ->
                val a = first.startThread(ThreadOptions(threadId = "replace"))
                val b = second.resumeThread("replace")
                val original = async { a.runStreamed("set temperature").toList() }
                withTimeout(5000) { while (b.pendingRequests().isEmpty()) yield() }
                val pending = b.pendingRequests().single() as Approval
                val replacement = b.run("do a new task", TurnOptions(onConflict = SessionRunConflictPolicy.CANCEL_AND_REPLACE))
                assertEquals("replacement", replacement.finalResponse)
                assertEquals(RespondResult.NOT_FOUND, pending.approve())
                assertEquals(TurnStatus.INTERRUPTED, (original.await().last() as ThreadEvent.TurnCompleted).result.status)
            }
        }
    }

    @Test fun resumingUpdatesExistingHandleAndPersistsNewInstructions() = runBlocking {
        val root = threadRoot()
        val model = ThreadScriptModel(listOf(finishEvents("first"), finishEvents("second")))
        threadBuilder(model, root).build().use { runtime ->
            val thread = runtime.startThread(ThreadOptions(threadId = "options", instructions = "ORIGINAL_INSTRUCTIONS"))
            thread.run("one")
            runtime.resumeThread("options", ThreadOptions(instructions = "UPDATED_INSTRUCTIONS"))
            thread.run("two")
            assertTrue(model.requests.last().system.orEmpty().contains("UPDATED_INSTRUCTIONS"))
            assertFalse(model.requests.last().system.orEmpty().contains("ORIGINAL_INSTRUCTIONS"))
        }
        val restored = ThreadScriptModel(listOf(finishEvents("third")))
        threadBuilder(restored, root).build().use { runtime ->
            runtime.resumeThread("options").run("three")
            assertTrue(restored.requests.single().system.orEmpty().contains("UPDATED_INSTRUCTIONS"))
        }
    }
}

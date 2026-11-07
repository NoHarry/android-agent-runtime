@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.SessionEventQuery
import cc.noharry.agent.spi.reliability.*
import cc.noharry.agent.runtime.thread.ThreadStore
import cc.noharry.agent.runtime.reliability.FileBackedReliabilityStorage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ThreadExecutionIdentityTest {
    @Test fun identicalTurnIdsInDifferentThreadsHaveIndependentExecutionsAndDiagnostics() = runBlocking {
        val root = threadRoot()
        val model = ThreadScriptModel(listOf(finishEvents("first"), finishEvents("second")))
        val starts = mutableListOf<HookContext>()
        val finishes = mutableListOf<HookContext>()
        val ids = mutableMapOf<String, String>()
        threadBuilder(model, root).hook(object : RuntimeHook {
            override suspend fun onTurnStarted(context: HookContext) { starts += context }
            override suspend fun onTurnFinished(result: TurnResult, context: HookContext) { finishes += context }
        }).build().use { runtime ->
            listOf("a", "b").forEach { threadId ->
                val thread = runtime.startThread(ThreadOptions(threadId = threadId))
                val result = thread.run("hello", TurnOptions(turnId = "same"))
                assertEquals(TurnStatus.COMPLETED, result.status)
                assertTrue(result.items.all { it.turnId == "same" })
                val snapshot = checkNotNull(runtime.diagnostics.getRunState(threadId, "same"))
                ids[threadId] = snapshot.runId
                assertEquals(threadId, snapshot.sessionId)
                assertNotEquals("same", snapshot.runId)
                val events = runtime.diagnostics.replaySessionEvents(threadId, "same")
                assertTrue(events.isNotEmpty())
                assertTrue(events.all { it.runId == snapshot.runId && it.requestId == "same" })
                assertEquals(events, runtime.diagnostics.querySessionEvents(SessionEventQuery(threadId, requestId = "same")))
                assertEquals(result, thread.run("duplicate", TurnOptions(turnId = "same")))
            }
            assertNotEquals(ids["a"], ids["b"])
            assertEquals(2, model.requests.size)
            assertEquals(starts, finishes)
            assertTrue(starts.all { it.requestId == "same" && it.runId == ids[it.sessionId] })
        }
        threadBuilder(ThreadScriptModel(emptyList()), root).build().use { runtime ->
            ids.forEach { (threadId, executionId) ->
                assertEquals(executionId, runtime.diagnostics.getRuntimeResult(threadId, "same")?.runId)
            }
            assertNull(runtime.diagnostics.getRunState("missing", "same"))
        }
    }

    @Test fun unsavedAttemptIsCancelledAndSameTurnStartsFreshAfterRestart() = runBlocking {
        val root = threadRoot()
        val storageRoot = File(root, "storage")
        val store = ThreadStore(storageRoot)
        store.create("crashed", ThreadOptions(threadId = "crashed"))
        store.attempts.begin("crashed", "retry", "old-execution")
        val reliability = FileBackedReliabilityStorage(File(storageRoot, "runtime"))
        reliability.saveRun(
            RuntimeRunSnapshot("old-execution", "crashed", RuntimeRunStatus.RUNNING, 1),
            RuntimeRunEnvelope("old-execution", "retry", "crashed", RuntimeRunStatus.RUNNING,
                RuntimeRunPhase.MODEL_RUNNING, 1, 1),
        )
        val model = ThreadScriptModel(listOf(finishEvents("recovered")))
        threadBuilder(model, root).build().use { runtime ->
            val thread = runtime.resumeThread("crashed")
            val result = thread.run("retry", TurnOptions(turnId = "retry"))
            assertEquals(TurnStatus.COMPLETED, result.status)
            val latest = checkNotNull(store.attempts.latest("crashed", "retry"))
            assertEquals(2, latest.attempt)
            assertFalse(latest.active)
            assertNotEquals("old-execution", latest.executionId)
            assertEquals(latest.executionId, runtime.diagnostics.getRunState("crashed", "retry")?.runId)
            assertEquals(RuntimeRunStatus.CANCELLED, reliability.readSnapshot("old-execution")?.status)
            assertEquals("INTERRUPTED", reliability.readResult("old-execution")?.errorCode)
            assertEquals(result, thread.run("again", TurnOptions(turnId = "retry")))
            assertEquals(1, model.requests.size)
        }
    }

    @Test fun savedResultWithUnclosedAttemptMarkerPreservesItsDiagnostics() = runBlocking {
        val root = threadRoot()
        val store = ThreadStore(File(root, "storage"))
        threadBuilder(ThreadScriptModel(listOf(finishEvents("original answer"))), root).build().use { runtime ->
            runtime.startThread(ThreadOptions(threadId = "saved")).run("hello", TurnOptions(turnId = "done"))
            val executionId = checkNotNull(store.attempts.latest("saved", "done")).executionId
            store.attempts.begin("saved", "done", executionId)
        }
        threadBuilder(ThreadScriptModel(listOf(finishEvents("new answer"))), root).build().use { runtime ->
            runtime.resumeThread("saved").run("new", TurnOptions(turnId = "next"))
            val previous = checkNotNull(runtime.diagnostics.getRuntimeResult("saved", "done"))
            assertEquals(RuntimeRunStatus.COMPLETED, previous.status)
            assertEquals("original answer", previous.response)
            assertFalse(checkNotNull(store.attempts.latest("saved", "done")).active)
        }
    }
}

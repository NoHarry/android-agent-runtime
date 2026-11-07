@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.reliability.*
import cc.noharry.agent.runtime.reliability.FileBackedReliabilityStorage
import cc.noharry.agent.runtime.thread.ThreadStore
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SessionSchedulingRestartIntegrationTest {
    @Test fun abandonedWaitingAndQueuedAttemptsAreCancelledWithoutReplayingTheirWork() = runSchedulingTest {
        val root = threadRoot()
        val storage = File(root, "storage")
        val store = ThreadStore(storage)
        store.create(SESSION_A, ThreadOptions(threadId = SESSION_A))
        val reliability = FileBackedReliabilityStorage(File(storage, "runtime"))
        val stale = listOf(RuntimeRunStatus.WAITING_CONFIRMATION, RuntimeRunStatus.QUEUED)
        stale.forEachIndexed { index, status ->
            val execution = "abandoned-$index"
            store.attempts.begin(SESSION_A, "old-$index", execution)
            val phase = if (status == RuntimeRunStatus.QUEUED) RuntimeRunPhase.QUEUED else RuntimeRunPhase.WAITING_CONFIRMATION
            reliability.saveRun(RuntimeRunSnapshot(execution, SESSION_A, status, 1),
                RuntimeRunEnvelope(execution, "old-$index", SESSION_A, status, phase, 1, 1))
        }
        val model = ThreadScriptModel(listOf(finishEvents("fresh")))
        threadBuilder(model, root).build().use { runtime ->
            val thread = runtime.resumeThread(SESSION_A)
            assertTrue(thread.pendingRequests().isEmpty())
            assertEquals("fresh", thread.run("fresh request", TurnOptions(turnId = "new")).finalResponse)
            assertEquals(1, model.requests.size)
            assertEquals(listOf("fresh request"), model.requests.single().messages.filter { it.role == "user" }.map { it.content })
            stale.indices.forEach { index ->
                assertEquals(RuntimeRunStatus.CANCELLED, reliability.readSnapshot("abandoned-$index")?.status)
                assertEquals("INTERRUPTED", reliability.readResult("abandoned-$index")?.errorCode)
                assertFalse(checkNotNull(store.attempts.latest(SESSION_A, "old-$index")).active)
            }
        }
    }
}

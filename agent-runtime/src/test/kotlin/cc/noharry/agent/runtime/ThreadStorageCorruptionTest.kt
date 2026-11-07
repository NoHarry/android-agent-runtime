package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.kernel.core.context.SessionHistoryPersistence
import cc.noharry.agent.kernel.core.context.SessionHistoryStore
import cc.noharry.agent.kernel.core.context.StoredSessionHistory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ThreadStorageCorruptionTest {
    @Test fun listingSkipsInvalidAndUnsupportedMetadata() = runBlocking {
        val root = threadRoot()
        threadBuilder(ThreadScriptModel(emptyList()), root).build().use { runtime ->
            runtime.startThread(ThreadOptions(threadId = "valid"))
            File(root, "storage/broken.thread.json").writeText("{invalid")
            File(root, "storage/future.thread.json").writeText("""{"version":999}""")
            assertEquals(listOf("valid"), runtime.listThreads().map { it.id })
        }
    }

    @Test fun damagedModelHistoryIsQuarantinedAndTheThreadContinues() = runBlocking {
        for (damaged in listOf("{invalid", """{"version":999,"runs":[]}""")) {
            val root = savedHistory()
            val history = historyFile(root)
            history.writeText(damaged)
            val model = ThreadScriptModel(listOf(finishEvents("new answer")))
            threadBuilder(model, root).build().use { runtime ->
                val result = runtime.resumeThread("history").run("new input")
                assertEquals(TurnStatus.COMPLETED, result.status)
                assertFalse(model.requests.single().messages.any { "old input" in it.content })
                assertEquals(damaged, File(history.path + ".corrupt").readText())
                assertTrue(history.isFile)
            }
        }
    }

    @Test fun deleteDoesNotNeedToReadDamagedModelHistory() = runBlocking {
        val root = savedHistory()
        val history = historyFile(root)
        history.writeText("{invalid")
        threadBuilder(ThreadScriptModel(emptyList()), root).build().use { runtime ->
            runtime.deleteThread("history")
            assertTrue(runtime.listThreads().isEmpty())
            assertFalse(history.exists())
            assertFalse(File(history.path + ".corrupt").exists())
        }
    }

    @Test fun deletionNeverLoadsAPersistedHistory() {
        val deleted = mutableListOf<String>()
        val persistence = object : SessionHistoryPersistence {
            override fun load(sessionId: String): StoredSessionHistory? = error("History must not be read when deleting")
            override fun save(sessionId: String, history: StoredSessionHistory) = Unit
            override fun delete(sessionId: String) { deleted += sessionId }
            override fun clear() = Unit
        }
        SessionHistoryStore(persistence = persistence).clearSession("damaged")
        assertEquals(listOf("damaged"), deleted)
    }

    private suspend fun savedHistory(): File = threadRoot().also { root ->
        threadBuilder(ThreadScriptModel(listOf(finishEvents("old answer"))), root).build().use { runtime ->
            runtime.startThread(ThreadOptions(threadId = "history")).run("old input")
        }
    }

    private fun historyFile(root: File): File = File(root, "storage").listFiles().orEmpty().single { it.name.endsWith(".history.json") }
}

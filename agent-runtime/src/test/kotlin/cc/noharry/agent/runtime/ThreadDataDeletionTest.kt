@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.*
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.runtime.eventlog.InMemorySessionEventLog
import cc.noharry.agent.runtime.eventlog.openFileBackedSessionEventLog
import cc.noharry.agent.runtime.thread.DefaultThreadRuntime
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ThreadDataDeletionTest {
    @Test fun aDeletedThreadsSavedPlanCannotReturnWhenItsIdIsReused() = runBlocking {
        val root = threadRoot()
        val model = ThreadScriptModel(listOf(
            listOf(ModelEvent.ToolCall("todo", "update_todo", """{"items":[{"text":"private old plan","status":"done"}]}"""), ModelEvent.Completed),
            finishEvents("saved"), finishEvents("fresh"),
        ))
        threadBuilder(model, root).build().use { runtime ->
            assertEquals(TurnStatus.COMPLETED, runtime.startThread(ThreadOptions(threadId = "plan")).run("plan something").status)
            assertTrue(File(root, "workspace").walkTopDown().any { it.isFile && it.parentFile.name == "todo" })
            runtime.deleteThread("plan")
            runtime.deleteThread("plan")
            runtime.startThread(ThreadOptions(threadId = "plan")).run("hello")
            val request = model.requests.last()
            assertFalse(request.system.orEmpty().contains("private old plan"))
            assertFalse(request.messages.any { it.content.contains("private old plan") })
            assertFalse(File(root, "workspace").walkTopDown().any { it.isFile && it.parentFile.name == "todo" })
        }
    }

    @Test fun deletionClearsAllRuntimeCopiesAndDiskArtifactsButPreservesTheWorkspace() = runBlocking {
        val root = threadRoot()
        val first = threadBuilder(ThreadScriptModel(listOf(finishEvents("first"))), root).build() as DefaultThreadRuntime
        val second = threadBuilder(ThreadScriptModel(listOf(finishEvents("second"))), root).build() as DefaultThreadRuntime
        try {
            first.startThread(ThreadOptions(threadId = "removed")).run("first", TurnOptions(turnId = "one"))
            second.resumeThread("removed").run("second", TurnOptions(turnId = "two"))
            val one = checkNotNull(first.diagnostics.getRuntimeResult("removed", "one"))
            val two = checkNotNull(second.diagnostics.getRuntimeResult("removed", "two"))
            val evidence = one.evidenceIds + two.evidenceIds
            assertTrue(evidence.isNotEmpty())
            assertTrue(first.diagnostics.querySessionEvents(SessionEventQuery("removed")).isNotEmpty())
            val retained = File(root, "workspace/keep.txt").apply { parentFile.mkdirs(); writeText("shared workspace") }
            val storage = File(root, "storage")
            val history = storage.listFiles().orEmpty().single { it.name.endsWith(".history.json") }
            File(history.path + ".corrupt").writeText("private damaged history")
            val attachment = addAttachment(File(storage, "events"))
            second.deleteThread("removed")
            for (runtime in listOf(first, second)) {
                assertTrue(runtime.listThreads().isEmpty())
                assertTrue(runtime.diagnostics.querySessionEvents(SessionEventQuery("removed")).isEmpty())
                evidence.forEach { assertNull(runtime.diagnostics.getRuntimeEvidence(it)) }
                listOf(one.runId, two.runId).forEach { assertNull(runtime.engine.getRuntimeResult(it)); assertNull(runtime.engine.getRunState(it)) }
                assertNull(runtime.engine.readThreadAttachment("removed", attachment))
            }
            assertFalse(storage.listFiles().orEmpty().any { it.name.endsWith(".corrupt") || it.name.endsWith(".thread.json") || it.name.endsWith(".attempts.json") })
            assertTrue(File(storage, "runtime/runs").listFiles().orEmpty().isEmpty())
            assertTrue(File(storage, "runtime/evidence").listFiles().orEmpty().isEmpty())
            assertEquals("shared workspace", retained.readText())
            assertEquals(TurnStatus.COMPLETED, first.startThread(ThreadOptions(threadId = "removed")).run("fresh", TurnOptions(turnId = "one")).status)
        } finally { first.close(); second.close() }
    }

    @Test fun unsupportedCustomEventLogFailsDeletionExplicitly() = runBlocking {
        val memory = InMemorySessionEventLog()
        val unsupported = object : SessionEventLog {
            override fun append(event: SessionEventAppend) = memory.append(event)
            override fun query(query: SessionEventQuery) = memory.query(query)
            override fun replay(sessionId: String, runId: String) = memory.replay(sessionId, runId)
        }
        threadBuilder(ThreadScriptModel(listOf(finishEvents("answer")))).sessionEventLog(unsupported).build().use { runtime ->
            runtime.startThread(ThreadOptions(threadId = "kept")).run("hello")
            val failure = runCatching { runtime.deleteThread("kept") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("does not support"))
            assertEquals(listOf("kept"), runtime.listThreads().map { it.id })
        }
    }

    private fun addAttachment(root: File): String {
        val bytes = "attachment content".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val log = openFileBackedSessionEventLog(root)
        assertTrue(log.putAttachment("removed", SessionAttachmentRef(sha, "image/png", bytes.size.toLong(), "image"), bytes))
        (log as? AutoCloseable)?.close()
        return sha
    }
}

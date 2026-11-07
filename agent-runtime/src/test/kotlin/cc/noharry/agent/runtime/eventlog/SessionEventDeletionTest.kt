@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.spi.*
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test

class SessionEventDeletionTest {
    @Test fun inMemoryDeletionRemovesOnlyTheSelectedThreadAndCanRepeat() {
        val log = InMemorySessionEventLog()
        val removed = log.append(event("removed"))
        val kept = log.append(event("kept"))
        assertTrue(log.deleteThread("removed"))
        assertTrue(log.deleteThread("removed"))
        assertTrue(log.query(SessionEventQuery("removed")).isEmpty())
        assertEquals(listOf(kept), log.query(SessionEventQuery("kept")))
        assertEquals(SessionEventLogHealthStatus.UNKNOWN, log.health("removed", removed.runId).status)
        assertEquals(1L, log.append(event("removed")).sequence)
    }

    @Test fun diskDeletionClearsAttachmentsAndCachesInEveryOpenReader() {
        val root = Files.createTempDirectory("event-delete-").toFile()
        val first = FileBackedSessionEventLog(root)
        val other = FileBackedSessionEventLog(root)
        val removed = first.append(event("removed"))
        val kept = first.append(event("kept"))
        val bytes = "image data".toByteArray()
        val ref = SessionAttachmentRef(sha256Hex(bytes), "image/png", bytes.size.toLong(), "image")
        assertTrue(first.putAttachment("removed", ref, bytes))
        other.replay("removed", "run")
        other.health("removed", "run")
        assertTrue(first.deleteThread("removed"))
        assertTrue(first.deleteThread("removed"))
        assertTrue(other.query(SessionEventQuery("removed")).isEmpty())
        assertNull(other.readAttachment("removed", ref.sha256))
        assertEquals(SessionEventLogHealthStatus.UNKNOWN, other.health("removed", "run").status)
        assertFalse(root.walkTopDown().filter(File::isFile).any { it.readText().contains(removed.eventId) })
        assertEquals(listOf(kept), other.replay("kept", "run"))
        assertEquals(1L, other.append(event("removed")).sequence)
    }

    @Test fun deletionWaitsForAcceptedAsyncWritesAndTheyCannotResurrectTheThread() {
        val root = Files.createTempDirectory("event-delete-async-").toFile()
        val durable = FileBackedSessionEventLog(root)
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val log = AsyncSessionEventLog(durable, persistBatch = {
            writing.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            durable.appendAllocatedBatch(it)
        })
        val pool = Executors.newSingleThreadExecutor()
        try {
            log.append(event("removed"))
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            log.append(event("removed"))
            val deletion = pool.submit<Boolean> { log.deleteThread("removed") }
            assertFalse(deletion.isDone)
            release.countDown()
            assertTrue(deletion.get(5, TimeUnit.SECONDS))
            assertTrue(log.replay("removed", "run").isEmpty())
            log.close()
            assertTrue(FileBackedSessionEventLog(root).query(SessionEventQuery("removed")).isEmpty())
        } finally { release.countDown(); log.close(); pool.shutdownNow() }
    }

    @Test fun aWriterWhoseWritesFailedDoesNotBlockDeletion() {
        val root = Files.createTempDirectory("event-delete-failed-writer-").toFile()
        val durable = FileBackedSessionEventLog(root)
        durable.append(event("removed"))
        val failing = AsyncSessionEventLog(durable, persistBatch = { error("disk full") })
        try {
            failing.append(event("removed"))
            assertFalse(failing.flush())
            assertTrue(failing.deleteThread("removed"))
            assertTrue(durable.query(SessionEventQuery("removed")).isEmpty())
        } finally {
            failing.close()
            root.deleteRecursively()
        }
    }

    @Test fun subscriberDropsAnOldPageAndReceivesEventsFromTheRecreatedThread() {
        val root = Files.createTempDirectory("event-delete-subscriber-").toFile()
        val log = AsyncSessionEventLog(FileBackedSessionEventLog(root))
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val recreated = CountDownLatch(1)
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val subscription = log.subscribe { value ->
            if (value.requestId == "old") { blocked.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            seen += value.requestId
            if (value.requestId == "new") recreated.countDown()
        }
        try {
            log.append(event("removed", "old"))
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            log.append(event("removed", "stale"))
            assertTrue(log.deleteThread("removed"))
            assertEquals(1L, log.append(event("removed", "new")).sequence)
            release.countDown()
            assertTrue(recreated.await(5, TimeUnit.SECONDS))
            assertFalse(seen.contains("stale"))
            assertEquals(1, seen.count { it == "new" })
        } finally { release.countDown(); subscription.close(); log.close() }
    }

    @Test fun deletingThroughAnotherAsyncOrRawHandleDrainsEveryWriterAtThatRoot() {
        for (raw in listOf(false, true)) deleteThroughAnotherHandle(raw)
    }

    private fun deleteThroughAnotherHandle(raw: Boolean) {
        val root = Files.createTempDirectory("event-delete-shared-").toFile()
        val durable = FileBackedSessionEventLog(root)
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AsyncSessionEventLog(durable, persistBatch = {
            writing.countDown(); check(release.await(5, TimeUnit.SECONDS)); durable.appendAllocatedBatch(it)
        })
        val second = AsyncSessionEventLog(FileBackedSessionEventLog(root))
        val deleting: SessionEventLog = if (raw) FileBackedSessionEventLog(root) else second
        val pool = Executors.newSingleThreadExecutor()
        try {
            first.append(event("removed"))
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            val deletion = pool.submit<Boolean> { deleting.deleteThread("removed") }
            assertThrows(TimeoutException::class.java) { deletion.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            assertTrue(deletion.get(5, TimeUnit.SECONDS))
            assertTrue(first.replay("removed", "run").isEmpty())
            assertEquals(1L, first.append(event("removed", "new")).sequence)
            assertTrue(first.flush())
            assertEquals(listOf("new"), second.replay("removed", "run").map { it.requestId })
        } finally { release.countDown(); first.close(); second.close(); pool.shutdownNow() }
    }

    @Test fun deletionDoesNotRepeatAnUnrelatedThreadsSlowSubscriberEvent() {
        val root = Files.createTempDirectory("event-delete-unrelated-").toFile()
        val log = AsyncSessionEventLog(FileBackedSessionEventLog(root))
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(2)
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val subscription = log.subscribe { value ->
            if (value.requestId == "one") { blocked.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            seen += value.requestId
            delivered.countDown()
        }
        try {
            log.append(event("kept", "one"))
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            assertTrue(log.deleteThread("removed"))
            log.append(event("kept", "two"))
            release.countDown()
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("one", "two"), seen.toList())
        } finally { release.countDown(); subscription.close(); log.close() }
    }

    private fun event(thread: String, request: String = "turn") = SessionEventAppend(
        sessionId = thread, runId = "run", requestId = request,
        type = SessionEventType.RUN_STARTED, payload = SessionEventPayload.RunLifecycle(status = "RUNNING", scene = "scene"),
    )
}

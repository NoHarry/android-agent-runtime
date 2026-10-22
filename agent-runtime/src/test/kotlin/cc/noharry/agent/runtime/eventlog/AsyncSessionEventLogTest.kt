@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionEventAppend
import cc.noharry.agent.kernel.api.SessionEventLogHealthStatus
import cc.noharry.agent.kernel.api.SessionEventPayload
import cc.noharry.agent.kernel.api.SessionEventType
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AsyncSessionEventLogTest {
    @Test
    fun closeDrainsAcceptedEventsWithoutAnExplicitFlush() {
        val root = Files.createTempDirectory("async-eventlog-close-").toFile()
        val log = AsyncSessionEventLog(FileBackedSessionEventLog(root))
        try {
            val accepted = List(EVENT_COUNT) { log.append(event()) }
            log.close()
            val cold = FileBackedSessionEventLog(root)
            assertEquals(accepted, cold.replay(SESSION, RUN))
            assertEquals(SessionEventLogHealthStatus.COMPLETE, cold.health(SESSION, RUN).status)
        } finally {
            log.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun anotherWriterCannotClearAnExistingOpenMarkerOrAppendToItsRun() {
        val root = Files.createTempDirectory("async-eventlog-owner-").toFile()
        val first = AsyncSessionEventLog(FileBackedSessionEventLog(root))
        val second = AsyncSessionEventLog(FileBackedSessionEventLog(root))
        try {
            val accepted = first.append(event())
            assertTrue(first.flush())
            second.append(event())
            assertFalse(second.flush())
            second.close()
            val cold = FileBackedSessionEventLog(root)
            assertEquals(listOf(accepted), cold.replay(SESSION, RUN))
            assertEquals(ASYNC_WRITER_OPEN, cold.health(SESSION, RUN).firstFailureCode)
            assertEquals(SessionEventLogHealthStatus.DEGRADED, second.health(SESSION, RUN).status)
        } finally {
            second.close()
            first.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun unclosedWriterIsDegradedForColdReaderAndCleanCloseClearsMarker() {
        val root = Files.createTempDirectory("async-eventlog-restart-").toFile()
        val log = AsyncSessionEventLog(FileBackedSessionEventLog(root))
        try {
            val accepted = log.append(event())
            assertTrue(log.flush())
            val cold = FileBackedSessionEventLog(root)
            assertEquals(SessionEventLogHealthStatus.DEGRADED, cold.health(SESSION, RUN).status)
            assertEquals(ASYNC_WRITER_OPEN, cold.health(SESSION, RUN).firstFailureCode)
            log.close()
            assertEquals(listOf(accepted), cold.replay(SESSION, RUN))
            assertEquals(SessionEventLogHealthStatus.COMPLETE, cold.health(SESSION, RUN).status)
        } finally {
            log.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun blockedDiskDoesNotBlockAcceptanceAndHealthShowsGapUntilFlush() {
        val root = Files.createTempDirectory("async-eventlog-disk-").toFile()
        val durable = FileBackedSessionEventLog(root)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val log = AsyncSessionEventLog(durable, persistBatch = {
            entered.countDown()
            check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            durable.appendAllocatedBatch(it)
        })
        try {
            val first = log.append(event())
            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val second = log.append(event())
            assertEquals(2L, second.sequence)
            val gap = log.health(SESSION, RUN)
            assertEquals(SessionEventLogHealthStatus.DEGRADED, gap.status)
            assertEquals(2L, gap.lastAcceptedSequence)
            assertNull(gap.lastPersistedSequence)
            release.countDown()
            assertTrue(log.flush())
            assertEquals(listOf(first, second), log.replay(SESSION, RUN))
            assertEquals(SessionEventLogHealthStatus.COMPLETE, log.health(SESSION, RUN).status)
        } finally {
            release.countDown()
            log.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun slowSubscriberCannotBlockWriterAndCatchesUpLosslesslyFromDisk() {
        val root = Files.createTempDirectory("async-eventlog-ui-").toFile()
        val durable = FileBackedSessionEventLog(root)
        val log = AsyncSessionEventLog(durable)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(EVENT_COUNT)
        val ids = Collections.synchronizedList(mutableListOf<String>())
        val subscription = log.subscribe {
            entered.countDown()
            check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            ids += it.eventId
            delivered.countDown()
        }
        try {
            val accepted = mutableListOf(log.append(event()))
            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            repeat(EVENT_COUNT - 1) { accepted += log.append(event()) }
            assertTrue(log.flush())
            assertTrue("UI must still be held", ids.isEmpty())
            assertEquals(accepted, FileBackedSessionEventLog(root).replay(SESSION, RUN))
            release.countDown()
            assertTrue(delivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(accepted.map { it.eventId }, ids.toList())
        } finally {
            release.countDown()
            subscription.close()
            log.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun writeFailureIsNotReportedAsCompleteAndLaterAcceptanceIsRejected() {
        val root = Files.createTempDirectory("async-eventlog-failure-").toFile()
        val log = AsyncSessionEventLog(FileBackedSessionEventLog(root), persistBatch = { error("disk failed") })
        try {
            log.append(event())
            assertFalse(log.flush())
            assertEquals(SessionEventLogHealthStatus.DEGRADED, log.health(SESSION, RUN).status)
            assertEquals(1L, log.health(SESSION, RUN).lastAcceptedSequence)
            assertThrows(IllegalStateException::class.java) { log.append(event()) }
        } finally {
            log.close()
            root.deleteRecursively()
        }
    }

    private fun event() = SessionEventAppend(
        sessionId = SESSION, runId = RUN, requestId = RUN, type = SessionEventType.ASSISTANT_CHUNK,
        payload = SessionEventPayload.AssistantChunk("text", "complete chunk"),
    )
}

private const val SESSION = "async-session"
private const val RUN = "async-run"
private const val EVENT_COUNT = 65
private const val TIMEOUT_SECONDS = 20L

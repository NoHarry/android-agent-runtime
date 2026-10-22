@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionEventAppend
import cc.noharry.agent.kernel.api.SessionEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class FileBackedSessionEventIdIndexTest {
    @Test
    fun laterAppendsReuseTheSeededEventIdIndexInsteadOfRescanning() {
        val root = Files.createTempDirectory("file-event-log-id-index-").toFile()
        val log = FileBackedSessionEventLog(root, nowEpochMillis = { 100 }, eventIdFactory = incrementalIds())
        log.append(event(SessionEventType.RUN_STARTED, sessionId = SESSION_A, runId = SHARED_RUN))
        log.append(event(SessionEventType.RUN_STARTED, sessionId = SESSION_B, runId = SHARED_RUN))
        repeat(6) { log.append(event(SessionEventType.RUN_STATUS, sessionId = SESSION_B, runId = SHARED_RUN)) }

        assertEquals(1, log.debugEventIdSeedCount())
        assertEquals(7, log.replay(SESSION_B, SHARED_RUN).size)
        assertThrows(IllegalStateException::class.java) {
            FileBackedSessionEventLog(root, eventIdFactory = { "event-1" }).append(
                event(SessionEventType.RUN_STATUS, sessionId = SESSION_B, runId = SHARED_RUN),
            )
        }
    }

    @Test
    fun staleInstanceIndexRefreshesBeforeAcceptingAnExternallyPersistedEventId() {
        val root = Files.createTempDirectory("file-event-log-stale-id-index-").toFile()
        val firstIdIndex = AtomicInteger(0)
        val firstIds = listOf("event-a", "event-b")
        val first = FileBackedSessionEventLog(
            root,
            nowEpochMillis = { 100 },
            eventIdFactory = { firstIds[firstIdIndex.getAndIncrement()] },
        )
        first.append(event(SessionEventType.RUN_STARTED, sessionId = SESSION_A, runId = SHARED_RUN))

        FileBackedSessionEventLog(
            root,
            nowEpochMillis = { 200 },
            eventIdFactory = { "event-b" },
        ).append(event(SessionEventType.RUN_STARTED, sessionId = SESSION_B, runId = SHARED_RUN))

        assertThrows(IllegalStateException::class.java) {
            first.append(event(SessionEventType.RUN_STARTED))
        }
        assertEquals(2, first.debugEventIdSeedCount())
        val reopened = FileBackedSessionEventLog(root)
        assertEquals(listOf("event-a"), reopened.replay(SESSION_A, SHARED_RUN).map { it.eventId })
        assertEquals(listOf("event-b"), reopened.replay(SESSION_B, SHARED_RUN).map { it.eventId })
        assertEquals(0, first.replay(SESSION_ID, RUN_ID).size)
    }

    @Test
    fun aFreshInstanceSeedsFromThePersistedIdListWithoutDecodingEveryStream() {
        val root = Files.createTempDirectory("file-event-log-id-list-").toFile()
        val writer = FileBackedSessionEventLog(root, nowEpochMillis = { 100 }, eventIdFactory = incrementalIds())
        listOf(SESSION_A, SESSION_B, SESSION_ID).forEach { session ->
            repeat(3) { writer.append(event(SessionEventType.RUN_STATUS, sessionId = session)) }
        }
        assertEquals(1, writer.debugEventIdFullScanCount())

        // A host that builds a runtime per run opens a new instance each time.
        val next = FileBackedSessionEventLog(root, nowEpochMillis = { 200 }, eventIdFactory = { "fresh-1" })
        next.append(event(SessionEventType.RUN_STARTED, runId = "run-2"))
        assertEquals(1, next.debugEventIdSeedCount())
        assertEquals(0, next.debugEventIdFullScanCount())
        assertThrows(IllegalStateException::class.java) {
            FileBackedSessionEventLog(root, eventIdFactory = { "event-4" }).append(event(SessionEventType.RUN_STATUS))
        }
    }

    @Test
    fun anOutOfDateOrTornIdListIsRebuiltByDecodingEveryStream() {
        listOf<(File) -> Unit>(
            // Another writer moved the generation without updating the list.
            { root -> File(root, EVENT_ID_GENERATION_FILE).writeText("41") },
            // A crash tore the last batch.
            { root -> File(root, EVENT_ID_LIST_FILE).appendText("event-9") },
            // The list is gone.
            { root -> File(root, EVENT_ID_LIST_FILE).delete() },
        ).forEach { damage ->
            val root = Files.createTempDirectory("file-event-log-stale-id-list-").toFile()
            FileBackedSessionEventLog(root, nowEpochMillis = { 100 }, eventIdFactory = incrementalIds()).apply {
                repeat(2) { append(event(SessionEventType.RUN_STATUS)) }
            }
            damage(root)

            val reopened = FileBackedSessionEventLog(root, eventIdFactory = { "event-2" })
            assertThrows(IllegalStateException::class.java) { reopened.append(event(SessionEventType.RUN_STATUS)) }
            assertEquals(1, reopened.debugEventIdFullScanCount())
            // The rebuilt list serves the next instance again.
            val after = FileBackedSessionEventLog(root, eventIdFactory = { "event-3" })
            after.append(event(SessionEventType.RUN_STATUS))
            assertEquals(0, after.debugEventIdFullScanCount())
            assertEquals(listOf("event-1", "event-2", "event-3"), after.replay(SESSION_ID, RUN_ID).map { it.eventId })
        }
    }

    @Test
    fun anIdTheListCannotHoldDropsTheListInsteadOfCorruptingIt() {
        val root = Files.createTempDirectory("file-event-log-unlistable-id-").toFile()
        FileBackedSessionEventLog(root, nowEpochMillis = { 100 }, eventIdFactory = { "#odd" }).append(event(SessionEventType.RUN_STARTED))
        val reopened = FileBackedSessionEventLog(root, eventIdFactory = { "#odd" })
        assertThrows(IllegalStateException::class.java) { reopened.append(event(SessionEventType.RUN_STATUS)) }
        assertEquals(1, reopened.debugEventIdFullScanCount())
    }

    @Test
    fun aDroppedListIsNotRestartedByTheNextBatch() {
        val root = Files.createTempDirectory("file-event-log-dropped-id-list-").toFile()
        val ids = ArrayDeque(listOf("#odd", "event-2"))
        FileBackedSessionEventLog(root, nowEpochMillis = { 100 }, eventIdFactory = { ids.removeFirst() }).apply {
            repeat(2) { append(event(SessionEventType.RUN_STATUS)) }
        }

        // A list holding only "event-2" would read as complete and let "#odd" in twice.
        val reopened = FileBackedSessionEventLog(root, eventIdFactory = { "#odd" })
        assertThrows(IllegalStateException::class.java) { reopened.append(event(SessionEventType.RUN_STATUS)) }
        assertEquals(1, reopened.debugEventIdFullScanCount())
    }

    @Test
    fun invalidEventIdGenerationFailsClosedWithoutAppending() {
        listOf("corrupt", "-1", Long.MAX_VALUE.toString()).forEach { generation ->
            val root = Files.createTempDirectory("file-event-log-invalid-generation-").toFile()
            FileBackedSessionEventLog(
                root,
                nowEpochMillis = { 100 },
                eventIdFactory = { "event-a" },
            ).append(event(SessionEventType.RUN_STARTED))
            File(root, EVENT_ID_GENERATION_FILE).writeText(generation)

            val reopened = FileBackedSessionEventLog(root, eventIdFactory = { "event-b" })
            assertThrows(generation, IllegalStateException::class.java) {
                reopened.append(event(SessionEventType.RUN_STATUS))
            }
            assertEquals(generation, listOf("event-a"), reopened.replay(SESSION_ID, RUN_ID).map { it.eventId })
        }
    }
}

private fun event(
    type: SessionEventType,
    sessionId: String = SESSION_ID,
    runId: String = RUN_ID,
): SessionEventAppend = SessionEventAppend(
    sessionId = sessionId,
    runId = runId,
    requestId = REQUEST_ID,
    type = type,
)

private fun incrementalIds(): () -> String {
    val next = AtomicInteger(0)
    return { "event-${next.incrementAndGet()}" }
}

private const val SESSION_ID = "session-1"
private const val SESSION_A = "session-a"
private const val SESSION_B = "session-b"
private const val RUN_ID = "run-1"
private const val SHARED_RUN = "shared-run"
private const val REQUEST_ID = "request-1"

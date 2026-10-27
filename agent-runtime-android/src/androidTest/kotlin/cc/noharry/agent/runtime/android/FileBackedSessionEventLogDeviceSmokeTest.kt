@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.android

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import cc.noharry.agent.kernel.api.SessionEventAppend
import cc.noharry.agent.kernel.api.SessionEventLog
import cc.noharry.agent.kernel.api.SessionEventLogHealthStatus
import cc.noharry.agent.kernel.api.SessionEventType
import cc.noharry.agent.kernel.core.state.encodeFileName
import cc.noharry.agent.runtime.eventlog.openFileBackedSessionEventLog
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileBackedSessionEventLogDeviceSmokeTest {
    /** device filesystem reopen/recovery smoke */
    @Test
    fun deviceFilesystemReopenRecoverySmoke() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = appStorage(context.noBackupFilesDir, "$TEST_DIR_PREFIX-${UUID.randomUUID()}")
        try {
            assertRootIsNoBackupNotFiles(root, context.noBackupFilesDir, context.filesDir)
            val writer = openFileBackedSessionEventLog(root, eventIdFactory = incrementalIds("write"))
            appendStartedThenFinished(writer, SESSION_A, SHARED_RUN)
            appendStartedThenFinished(writer, SESSION_B, SHARED_RUN)
            assertIsolatedCompleteStreams(writer, root)

            val reopened = openFileBackedSessionEventLog(root, eventIdFactory = incrementalIds("reopen"))
            assertReopenedComplete(reopened)

            val corruptFile = eventFile(root, SESSION_A, SHARED_RUN)
            corruptFile.appendText(TRUNCATED_EVENT_LINE)
            val corruptedA = corruptFile.readBytes()

            val recovered = openFileBackedSessionEventLog(root, eventIdFactory = incrementalIds("recover"))
            assertEquals(
                listOf(FIRST_EVENT_SEQUENCE, SECOND_EVENT_SEQUENCE),
                sequencesOf(recovered, SESSION_A),
            )
            assertNotEquals(
                SessionEventLogHealthStatus.COMPLETE,
                recovered.health(SESSION_A, SHARED_RUN).status,
            )

            val third = recovered.append(
                event(SessionEventType.RUN_STATUS, SESSION_B, SHARED_RUN),
            )
            assertEquals(THIRD_EVENT_SEQUENCE, third.sequence)
            assertEquals(
                listOf(FIRST_EVENT_SEQUENCE, SECOND_EVENT_SEQUENCE, THIRD_EVENT_SEQUENCE),
                sequencesOf(recovered, SESSION_B),
            )
            assertEquals(
                SessionEventLogHealthStatus.COMPLETE,
                recovered.health(SESSION_B, SHARED_RUN).status,
            )
            assertTrue(corruptedA.contentEquals(corruptFile.readBytes()))
            assertNotEquals(
                SessionEventLogHealthStatus.COMPLETE,
                recovered.health(SESSION_A, SHARED_RUN).status,
            )
        } finally {
            root.deleteRecursively()
        }
    }
}

private fun assertRootIsNoBackupNotFiles(root: File, noBackup: File, filesDir: File) {
    val canonicalRoot = root.canonicalFile.toPath()
    assertTrue(canonicalRoot.startsWith(noBackup.canonicalFile.toPath()))
    assertFalse(canonicalRoot.startsWith(filesDir.canonicalFile.toPath()))
}

private fun appendStartedThenFinished(
    log: SessionEventLog,
    sessionId: String,
    runId: String,
) {
    val started = log.append(event(SessionEventType.RUN_STARTED, sessionId, runId))
    val finished = log.append(
        event(
            SessionEventType.RUN_FINISHED,
            sessionId,
            runId,
            sourceEventSequences = listOf(started.sequence),
        ),
    )
    assertEquals(FIRST_EVENT_SEQUENCE, started.sequence)
    assertEquals(SECOND_EVENT_SEQUENCE, finished.sequence)
}

private fun assertIsolatedCompleteStreams(log: SessionEventLog, root: File) {
    val pathA = eventFile(root, SESSION_A, SHARED_RUN)
    val pathB = eventFile(root, SESSION_B, SHARED_RUN)
    assertTrue(pathA.isFile)
    assertTrue(pathB.isFile)
    assertNotEquals(pathA.canonicalPath, pathB.canonicalPath)
    assertEquals(listOf(FIRST_EVENT_SEQUENCE, SECOND_EVENT_SEQUENCE), sequencesOf(log, SESSION_A))
    assertEquals(listOf(FIRST_EVENT_SEQUENCE, SECOND_EVENT_SEQUENCE), sequencesOf(log, SESSION_B))
    assertEquals(SessionEventLogHealthStatus.COMPLETE, log.health(SESSION_A, SHARED_RUN).status)
    assertEquals(SessionEventLogHealthStatus.COMPLETE, log.health(SESSION_B, SHARED_RUN).status)
}

private fun assertReopenedComplete(log: SessionEventLog) {
    assertEquals(listOf(FIRST_EVENT_SEQUENCE, SECOND_EVENT_SEQUENCE), sequencesOf(log, SESSION_A))
    assertEquals(listOf(FIRST_EVENT_SEQUENCE, SECOND_EVENT_SEQUENCE), sequencesOf(log, SESSION_B))
    assertEquals(SessionEventLogHealthStatus.COMPLETE, log.health(SESSION_A, SHARED_RUN).status)
    assertEquals(SessionEventLogHealthStatus.COMPLETE, log.health(SESSION_B, SHARED_RUN).status)
}

private fun sequencesOf(log: SessionEventLog, sessionId: String): List<Long> =
    log.replay(sessionId, SHARED_RUN).map { it.sequence }

private fun event(
    type: SessionEventType,
    sessionId: String,
    runId: String,
    sourceEventSequences: List<Long> = emptyList(),
): SessionEventAppend = SessionEventAppend(
    sessionId = sessionId,
    runId = runId,
    requestId = REQUEST_ID,
    type = type,
    sourceEventSequences = sourceEventSequences,
)

private fun eventFile(root: File, sessionId: String, runId: String): File =
    File(root, "$STREAMS_RELATIVE/${encodeFileName(sessionId)}/${encodeFileName(runId)}/segments/000001.jsonl")

private fun incrementalIds(prefix: String): () -> String {
    val next = AtomicInteger(0)
    return { "$prefix-${next.incrementAndGet()}" }
}

private const val TEST_DIR_PREFIX = "eventlog-device-smoke"
private const val SESSION_A = "session-a"
private const val SESSION_B = "session-b"
private const val SHARED_RUN = "run-shared"
private const val REQUEST_ID = "request-device-smoke"
private const val FIRST_EVENT_SEQUENCE = 1L
private const val SECOND_EVENT_SEQUENCE = 2L
private const val THIRD_EVENT_SEQUENCE = 3L
private const val STREAMS_RELATIVE = "session-events/v2/streams"
private const val TRUNCATED_EVENT_LINE = """{"schemaVersion":1"""

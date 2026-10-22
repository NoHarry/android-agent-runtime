@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionEventAppend
import cc.noharry.agent.kernel.api.SessionEventLogHealthStatus
import cc.noharry.agent.kernel.api.SessionEventPayload
import cc.noharry.agent.kernel.api.SessionEventType
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SessionEventAppendCursorTest {
    @Test
    fun activeWriterAppendsWithoutRescanningHistoryAndReopensLosslesslyAcrossRollover() {
        val root = Files.createTempDirectory("append-cursor-").toFile()
        val durable = FileBackedSessionEventLog(root, segmentRolloverBytes = 1_024)
        val log = AsyncSessionEventLog(durable)
        try {
            val accepted = mutableListOf(log.append(event("x".repeat(2_048))))
            assertTrue(log.flush())
            val reads = durable.debugStreamReadCount()
            repeat(8) {
                accepted += log.append(event("chunk-$it", listOf(1)))
                assertTrue(log.flush())
            }
            assertEquals("Appends must use the owned cursor, not rescan the prefix", reads, durable.debugStreamReadCount())
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
    fun changedClosedSegmentInvalidatesCursorAndDoesNotClaimComplete() {
        val root = Files.createTempDirectory("append-cursor-corrupt-").toFile()
        val durable = FileBackedSessionEventLog(root, segmentRolloverBytes = 1)
        val log = AsyncSessionEventLog(durable)
        try {
            log.append(event("first")); assertTrue(log.flush())
            log.append(event("second")); assertTrue(log.flush())
            val first = root.walkTopDown().first { it.name == "000001.jsonl" }
            first.appendText("corruption\n")
            log.append(event("third"))
            assertFalse(log.flush())
            assertEquals(SessionEventLogHealthStatus.DEGRADED, log.health(SESSION, RUN).status)
            assertThrows(IllegalStateException::class.java) { FileBackedSessionEventLog(root).replay(SESSION, RUN) }
        } finally {
            log.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun oversizedBlobSurvivesIncrementalWritesAndColdChecksumValidation() {
        val root = Files.createTempDirectory("append-cursor-blob-").toFile()
        val log = AsyncSessionEventLog(FileBackedSessionEventLog(root, blobInlineThresholdBytes = 512))
        try {
            val accepted = mutableListOf(log.append(event("中".repeat(8_192))))
            assertTrue(log.flush())
            accepted += log.append(event("tail", listOf(1)))
            log.close()
            assertEquals(accepted, FileBackedSessionEventLog(root).replay(SESSION, RUN))
            root.walkTopDown().first { it.parentFile?.name == "blobs" && it.isFile }.appendText("bad")
            assertThrows(IllegalStateException::class.java) { FileBackedSessionEventLog(root).replay(SESSION, RUN) }
        } finally {
            log.close()
            root.deleteRecursively()
        }
    }

    private fun event(text: String, sources: List<Long> = emptyList()) = SessionEventAppend(
        sessionId = SESSION, runId = RUN, requestId = RUN, type = SessionEventType.ASSISTANT_CHUNK,
        payload = SessionEventPayload.AssistantChunk("text", text), sourceEventSequences = sources,
    )
}

private const val SESSION = "cursor-session"
private const val RUN = "cursor-run"

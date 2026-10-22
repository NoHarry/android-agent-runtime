@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionEvent
import cc.noharry.agent.kernel.api.SessionEventPayload
import cc.noharry.agent.kernel.api.SessionEventType
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventValidationCostTest {
    @Test
    fun validatingContiguousHistoryVisitsEachRecordOnlyLinearly() {
        val records = CountingEvents((1..HISTORY_SIZE).map { event(it) })
        validatePersistedStream(records)
        assertTrue("History repeatedly scanned: ${records.reads}", records.reads <= HISTORY_SIZE * 2)
    }

    @Test
    fun rejectsNonexistentCausalSourcesAndGapsWithoutPrefixScans() {
        for (source in listOf(0L, 2L, 3L)) {
            assertThrows(IllegalArgumentException::class.java) {
                validatePersistedStream(listOf(event(1), event(2).copy(sourceEventSequences = listOf(source))))
            }
        }
        assertThrows(IllegalStateException::class.java) {
            validatePersistedStream(listOf(event(1), event(3).copy(sourceEventSequences = listOf(2L))))
        }
        validatePersistedStream(listOf(event(1), event(2).copy(sourceEventSequences = listOf(1L))))
    }

    private fun event(sequence: Int) = SessionEvent(
        schemaVersion = 2, eventId = "event-$sequence", sessionId = "session", runId = "run",
        requestId = "request", sequence = sequence.toLong(), recordedAtEpochMillis = 100,
        type = SessionEventType.RUN_STATUS, payload = SessionEventPayload.Empty, sourceEventSequences = emptyList(),
    )

    private class CountingEvents(private val records: List<SessionEvent>) : AbstractList<SessionEvent>() {
        var reads = 0
        override val size: Int get() = records.size
        override fun get(index: Int): SessionEvent {
            reads++
            return records[index]
        }
    }
}

private const val HISTORY_SIZE = 2_000

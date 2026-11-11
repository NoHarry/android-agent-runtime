@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory
import cc.noharry.agent.appserver.protocol.EventLogHealthStatus
import cc.noharry.agent.appserver.protocol.TrajectoryRichAvailability
import cc.noharry.agent.appserver.protocol.TrajectorySource
import cc.noharry.agent.spi.SessionEventLogHealth
import cc.noharry.agent.spi.SessionEventLogHealthStatus
import cc.noharry.agent.spi.SessionEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryMergeTest {

    @Test
    fun canonicalRecordsAreOrderedBySequenceWithinTheRun() {
        val projection = projectCanonicalRecords(
            sessionId = "s1",
            runId = "run-1",
            events = listOf(
                sessionEvent(eventId = "c", sequence = 3),
                sessionEvent(eventId = "a", sequence = 1),
                sessionEvent(eventId = "b", sequence = 2),
            ),
        )

        assertEquals(listOf(1L, 2L, 3L), projection.records.mapNotNull { it.sequence })
        assertFalse(projection.hasSequenceGap)
        assertFalse(projection.hasSequenceConflict)
        assertTrue(projection.records.all { it.sources == setOf(TrajectorySource.EVENT_LOG) })
    }

    @Test
    fun eventsFromAnotherRunOrSessionAreRejectedNotReordered() {
        val projection = projectCanonicalRecords(
            sessionId = "s1",
            runId = "run-1",
            events = listOf(
                sessionEvent(eventId = "mine", sequence = 1),
                sessionEvent(eventId = "other-run", runId = "run-2", sequence = 2),
                sessionEvent(eventId = "other-session", sessionId = "s2", sequence = 3),
            ),
        )

        assertEquals(listOf("evt:mine"), projection.records.map { "evt:${it.eventId}" })
        assertTrue(projection.hasSequenceConflict)
    }

    @Test
    fun sequenceGapIsReportedWithoutInventingTheMissingRows() {
        val projection = projectCanonicalRecords(
            sessionId = "s1",
            runId = "run-1",
            events = listOf(
                sessionEvent(eventId = "a", sequence = 1),
                sessionEvent(eventId = "b", sequence = 5),
            ),
        )

        assertTrue(projection.hasSequenceGap)
        assertEquals(2, projection.records.size)
    }

    @Test
    fun duplicateSequenceStopsCanonicalMergeAndKeepsTheVerifiedPrefix() {
        val projection = projectCanonicalRecords(
            sessionId = "s1",
            runId = "run-1",
            events = listOf(
                sessionEvent(eventId = "a", sequence = 1),
                sessionEvent(eventId = "b", sequence = 2),
                sessionEvent(eventId = "dup", sequence = 2),
            ),
        )

        assertTrue(projection.hasSequenceConflict)
        assertEquals(listOf("a", "b"), projection.records.mapNotNull { it.eventId })
    }

    @Test
    fun acceptedAndReplayedRowsShareOneEventIdentity() {
        val event = toolStartedEvent(callId = "run-1:call-a")
        val accepted = event.toTrajectoryRecord()
        val replayed = projectCanonicalRecords("s1", "run-1", listOf(event)).records.single()

        assertEquals(accepted.id, replayed.id)
        assertEquals("event:tool-start", replayed.id.value)
        assertEquals(setOf(TrajectorySource.EVENT_LOG), replayed.sources)
        assertFalse(replayed.isLive)
    }

    @Test
    fun modelToolCallAndExecutorStartRemainSeparateFacts() {
        val projection = projectCanonicalRecords(
            sessionId = "s1",
            runId = "run-1",
            events = listOf(
                sessionEvent(
                    eventId = "model-tool",
                    sequence = 2,
                    type = SessionEventType.MODEL_TOOL_CALL,
                    safeMetadata = mapOf("callId" to "call-a", "toolName" to "read_file"),
                ),
                sessionEvent(
                    eventId = "tool-start",
                    sequence = 3,
                    type = SessionEventType.TOOL_STARTED,
                    safeMetadata = mapOf("callId" to "call-a", "toolName" to "read_file"),
                ),
            ),
        )

        assertEquals(2, projection.records.size)
        assertEquals(listOf("model-tool", "tool-start"), projection.records.map { it.eventId })
    }

    @Test
    fun completeRuntimeHealthIsDowngradedWhenSequencesConflict() {
        val projection = CanonicalProjection(
            records = emptyList(),
            hasSequenceGap = true,
            hasSequenceConflict = true,
        )

        val health = composeTrajectoryHealth(
            runtimeHealth = SessionEventLogHealth(status = SessionEventLogHealthStatus.COMPLETE),
            projection = projection,
            richAvailability = TrajectoryRichAvailability.AVAILABLE,
        )

        assertEquals(EventLogHealthStatus.DEGRADED, health.eventLogStatus)
        assertTrue(health.hasSequenceGap)
        assertFalse(health.hasLiveOnlyRecords)
        assertEquals("Degraded", health.badgeLabel)
    }

    private fun toolStartedEvent(callId: String, toolName: String = "web_search") =
        sessionEvent(
            eventId = "tool-start",
            sequence = 2,
            type = SessionEventType.TOOL_STARTED,
            safeMetadata = mapOf("callId" to callId, "toolName" to toolName),
        )
}

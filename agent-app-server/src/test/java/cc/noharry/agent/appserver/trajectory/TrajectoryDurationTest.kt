@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory
import cc.noharry.agent.appserver.protocol.TrajectoryRecord
import cc.noharry.agent.appserver.protocol.TrajectoryRunGroup
import cc.noharry.agent.appserver.protocol.view.formatTrajectoryDuration
import cc.noharry.agent.appserver.protocol.view.formatTrajectoryDurationLabel
import cc.noharry.agent.appserver.protocol.view.toolCallSummary
import cc.noharry.agent.appserver.protocol.view.trajectoryDurationMs
import cc.noharry.agent.appserver.protocol.view.trajectoryInspectorSummaryEntries
import cc.noharry.agent.appserver.protocol.view.trajectoryInspectorTimingEntries
import cc.noharry.agent.spi.SessionEvent
import cc.noharry.agent.spi.SessionEventPayload
import cc.noharry.agent.spi.SessionEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrajectoryDurationTest {

    @Test
    fun toolStartAndFinishShareCallDurationOnSummaryAndTiming() {
        val start = toolRecord(eventId = "start", sequence = 4, at = 1_000L, type = SessionEventType.TOOL_STARTED)
        val finish = toolRecord(eventId = "finish", sequence = 5, at = 2_240L, type = SessionEventType.TOOL_RESULT)
        val siblings = listOf(start, finish)

        assertEquals(1_240L, trajectoryDurationMs(start, siblings))
        assertEquals(1_240L, trajectoryDurationMs(finish, siblings))
        assertEquals("1.24s", field(trajectoryInspectorSummaryEntries(start, null, 1_240L), "duration"))
        assertEquals("1.24s", field(trajectoryInspectorTimingEntries(finish, 1_240L), "duration"))
        assertEquals("1240", field(trajectoryInspectorTimingEntries(finish, 1_240L), "durationMs"))
    }

    @Test
    fun inProgressToolHasNoDuration() {
        val start = toolRecord(eventId = "start", sequence = 4, at = 1_000L, type = SessionEventType.TOOL_STARTED)

        assertNull(trajectoryDurationMs(start, listOf(start)))
        assertEquals("—", field(trajectoryInspectorSummaryEntries(start, null, null), "duration"))
        assertEquals("—", field(trajectoryInspectorTimingEntries(start, null), "duration"))
    }

    @Test
    fun modelCallFinishedUsesRecordedDurationMs() {
        val finished = SessionEvent(
            schemaVersion = 2,
            eventId = "model-done",
            sessionId = "s1",
            runId = "run-1",
            requestId = "req-1",
            sequence = 8,
            recordedAtEpochMillis = 3_000L,
            type = SessionEventType.MODEL_CALL_FINISHED,
            payload = SessionEventPayload.ModelCallFinished(callId = "m1", durationMs = 850L),
            sourceEventSequences = emptyList(),
        ).toTrajectoryRecord()

        assertEquals(850L, trajectoryDurationMs(finished, listOf(finished)))
        assertEquals("850ms", field(trajectoryInspectorSummaryEntries(finished, null, 850L), "duration"))
    }

    @Test
    fun durationFormatterKeepsSubSecondResolution() {
        assertEquals("347ms", formatTrajectoryDuration(347L))
        assertEquals("1.00s", formatTrajectoryDuration(1_000L))
        assertEquals("1.24s", formatTrajectoryDuration(1_240L))
        assertEquals("1m 05s", formatTrajectoryDuration(65_000L))
        assertEquals("—", formatTrajectoryDurationLabel(null))
    }

    @Test
    fun collapsedToolSummaryUsesTheSameDurationLabel() {
        val start = toolRecord(eventId = "start", sequence = 4, at = 1_000L, type = SessionEventType.TOOL_STARTED)
        val finish = toolRecord(
            eventId = "finish",
            sequence = 5,
            at = 1_400L,
            type = SessionEventType.TOOL_RESULT,
            status = "OK",
        )
        val group = TrajectoryRunGroup(sessionId = "s1", runId = "run-1", records = listOf(start, finish))

        assertEquals("read_file · 400ms · OK", group.toolCallSummary("c1"))
    }
}

private fun toolRecord(
    eventId: String,
    sequence: Long,
    at: Long,
    type: SessionEventType,
    status: String = "",
): TrajectoryRecord = sessionEvent(
    eventId = eventId,
    sequence = sequence,
    recordedAtEpochMillis = at,
    type = type,
    safeMetadata = buildMap {
        put("callId", "c1")
        put("toolName", "read_file")
        if (status.isNotBlank()) put("status", status)
    },
).toTrajectoryRecord()

private fun field(entries: List<Pair<String, String>>, key: String): String =
    entries.first { it.first == key }.second

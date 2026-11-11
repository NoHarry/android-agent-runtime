package cc.noharry.agent.appserver.trajectory
import cc.noharry.agent.appserver.protocol.TrajectoryCorrelation
import cc.noharry.agent.appserver.protocol.TrajectoryKind
import cc.noharry.agent.appserver.protocol.TrajectoryLane
import cc.noharry.agent.appserver.protocol.TrajectoryRecord
import cc.noharry.agent.appserver.protocol.TrajectoryRecordId
import cc.noharry.agent.appserver.protocol.TrajectoryTimeQuality
import cc.noharry.agent.appserver.protocol.view.TrajectoryRangeSelection
import cc.noharry.agent.appserver.protocol.view.TrajectoryTimeRange
import cc.noharry.agent.appserver.protocol.view.TrajectoryTimelineMode
import cc.noharry.agent.appserver.protocol.view.formatTrajectoryDurationLabel
import cc.noharry.agent.appserver.protocol.view.headerMetricLabels
import cc.noharry.agent.appserver.protocol.view.isInRange
import cc.noharry.agent.appserver.protocol.view.projectTrajectoryTimeline
import cc.noharry.agent.appserver.protocol.view.rangeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryTimelineProjectionTest {

    @Test
    fun recordsAreSplitAcrossLanesWithSystemRowsAsMarkers() {
        val projection = projectTrajectoryTimeline(
            listOf(
                record("start", TrajectoryKind.RUN_START, sequence = 1, at = 1_000L),
                record("model", TrajectoryKind.MODEL_MESSAGE, sequence = 2, at = 1_100L),
                record("tool", TrajectoryKind.TOOL_START, sequence = 3, at = 1_200L),
                record("end", TrajectoryKind.RUN_END, sequence = 4, at = 1_400L),
            ),
        )

        assertEquals(
            setOf(TrajectoryLane.INPUT, TrajectoryLane.MODEL, TrajectoryLane.TOOLS),
            projection.items.map { it.lane }.toSet(),
        )
        assertEquals(listOf(TrajectoryKind.RUN_END), projection.markers.map { it.kind })
    }

    @Test
    fun actualTimeModePositionsItemsProportionallyToTimestamps() {
        val projection = projectTrajectoryTimeline(
            listOf(
                record("a", TrajectoryKind.RUN_START, sequence = 1, at = 1_000L),
                record("b", TrajectoryKind.MODEL_MESSAGE, sequence = 2, at = 1_250L),
                record("c", TrajectoryKind.MODEL_MESSAGE, sequence = 3, at = 2_000L),
            ),
            TrajectoryTimelineMode.ACTUAL_TIME,
        )

        assertEquals(listOf(0f, 0.25f, 1f), projection.items.map { it.startFraction })
        assertEquals(1_000L, projection.spanMs)
    }

    @Test
    fun equalWidthModeSpacesLogicalStepsEvenly() {
        val projection = projectTrajectoryTimeline(
            listOf(
                record("a", TrajectoryKind.RUN_START, sequence = 1, at = 1_000L),
                record("b", TrajectoryKind.MODEL_MESSAGE, sequence = 2, at = 1_250L),
                record("c", TrajectoryKind.MODEL_MESSAGE, sequence = 3, at = 2_000L),
            ),
            TrajectoryTimelineMode.EQUAL_WIDTH,
        )

        assertEquals(listOf(0f, 0.5f, 1f), projection.items.map { it.startFraction })
    }

    @Test
    fun toolStartAndFinishSharingACallIdBecomeOneSpan() {
        val projection = projectTrajectoryTimeline(
            listOf(
                record("start", TrajectoryKind.RUN_START, sequence = 1, at = 1_000L),
                record("tool-start", TrajectoryKind.TOOL_START, sequence = 2, at = 1_200L, callId = "call-a"),
                record("tool-finish", TrajectoryKind.TOOL_FINISH, sequence = 3, at = 1_600L, callId = "call-a"),
            ),
        )

        val span = projection.items.single { it.kind == TrajectoryKind.TOOL_START }
        assertTrue(span.isSpan)
        assertEquals(1f, span.endFraction)
        // The paired finish is folded into the span rather than drawn twice.
        assertFalse(projection.items.any { it.kind == TrajectoryKind.TOOL_FINISH })
    }

    @Test
    fun unpairedToolStartStaysAPointMarkerInsteadOfAGuessedSpan() {
        val projection = projectTrajectoryTimeline(
            listOf(
                record("start", TrajectoryKind.RUN_START, sequence = 1, at = 1_000L),
                record("tool-start", TrajectoryKind.TOOL_START, sequence = 2, at = 1_200L, callId = "call-a"),
                record("other-finish", TrajectoryKind.TOOL_FINISH, sequence = 3, at = 1_600L, callId = "call-b"),
            ),
        )

        assertFalse(projection.items.single { it.id.value == "tool-start" }.isSpan)
    }

    @Test
    fun modelCallSpanUsesRecordedDurationAndFallsBackToAPoint() {
        val withDuration = projectTrajectoryTimeline(
            listOf(
                record("a", TrajectoryKind.RUN_START, sequence = 1, at = 1_000L),
                record(
                    "call",
                    TrajectoryKind.MODEL_CALL,
                    sequence = 2,
                    at = 1_200L,
                    metadata = mapOf("durationMs" to "400"),
                ),
                record("z", TrajectoryKind.MODEL_MESSAGE, sequence = 3, at = 2_000L),
            ),
        )
        val withoutDuration = projectTrajectoryTimeline(
            listOf(
                record("a", TrajectoryKind.RUN_START, sequence = 1, at = 1_000L),
                record("call", TrajectoryKind.MODEL_CALL, sequence = 2, at = 1_200L),
                record("z", TrajectoryKind.MODEL_MESSAGE, sequence = 3, at = 2_000L),
            ),
        )

        assertTrue(withDuration.items.single { it.id.value == "call" }.isSpan)
        assertNull(withoutDuration.items.single { it.id.value == "call" }.endFraction)
    }

    @Test
    fun unknownTimestampsFallBackToLogicalStepsWithNoSpanOrTtft() {
        val projection = projectTrajectoryTimeline(
            listOf(
                record("a", TrajectoryKind.RUN_START, sequence = 1, at = null),
                record("b", TrajectoryKind.MODEL_MESSAGE, sequence = 2, at = null),
            ),
        )

        assertEquals(listOf(0f, 1f), projection.items.map { it.startFraction })
        assertNull(projection.spanMs)
        assertNull(projection.timeToFirstModelMs)
    }

    @Test
    fun ttftRequiresAPersistedFirstModelTimestamp() {
        val persisted = projectTrajectoryTimeline(
            listOf(
                record("a", TrajectoryKind.RUN_START, sequence = 1, at = 1_000L),
                record("b", TrajectoryKind.MODEL_MESSAGE, sequence = 2, at = 1_350L),
            ),
        )
        val liveOnly = projectTrajectoryTimeline(
            listOf(
                record("a", TrajectoryKind.RUN_START, sequence = 1, at = 1_000L),
                record(
                    "b",
                    TrajectoryKind.MODEL_MESSAGE,
                    sequence = 2,
                    at = 1_350L,
                    quality = TrajectoryTimeQuality.LIVE_ARRIVAL,
                ),
            ),
        )

        assertEquals(350L, persisted.timeToFirstModelMs)
        assertNull(liveOnly.timeToFirstModelMs)
    }

    @Test
    fun laneTotalsLabelInputTtftModelCallSumAndPairedToolSum() {
        val projection = projectTrajectoryTimeline(
            listOf(
                record("start", TrajectoryKind.RUN_START, sequence = 1, at = 1_000L),
                record("user", TrajectoryKind.INPUT, sequence = 2, at = 1_020L),
                record(
                    "model-a",
                    TrajectoryKind.MODEL_CALL,
                    sequence = 3,
                    at = 1_350L,
                    metadata = mapOf("durationMs" to "400"),
                ),
                record("tool-a-start", TrajectoryKind.TOOL_START, sequence = 4, at = 1_800L, callId = "a"),
                record("tool-a-finish", TrajectoryKind.TOOL_FINISH, sequence = 5, at = 2_000L, callId = "a"),
                record(
                    "model-b",
                    TrajectoryKind.MODEL_CALL,
                    sequence = 6,
                    at = 2_100L,
                    metadata = mapOf("durationMs" to "500"),
                ),
                record("tool-b-start", TrajectoryKind.TOOL_START, sequence = 7, at = 2_600L, callId = "b"),
                record("tool-b-finish", TrajectoryKind.TOOL_FINISH, sequence = 8, at = 2_900L, callId = "b"),
            ),
        )

        assertEquals(350L, projection.laneDurationMs[TrajectoryLane.INPUT])
        assertEquals(900L, projection.laneDurationMs[TrajectoryLane.MODEL])
        assertEquals(500L, projection.laneDurationMs[TrajectoryLane.TOOLS])
        assertEquals("350ms", formatTrajectoryDurationLabel(projection.laneDurationMs[TrajectoryLane.INPUT]))
        assertEquals("900ms", formatTrajectoryDurationLabel(projection.laneDurationMs[TrajectoryLane.MODEL]))
        assertEquals("500ms", formatTrajectoryDurationLabel(projection.laneDurationMs[TrajectoryLane.TOOLS]))
        assertEquals(
            listOf("TTFT 350ms", "input 350ms", "model 900ms", "tools 500ms", "span 1.90s"),
            projection.headerMetricLabels(),
        )
    }

    @Test
    fun laneTotalsStayUnknownWhenThatLaneHasNoTrustworthyWork() {
        val projection = projectTrajectoryTimeline(
            listOf(
                record("start", TrajectoryKind.RUN_START, sequence = 1, at = 1_000L),
                record("user", TrajectoryKind.INPUT, sequence = 2, at = 1_020L),
                record("tool-start", TrajectoryKind.TOOL_START, sequence = 3, at = 1_200L, callId = "open"),
            ),
        )

        assertNull(projection.laneDurationMs[TrajectoryLane.INPUT])
        assertNull(projection.laneDurationMs[TrajectoryLane.MODEL])
        assertNull(projection.laneDurationMs[TrajectoryLane.TOOLS])
        assertEquals("—", formatTrajectoryDurationLabel(projection.laneDurationMs[TrajectoryLane.TOOLS]))
    }

    /** A span wider than the selection has neither endpoint inside it but clearly intersects. */
    @Test
    fun rangeMatchesASpanThatFullyEnclosesTheSelection() {
        val range = TrajectoryTimeRange(0.4f, 0.6f)

        assertTrue(range.intersects(0.1f, 0.9f))
        assertTrue(range.intersects(0.5f))
        assertTrue(range.intersects(0.0f, 0.4f))
        assertTrue(range.intersects(0.6f, 1.0f))
        assertFalse(range.intersects(0.0f, 0.39f))
        assertFalse(range.intersects(0.61f, 1.0f))
        assertFalse(range.intersects(0.2f))
    }

    @Test
    fun enclosingToolSpanIsNotDimmedByANarrowRange() {
        val projection = projectTrajectoryTimeline(
            listOf(
                record("a", TrajectoryKind.RUN_START, sequence = 1, at = 1_000L),
                record("tool-start", TrajectoryKind.TOOL_START, sequence = 2, at = 1_100L, callId = "call-a"),
                record("tool-finish", TrajectoryKind.TOOL_FINISH, sequence = 3, at = 1_900L, callId = "call-a"),
                record("z", TrajectoryKind.MODEL_MESSAGE, sequence = 4, at = 2_000L),
            ),
        )

        // Selection sits strictly inside the read_file span, which used to dim the span itself.
        assertTrue(projection.isInRange(TrajectoryRecordId("tool-start"), TrajectoryTimeRange(0.4f, 0.6f)))
    }

    @Test
    fun rangeFilteringOnlyDimsAndNeverRemovesRecords() {
        val projection = projectTrajectoryTimeline(
            listOf(
                record("a", TrajectoryKind.RUN_START, sequence = 1, at = 1_000L),
                record("b", TrajectoryKind.MODEL_MESSAGE, sequence = 2, at = 2_000L),
            ),
        )
        val range = TrajectoryTimeRange(0f, 0.4f)

        assertEquals(2, projection.items.size)
        assertTrue(projection.isInRange(TrajectoryRecordId("a"), range))
        assertFalse(projection.isInRange(TrajectoryRecordId("b"), range))
        assertTrue(projection.isInRange(TrajectoryRecordId("b"), null))
    }

    /** Fractions are per-run, so an unowned range would dim unrelated records after a switch. */
    @Test
    fun rangeSelectionOnlyAppliesToTheRunItWasDrawnOn() {
        val selection = TrajectoryRangeSelection("run-1", TrajectoryTimeRange(0.4f, 0.6f))

        assertEquals(selection.range, selection.rangeFor("run-1"))
        assertNull(selection.rangeFor("run-2"))
        assertNull(selection.rangeFor(null))
        assertNull(null.rangeFor("run-1"))
    }

    @Test
    fun emptyRecordsProduceAnEmptyProjection() {
        assertTrue(projectTrajectoryTimeline(emptyList()).isEmpty)
    }

    private fun record(
        id: String,
        kind: TrajectoryKind,
        sequence: Long,
        at: Long?,
        callId: String? = null,
        metadata: Map<String, String> = emptyMap(),
        quality: TrajectoryTimeQuality = TrajectoryTimeQuality.PERSISTED_RECORDED,
    ) = TrajectoryRecord(
        id = TrajectoryRecordId(id),
        sessionId = "s1",
        runId = "run-1",
        sequence = sequence,
        occurredAtEpochMillis = at,
        timeQuality = quality,
        kind = kind,
        lane = kind.lane(),
        title = id,
        correlation = TrajectoryCorrelation(toolCallId = callId),
        safeMetadata = metadata,
    )
}

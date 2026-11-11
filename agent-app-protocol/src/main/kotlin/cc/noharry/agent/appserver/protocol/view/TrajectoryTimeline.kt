package cc.noharry.agent.appserver.protocol.view

import cc.noharry.agent.appserver.protocol.TrajectoryRecordId
import cc.noharry.agent.appserver.protocol.TrajectoryLane
import cc.noharry.agent.appserver.protocol.TrajectoryKind
import cc.noharry.agent.appserver.protocol.TrajectoryTimeQuality
import cc.noharry.agent.appserver.protocol.TrajectoryRecord

// The trajectory timeline's lanes, ranges and header metrics, projected from protocol records; pure, so the server's
// tests check it too.

/** Actual time honours known timestamps; equal width spaces logical steps evenly. */
enum class TrajectoryTimelineMode {
    ACTUAL_TIME,
    EQUAL_WIDTH,
}

data class TrajectoryTimeRange(
    val startFraction: Float,
    val endFraction: Float,
) {
    fun contains(fraction: Float): Boolean = fraction >= startFraction && fraction <= endFraction

    /**
     * Interval overlap, not endpoint containment. A span that fully encloses the selection has
     * neither endpoint inside it yet clearly intersects, and endpoint-only checks dimmed it.
     */
    fun intersects(itemStart: Float, itemEnd: Float? = null): Boolean =
        itemStart <= endFraction && (itemEnd ?: itemStart) >= startFraction
}

/**
 * A range bound to the run it was drawn on.
 *
 * Fractions are normalised per run, so an unowned range would dim unrelated records after a run
 * switch. Binding the owner makes that a pure rule instead of relying on effect ordering to clear
 * the state in time.
 */
data class TrajectoryRangeSelection(
    val runId: String,
    val range: TrajectoryTimeRange,
)

/** Returns the range only when it belongs to [runId]; any other run reads as no selection. */
fun TrajectoryRangeSelection?.rangeFor(runId: String?): TrajectoryTimeRange? =
    this?.takeIf { runId != null && it.runId == runId }?.range

/**
 * One placed item on a lane. [endFraction] is null for point markers, i.e. anything without a
 * trustworthy duration; a missing duration is shown as a point rather than guessed.
 */
data class TrajectoryTimelineItem(
    val id: TrajectoryRecordId,
    val lane: TrajectoryLane,
    val kind: TrajectoryKind,
    val label: String,
    val startFraction: Float,
    val endFraction: Float? = null,
    val status: String? = null,
    val isLive: Boolean = false,
) {
    val isSpan: Boolean get() = endFraction != null && endFraction > startFraction
}

data class TrajectoryTimelineProjection(
    val items: List<TrajectoryTimelineItem> = emptyList(),
    val markers: List<TrajectoryTimelineItem> = emptyList(),
    val mode: TrajectoryTimelineMode = TrajectoryTimelineMode.ACTUAL_TIME,
    /** Null when no reliable first-model timestamp exists; the UI then renders an em dash. */
    val timeToFirstModelMs: Long? = null,
    val spanMs: Long? = null,
    /**
     * Work time per visible lane. Input is time-to-first-model; Model sums recorded
     * `durationMs`; Tools sums paired start/finish. Missing work is null, not zero.
     */
    val laneDurationMs: Map<TrajectoryLane, Long?> = emptyMap(),
) {
    val isEmpty: Boolean get() = items.isEmpty() && markers.isEmpty()
}

/**
 * Places one run's records on the Input/Model/Tools lanes.
 *
 * Tool start/finish rows sharing a `callId` become a duration span; model calls use their
 * recorded `durationMs`. System rows become full-height markers instead of lane items.
 */
fun projectTrajectoryTimeline(
    records: List<TrajectoryRecord>,
    mode: TrajectoryTimelineMode = TrajectoryTimelineMode.ACTUAL_TIME,
): TrajectoryTimelineProjection {
    if (records.isEmpty()) return TrajectoryTimelineProjection(mode = mode)
    val ordered = records.sortedWith(
        compareBy({ it.sequence ?: Long.MAX_VALUE }, { it.occurredAtEpochMillis ?: Long.MAX_VALUE }),
    )
    val times = ordered.mapNotNull { it.occurredAtEpochMillis }
    val start = times.minOrNull()
    val end = times.maxOrNull()
    val spanMs = if (start != null && end != null && end > start) end - start else null
    val fractionOf = fractionResolver(ordered, mode, start, spanMs)

    val finishByCallId = ordered
        .filter { it.kind == TrajectoryKind.TOOL_FINISH }
        .mapNotNull { record -> record.correlation.toolCallId?.let { it to record } }
        .toMap()

    val items = mutableListOf<TrajectoryTimelineItem>()
    val markers = mutableListOf<TrajectoryTimelineItem>()
    ordered.forEach { record ->
        val startFraction = fractionOf(record)
        val item = TrajectoryTimelineItem(
            id = record.id,
            lane = record.lane,
            kind = record.kind,
            label = record.title,
            startFraction = startFraction,
            endFraction = record.endFraction(finishByCallId, fractionOf, spanMs, startFraction),
            status = record.status,
            isLive = record.isLive,
        )
        if (record.lane == TrajectoryLane.SYSTEM) markers += item else items += item
    }
    val timeToFirstModelMs = timeToFirstModel(ordered, start)
    return TrajectoryTimelineProjection(
        items = items.filterNot { it.kind == TrajectoryKind.TOOL_FINISH && it.pairedWithStart(items) },
        markers = markers,
        mode = mode,
        timeToFirstModelMs = timeToFirstModelMs,
        spanMs = spanMs,
        laneDurationMs = laneDurationsMs(ordered, timeToFirstModelMs),
    )
}

fun laneDurationsMs(
    records: List<TrajectoryRecord>,
    timeToFirstModelMs: Long?,
): Map<TrajectoryLane, Long?> = mapOf(
    TrajectoryLane.INPUT to timeToFirstModelMs,
    TrajectoryLane.MODEL to sumRecordedDurations(records, TrajectoryKind.MODEL_CALL),
    TrajectoryLane.TOOLS to sumToolCallDurations(records),
)

fun TrajectoryTimelineProjection.headerMetricLabels(): List<String> {
    val lanes = TrajectoryLane.entries.filter { it != TrajectoryLane.SYSTEM }.map { lane ->
        "${lane.name.lowercase()} ${formatTrajectoryDurationLabel(laneDurationMs[lane])}"
    }
    return listOf("TTFT ${formatTrajectoryDurationLabel(timeToFirstModelMs)}") +
        lanes +
        listOf("span ${formatTrajectoryDurationLabel(spanMs)}")
}

private fun sumRecordedDurations(records: List<TrajectoryRecord>, kind: TrajectoryKind): Long? {
    val durations = records.mapNotNull { record ->
        record.takeIf { it.kind == kind }?.let(::recordedDurationMs)
    }
    return durations.takeIf { it.isNotEmpty() }?.sum()
}

private fun sumToolCallDurations(records: List<TrajectoryRecord>): Long? {
    val callIds = records.mapNotNull { it.correlation.toolCallId }.toSet()
    val durations = callIds.mapNotNull { toolCallDurationMs(records, it) }
    return durations.takeIf { it.isNotEmpty() }?.sum()
}

private fun fractionResolver(
    ordered: List<TrajectoryRecord>,
    mode: TrajectoryTimelineMode,
    start: Long?,
    spanMs: Long?,
): (TrajectoryRecord) -> Float {
    val stepDenominator = (ordered.size - 1).coerceAtLeast(1).toFloat()
    val indexById = ordered.withIndex().associate { (index, record) -> record.id to index }
    // Equal width is also the fallback for actual time when no usable timestamp span exists.
    if (mode == TrajectoryTimelineMode.EQUAL_WIDTH || start == null || spanMs == null) {
        return { record -> (indexById[record.id] ?: 0) / stepDenominator }
    }
    return { record ->
        record.occurredAtEpochMillis
            ?.let { ((it - start).toFloat() / spanMs.toFloat()).coerceIn(0f, 1f) }
            ?: ((indexById[record.id] ?: 0) / stepDenominator)
    }
}

private fun TrajectoryRecord.endFraction(
    finishByCallId: Map<String, TrajectoryRecord>,
    fractionOf: (TrajectoryRecord) -> Float,
    spanMs: Long?,
    startFraction: Float,
): Float? {
    if (kind == TrajectoryKind.TOOL_START) {
        val callId = correlation.toolCallId ?: return null
        val finish = finishByCallId[callId] ?: return null
        return fractionOf(finish)
    }
    if (kind == TrajectoryKind.MODEL_CALL) {
        val durationMs = safeMetadata["durationMs"]?.toLongOrNull() ?: return null
        if (durationMs <= 0 || spanMs == null || spanMs <= 0) return null
        return (startFraction + durationMs.toFloat() / spanMs.toFloat()).coerceAtMost(1f)
    }
    return null
}

private fun TrajectoryTimelineItem.pairedWithStart(items: List<TrajectoryTimelineItem>): Boolean =
    items.any { it.kind == TrajectoryKind.TOOL_START && it.isSpan && it.endFraction == startFraction }

private fun timeToFirstModel(ordered: List<TrajectoryRecord>, start: Long?): Long? {
    if (start == null) return null
    val firstModel = ordered.firstOrNull {
        it.lane == TrajectoryLane.MODEL && it.timeQuality == TrajectoryTimeQuality.PERSISTED_RECORDED
    } ?: return null
    val at = firstModel.occurredAtEpochMillis ?: return null
    return (at - start).takeIf { it >= 0 }
}

/** Range filtering only dims out-of-range rows; ledger records are never removed. */
fun TrajectoryTimelineProjection.isInRange(
    id: TrajectoryRecordId,
    range: TrajectoryTimeRange?,
): Boolean {
    if (range == null) return true
    val item = (items + markers).firstOrNull { it.id == id } ?: return true
    return range.intersects(item.startFraction, item.endFraction)
}

package cc.noharry.agent.appserver.protocol.view

import cc.noharry.agent.appserver.protocol.TrajectoryHealth
import cc.noharry.agent.appserver.protocol.TrajectoryKind
import cc.noharry.agent.appserver.protocol.TrajectoryRecord
import cc.noharry.agent.appserver.protocol.TrajectoryRunGroup

// What the trajectory inspector offers for a record, how a long raw text is paged, and the durations and entries it
// shows; pure, so the server's tests check them too.

enum class TrajectoryInspectorTab {
    SUMMARY,
    PREVIEW,
    RAW,
    SOURCE,
    REQUEST,
    OPTIONS,
    TOOLS,
    THINKING,
    OUTPUT,
    USAGE,
    PAYLOAD,
    RESULT,
    SCHEMA,
    TIMING,
    HEALTH,
}

/** The inspector's tabs for a record, by what kind of step it is and what it carries. */
fun inspectorTabsFor(record: TrajectoryRecord): List<TrajectoryInspectorTab> {
    val tabs = linkedSetOf(TrajectoryInspectorTab.SUMMARY)
    val rich = record.rich
    when (record.kind) {
        TrajectoryKind.INPUT, TrajectoryKind.MODEL_MESSAGE -> {
            tabs += TrajectoryInspectorTab.PREVIEW
            tabs += TrajectoryInspectorTab.RAW
            tabs += TrajectoryInspectorTab.SOURCE
        }
        TrajectoryKind.THINKING -> {
            tabs += TrajectoryInspectorTab.THINKING
            tabs += TrajectoryInspectorTab.RAW
            tabs += TrajectoryInspectorTab.SOURCE
        }
        TrajectoryKind.MODEL_CALL -> {
            tabs += TrajectoryInspectorTab.REQUEST
            if (!rich?.schemaPreview.isNullOrBlank()) tabs += TrajectoryInspectorTab.TOOLS
            tabs += TrajectoryInspectorTab.OPTIONS
            tabs += TrajectoryInspectorTab.TIMING
            tabs += TrajectoryInspectorTab.RAW
        }
        TrajectoryKind.MODEL_TOOL, TrajectoryKind.TOOL_START -> {
            tabs += TrajectoryInspectorTab.PAYLOAD
            if (!rich?.schemaPreview.isNullOrBlank()) tabs += TrajectoryInspectorTab.SCHEMA
            tabs += TrajectoryInspectorTab.RAW
            tabs += TrajectoryInspectorTab.TIMING
        }
        TrajectoryKind.TOOL_FINISH -> {
            tabs += TrajectoryInspectorTab.RESULT
            tabs += TrajectoryInspectorTab.RAW
            tabs += TrajectoryInspectorTab.TIMING
        }
        TrajectoryKind.USAGE -> {
            tabs += TrajectoryInspectorTab.USAGE
            tabs += TrajectoryInspectorTab.RAW
        }
        TrajectoryKind.LOAD_ERROR, TrajectoryKind.GAP -> {
            tabs += TrajectoryInspectorTab.HEALTH
            tabs += TrajectoryInspectorTab.SOURCE
        }
        else -> {
            tabs += TrajectoryInspectorTab.RAW
            tabs += TrajectoryInspectorTab.SOURCE
            tabs += TrajectoryInspectorTab.TIMING
        }
    }
    tabs += TrajectoryInspectorTab.HEALTH
    return tabs.toList()
}

fun paginateRawText(text: String, pageSize: Int = TRAJECTORY_RAW_PAGE_CHARS): List<String> {
    if (text.isEmpty()) return listOf("")
    return text.chunked(pageSize.coerceAtLeast(1))
}

const val TRAJECTORY_RAW_PAGE_CHARS = 4096

fun trajectoryInspectorSummaryEntries(
    record: TrajectoryRecord,
    health: TrajectoryHealth?,
    durationMs: Long?,
): List<Pair<String, String>> = buildList {
    add("id" to record.id.value)
    add("kind" to record.kind.name)
    add("lane" to record.lane.name)
    record.status?.let { add("status" to it) }
    add("sequence" to (record.sequence?.toString() ?: "not persisted"))
    add("duration" to formatTrajectoryDurationLabel(durationMs))
    add("sources" to record.sources.joinToString { it.name })
    record.correlation.toolCallId?.let { add("toolCallId" to it) }
    record.correlation.modelCallId?.let { add("modelCallId" to it) }
    record.correlation.lifecyclePhase?.let { add("phase" to it) }
    if (record.sourceEventSequences.isNotEmpty()) {
        add("causedBy" to record.sourceEventSequences.joinToString())
    }
    health?.let { add("eventLog" to it.badgeLabel) }
    add("details" to record.richAvailability.name)
}

fun trajectoryInspectorTimingEntries(
    record: TrajectoryRecord,
    durationMs: Long?,
): List<Pair<String, String>> = buildList {
    add("time" to (record.occurredAtEpochMillis?.toString() ?: "unknown"))
    add("timeQuality" to record.timeQuality.name)
    add("duration" to formatTrajectoryDurationLabel(durationMs))
    durationMs?.let { add("durationMs" to it.toString()) }
    record.rich?.timing?.forEach { (key, value) ->
        if (key != DURATION_MS_KEY) add(key to value.toString())
    }
}

fun trajectoryDurationMs(
    record: TrajectoryRecord,
    siblings: List<TrajectoryRecord>,
): Long? {
    recordedDurationMs(record)?.let { return it }
    toolCallDurationMs(siblings, record.correlation.toolCallId)?.let { return it }
    val modelId = record.correlation.modelCallId ?: return null
    return siblings.firstNotNullOfOrNull { sibling ->
        sibling.takeIf { it.correlation.modelCallId == modelId }?.let(::recordedDurationMs)
    }
}

fun recordedDurationMs(record: TrajectoryRecord): Long? =
    record.safeMetadata[DURATION_MS_KEY]?.toLongOrNull()
        ?: record.rich?.timing?.get(DURATION_MS_KEY)

fun formatTrajectoryDuration(durationMs: Long): String {
    val ms = durationMs.coerceAtLeast(0L)
    if (ms < MILLIS_PER_SECOND) return "${ms}ms"
    if (ms < MILLIS_PER_MINUTE) {
        val hundredths = (ms % MILLIS_PER_SECOND) / 10L
        return "${ms / MILLIS_PER_SECOND}.${hundredths.toString().padStart(2, '0')}s"
    }
    return formatWorkedDuration(ms)
}

fun formatTrajectoryDurationLabel(durationMs: Long?): String =
    durationMs?.let(::formatTrajectoryDuration) ?: UNKNOWN_DURATION_LABEL

private const val DURATION_MS_KEY = "durationMs"
private const val UNKNOWN_DURATION_LABEL = "—"
private const val MILLIS_PER_SECOND = 1000L
private const val MILLIS_PER_MINUTE = 60_000L

/** `toolName · duration · status` for a collapsed tool call, matching the run-header summary shape. */
fun TrajectoryRunGroup.toolCallSummary(callId: String?): String {
    if (callId == null) return ""
    val phases = records.filter { it.correlation.toolCallId == callId }
    val toolName = phases.firstNotNullOfOrNull { it.safeMetadata["toolName"] } ?: phases.firstOrNull()?.title.orEmpty()
    val duration = toolCallDurationMs(records, callId)?.let(::formatTrajectoryDuration) ?: "—"
    val finish = phases.firstOrNull { it.kind == TrajectoryKind.TOOL_FINISH }
    val status = finish?.safeMetadata?.get("status") ?: finish?.status ?: "running"
    return "$toolName · $duration · $status"
}

fun toolCallDurationMs(records: List<TrajectoryRecord>, callId: String?): Long? {
    if (callId.isNullOrBlank()) return null
    val start = records.firstOrNull {
        it.correlation.toolCallId == callId && it.kind == TrajectoryKind.TOOL_START
    }?.occurredAtEpochMillis ?: return null
    val finish = records.firstOrNull {
        it.correlation.toolCallId == callId && it.kind == TrajectoryKind.TOOL_FINISH
    }?.occurredAtEpochMillis ?: return null
    return (finish - start).coerceAtLeast(0L)
}

package cc.noharry.agent.appserver.protocol.view

import cc.noharry.agent.appserver.protocol.A2aOutcome
import cc.noharry.agent.appserver.protocol.A2aTaskStatus
import cc.noharry.agent.appserver.protocol.RunActivity
import cc.noharry.agent.appserver.protocol.RunOutcome
import cc.noharry.agent.appserver.protocol.RunResult

fun formatWorkedDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / MILLIS_PER_SECOND).coerceAtLeast(0L)
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${"%02d".format(seconds)}s"
        else -> "${seconds}s"
    }
}

const val PARTIAL_SUMMARY = " · partial"

const val BLOCKED_SUMMARY = " · blocked"

const val LIVE_MODEL_LABEL = "live model"

const val SIMULATED_MODEL_LABEL = "simulated MCP"

fun String.normalizeTextEscapes(): String =
    replace("\\r\\n", "\n")
        .replace("\\n", "\n")
        .replace("\\t", "\t")

fun String.lineValue(name: String): String? =
    lineSequence().firstOrNull { it.startsWith("$name=") }?.substringAfter("=")?.trim()

const val GENERATED_IMAGES_DIR = "generated-images"

/** A picture the device saved itself; `show_card.image` takes this or a public https URL. */
val SAVED_PICTURE = Regex("$GENERATED_IMAGES_DIR/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+\\.(?:jpg|png|webp)")

const val IMAGE_GENERATE_TOOL = "image_generate"

/**
 * [this] as the overlay's projection names it: a short English header ("Thinking", "Running <tool>") that the screens
 * word in their language; [tool] is the tool running.
 */
fun RunActivity.headerWords(tool: String): String = when (this) {
    RunActivity.RUNNING_TOOL -> "$RUNNING_TOOL_PREFIX$tool"
    RunActivity.THINKING -> "Thinking"
    RunActivity.WAITING_FOR_APPROVAL -> "Waiting for approval"
    RunActivity.BLOCKED -> "Blocked"
    RunActivity.FAILED -> "Failed"
    RunActivity.STOPPED -> "Stopped"
    RunActivity.IDLE, RunActivity.STARTING, RunActivity.WORKING -> "Working"
}

/**
 * [this] as one English line ("Worked for 12s · partial"): the form the runtime's logs and evidence keep, and the one
 * the overlay's projection carries for the screens to word.
 */
fun RunResult.label(): String {
    val elapsed = formatWorkedDuration(durationMs.coerceAtLeast(0L))
    return when (outcome) {
        RunOutcome.COMPLETED -> "Worked for $elapsed"
        RunOutcome.PARTIAL -> "Worked for $elapsed$PARTIAL_SUMMARY"
        RunOutcome.BLOCKED -> "Worked for $elapsed$BLOCKED_SUMMARY"
        RunOutcome.REPLAY_FAILED -> "Worked for $elapsed$REPLAY_FAILED_SUMMARY"
        RunOutcome.FAILED -> "Failed after $elapsed"
        RunOutcome.STOPPED -> "Stopped after $elapsed"
    }
}

const val REPLAY_FAILED_SUMMARY = " · replay failed"

/** The runner's start line for a tool, "Running <name>". */
const val RUNNING_TOOL_PREFIX = "Running "
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L

/** 「completed · 12s」: how the last task ended and how long it ran. */
fun A2aOutcome.line(): String = listOfNotNull(status.wireLabel(), elapsedMillis?.let(::formatWorkedDuration)).joinToString(" · ")

/** An A2A task's state as the wire names it. */
fun A2aTaskStatus.wireLabel(): String = when (this) {
    A2aTaskStatus.SUBMITTED -> "submitted"
    A2aTaskStatus.WORKING -> "working"
    A2aTaskStatus.INPUT_REQUIRED -> "input-required"
    A2aTaskStatus.COMPLETED -> "completed"
    A2aTaskStatus.CANCELLED -> "canceled"
    A2aTaskStatus.FAILED -> "failed"
    A2aTaskStatus.REJECTED -> "rejected"
    A2aTaskStatus.UNKNOWN -> "unknown"
}

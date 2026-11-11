@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.protocol.RunActivity
import cc.noharry.agent.appserver.protocol.RunOutcome
import cc.noharry.agent.appserver.protocol.RunResult
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.view.BLOCKED_SUMMARY
import cc.noharry.agent.appserver.protocol.view.PARTIAL_SUMMARY
import cc.noharry.agent.appserver.protocol.view.REPLAY_FAILED_SUMMARY
import cc.noharry.agent.appserver.protocol.view.formatWorkedDuration
import cc.noharry.agent.appserver.protocol.view.label
import cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.spi.reliability.RuntimeCompletionSummary
import cc.noharry.agent.spi.reliability.RuntimeResult
import cc.noharry.agent.spi.reliability.RuntimeRunStatus


internal fun sessionRunnerOutcome(
    runtimeResult: RuntimeResult?,
    replayFailed: Boolean,
    /** The run stopped to ask the user (the kernel's `ask`): the question is its reply, as the transcript keeps it. */
    askedUser: Boolean = false,
): RunOutcome {
    val ended = when {
        runtimeResult != null &&
            RuntimeCompletionSummary.firstPartyPass(runtimeResult.status, runtimeResult.completion) ->
            RunOutcome.COMPLETED
        // A run that ends by asking the person (a confirmation before acting) has no result yet, and has not failed.
        askedUser || runtimeResult?.status == RuntimeRunStatus.WAITING_USER_INPUT -> RunOutcome.COMPLETED
        // An answer the model gave as partial or blocked (a reply that asks the person ended above, as a question does).
        runtimeResult?.status == RuntimeRunStatus.COMPLETED && runtimeResult.completion.outcome == RuntimeCompletionOutcome.PARTIAL ->
            RunOutcome.PARTIAL
        runtimeResult?.status == RuntimeRunStatus.COMPLETED && runtimeResult.completion.outcome == RuntimeCompletionOutcome.BLOCKED ->
            RunOutcome.BLOCKED
        runtimeResult?.status == RuntimeRunStatus.CANCELLED -> RunOutcome.STOPPED
        else -> RunOutcome.FAILED
    }
    // A failed replay only marks a run that otherwise completed: a failed, partial, blocked or stopped run keeps saying so
    // (the replay check, which counts only a full success or a question as done, fails each of those anyway).
    return if (replayFailed && ended == RunOutcome.COMPLETED) RunOutcome.REPLAY_FAILED else ended
}

/** The end of a run as the runtime's logs and status line keep it ("Worked for 12s"); [parseRunSummary] reads it back. */
internal fun formatRunSummary(durationMs: Long, outcome: RunOutcome): String = RunResult(outcome, durationMs).label()

/**
 * A summary [formatRunSummary] wrote, as the protocol carries it: how the run ended and after how long; null for any
 * other text.
 */
internal fun parseRunSummary(summary: String?): RunResult? {
    val match = RUN_SUMMARY.matchEntire(summary?.trim() ?: return null) ?: return null
    val (verb, duration, suffix) = match.destructured
    val outcome = when {
        verb == "Failed after" -> RunOutcome.FAILED
        verb == "Stopped after" -> RunOutcome.STOPPED
        suffix == PARTIAL_SUMMARY -> RunOutcome.PARTIAL
        suffix == BLOCKED_SUMMARY -> RunOutcome.BLOCKED
        suffix == REPLAY_FAILED_SUMMARY -> RunOutcome.REPLAY_FAILED
        else -> RunOutcome.COMPLETED
    }
    return RunResult(outcome, durationMillis(duration) ?: return null)
}

/**
 * What the runtime's status line says the run is doing, and the tool it names: the status line stays English for the
 * logs, and this is what goes out for the screens to word.
 */
internal fun runActivity(status: String): Pair<RunActivity, String> = when {
    status.startsWith("Tool running:", ignoreCase = true) -> RunActivity.RUNNING_TOOL to status.substringAfter(":").trim()
    status.startsWith("Thinking", ignoreCase = true) -> RunActivity.THINKING to ""
    status.startsWith("Failed", ignoreCase = true) -> RunActivity.FAILED to ""
    status.startsWith("Stopped", ignoreCase = true) -> RunActivity.STOPPED to ""
    status.contains("confirmation", ignoreCase = true) -> RunActivity.WAITING_FOR_APPROVAL to ""
    status.contains("blocked", ignoreCase = true) -> RunActivity.BLOCKED to ""
    // The runner's own start line, "Running <profile> <stream>".
    status.startsWith("Running ") && status.trimEnd().count { it == ' ' } >= 2 -> RunActivity.STARTING to ""
    else -> RunActivity.WORKING to ""
}

/** [this] with what the runtime's [status] line and its last [summary] say, as the protocol carries them. */
internal fun ServerState.withRun(status: String, running: Boolean, summary: String?): ServerState {
    val (activity, tool) = if (running) runActivity(status) else RunActivity.IDLE to ""
    return copy(activity = activity, activityTool = tool, runtimeStatus = status, lastRun = parseRunSummary(summary))
}

/** "1h 2m 3s", "1m 05s" or "12s" ([formatWorkedDuration]) in milliseconds. */
private fun durationMillis(label: String): Long? {
    val match = DURATION_LABEL.matchEntire(label.trim()) ?: return null
    val (hours, minutes, seconds) = match.destructured
    val total = (hours.toLongOrNull() ?: 0L) * SECONDS_PER_HOUR + (minutes.toLongOrNull() ?: 0L) * SECONDS_PER_MINUTE + seconds.toLong()
    return total * MILLIS_PER_SECOND
}

private val RUN_SUMMARY = Regex("""(Worked for|Failed after|Stopped after) (.+?)( · .+)?""")
private val DURATION_LABEL = Regex("""(?:(\d+)h )?(?:(\d+)m )?(\d+)s""")

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L

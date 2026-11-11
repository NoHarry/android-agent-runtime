@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.skills

import android.content.res.Resources
import androidx.annotation.StringRes
import cc.noharry.agent.runtime.SubagentState
import cc.noharry.agent.runtime.SubagentStatus
import cc.noharry.agent.appserver.R

// How a finished subagent is logged and announced, in the screens' language.

/** A subagent's state in words. */
@StringRes
internal fun SubagentState.labelRes(): Int = when (this) {
    SubagentState.RUNNING -> R.string.console_subagent_running
    SubagentState.STALLED -> R.string.console_subagent_stalled
    SubagentState.COMPLETED -> R.string.console_subagent_completed
    SubagentState.FAILED -> R.string.console_subagent_failed
    SubagentState.CANCELLED -> R.string.console_subagent_cancelled
    SubagentState.TIMED_OUT -> R.string.console_subagent_timed_out
}

/** `SubagentStatus.logLine()` in words: seconds, tool and model calls, tokens and cache hit. */
internal fun Resources.subagentLogText(row: SubagentStatus, now: Long = System.currentTimeMillis()): String {
    val end = if (row.finishedAtEpochMillis > 0) row.finishedAtEpochMillis else now
    val seconds = (end - row.startedAtEpochMillis) / MILLIS_PER_SECOND
    val line = if (row.inputTokens > 0) {
        val cache = row.cachedTokens * PERCENT / row.inputTokens
        getString(
            R.string.console_subagent_log_cached,
            seconds, row.toolCalls, row.modelCalls, row.inputTokens, cache, row.outputTokens,
        )
    } else {
        getString(
            R.string.console_subagent_log,
            seconds, row.toolCalls, row.modelCalls, row.inputTokens, row.outputTokens,
        )
    }
    return if (row.error.isNotEmpty()) "$line · ${row.error}" else line
}

private const val MILLIS_PER_SECOND = 1000L
private const val PERCENT = 100

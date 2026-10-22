package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode

data class LoopBudgetConfig(
    val maxModelRounds: Int = DEFAULT_MAX_MODEL_ROUNDS,
    val maxToolCallsPerRun: Int = DEFAULT_MAX_TOOL_CALLS_PER_RUN,
    val maxWallClockMillis: Long? = null,
    /** Whether the model accepts image input; when false every request image folds into text. */
    val imageInputEnabled: Boolean = true,
) {
    init {
        require(maxModelRounds > 0) { "maxModelRounds must be positive." }
        require(maxToolCallsPerRun > 0) { "maxToolCallsPerRun must be positive." }
        require(maxWallClockMillis == null || maxWallClockMillis > 0) {
            "maxWallClockMillis must be positive when set."
        }
    }
}

data class LoopBudgetSnapshot(
    val roundsStarted: Int = 0,
    val toolCallsUsed: Int = 0,
    val startedAtEpochMillis: Long? = null,
    val latestUsageTokens: Int = 0,
)

data class LoopBudgetWarning(
    val roundsRemaining: Int,
    val toolCallsRemaining: Int,
) {
    fun toPromptJson(): String =
        """{"budgetWarning":{"roundsRemaining":$roundsRemaining,"toolCallsRemaining":$toolCallsRemaining}}"""
}

class LoopBudgetTracker(
    private val config: LoopBudgetConfig,
    private val nowEpochMillis: () -> Long,
    snapshot: LoopBudgetSnapshot = LoopBudgetSnapshot(),
) {
    private var roundsStarted = snapshot.roundsStarted
    private var toolCallsUsed = snapshot.toolCallsUsed
    private var latestUsageTokens = snapshot.latestUsageTokens
    private val startedAt = snapshot.startedAtEpochMillis ?: nowEpochMillis()

    fun beforeRoundFailure(): ToolLoopFailure? =
        timeoutFailure() ?: if (roundsStarted >= config.maxModelRounds) {
            budgetFailure("Model loop exceeded maxModelRounds=${config.maxModelRounds}.")
        } else {
            null
        }

    fun budgetWarningForRound(): LoopBudgetWarning? {
        val roundsRemaining = (config.maxModelRounds - roundsStarted - 1).coerceAtLeast(0)
        val toolCallsRemaining = (config.maxToolCallsPerRun - toolCallsUsed).coerceAtLeast(0)
        return budgetWarning(roundsRemaining, toolCallsRemaining)
    }

    fun recordRoundStarted() {
        roundsStarted += 1
    }

    fun recordToolCall(): ToolLoopFailure? {
        toolCallsUsed += 1
        return timeoutFailure() ?: if (toolCallsUsed > config.maxToolCallsPerRun) {
            budgetFailure("Model loop exceeded maxToolCallsPerRun=${config.maxToolCallsPerRun}.")
        } else {
            null
        }
    }

    /** Starts a physical model call, including retries; prior call usage must not leak. */
    fun beginModelCall() { latestUsageTokens = 0 }

    /** Inclusive input deltas only: output/reasoning and cache breakdowns are not added again. */
    fun recordUsage(delta: ModelEvent.UsageDelta) {
        latestUsageTokens = (latestUsageTokens.toLong() + delta.inputTokens.coerceAtLeast(0))
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun latestUsageTokensForCompaction(): Int? =
        latestUsageTokens.takeIf { it > 0 }

    fun afterRoundFailure(): ToolLoopFailure? = timeoutFailure()

    fun snapshot(): LoopBudgetSnapshot = LoopBudgetSnapshot(
        roundsStarted = roundsStarted,
        toolCallsUsed = toolCallsUsed,
        startedAtEpochMillis = startedAt,
        latestUsageTokens = latestUsageTokens,
    )

    private fun timeoutFailure(): ToolLoopFailure? {
        val maxMillis = config.maxWallClockMillis ?: return null
        val elapsed = nowEpochMillis() - startedAt
        return if (elapsed > maxMillis) {
            ToolLoopFailure(
                RuntimeErrorCode.MODEL_LOOP_TIMEOUT.wireCode,
                "Model loop exceeded maxWallClockMillis=$maxMillis.",
            )
        } else {
            null
        }
    }
}

private fun budgetWarning(roundsRemaining: Int, toolCallsRemaining: Int): LoopBudgetWarning? =
    if (roundsRemaining == 0 || toolCallsRemaining <= LAST_TOOL_CALL_WARNING_THRESHOLD) {
        LoopBudgetWarning(roundsRemaining, toolCallsRemaining)
    } else {
        null
    }

private fun budgetFailure(message: String): ToolLoopFailure =
    ToolLoopFailure(RuntimeErrorCode.MODEL_LOOP_BUDGET_EXCEEDED.wireCode, message)

/**
 * No round or tool-call cap by default, as in dsh and Codex: a run goes until the model answers or the host cancels.
 * [RepeatToolCallReminder] and [ToolCallFailureBreaker] keep a stuck run from repeating itself; hosts that want a
 * hard cap set one with `loopBudget(...)`.
 */
const val DEFAULT_MAX_MODEL_ROUNDS = Int.MAX_VALUE
const val DEFAULT_MAX_TOOL_CALLS_PER_RUN = Int.MAX_VALUE
private const val LAST_TOOL_CALL_WARNING_THRESHOLD = 1

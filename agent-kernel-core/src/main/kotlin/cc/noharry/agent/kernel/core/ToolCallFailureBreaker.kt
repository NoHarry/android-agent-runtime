package cc.noharry.agent.kernel.core


import cc.noharry.agent.kernel.api.ToolUseResult
/**
 * Run-scoped circuit breaker for repeated backend tool-call failures.
 *
 * Missing bench capabilities often fail every attempt (error result, provider
 * exception, timeout, or an untyped MCP error), yet the model keeps
 * re-issuing calls until the loop budget is spent. Two tiers are tracked per run:
 *
 * 1. Identical call: the same (toolName, argumentsJson) failing
 *    [maxConsecutiveFailures] times trips that exact call.
 * 2. Same tool: the same tool failing [maxConsecutiveToolFailures] times in a row
 *    (with any arguments) trips the whole tool, because models often vary
 *    arguments while retrying an unavailable capability.
 *
 * A tripped call is not executed; the caller returns a terminal, non-retryable
 * result instead. A successful result for a tool resets both tiers for it. State
 * is kept per run and old runs are evicted once [MAX_TRACKED_RUNS] is exceeded.
 */
class ToolCallFailureBreaker(
    private val maxConsecutiveFailures: Int = DEFAULT_MAX_CONSECUTIVE_TOOL_CALL_FAILURES,
    private val maxConsecutiveToolFailures: Int = DEFAULT_MAX_CONSECUTIVE_TOOL_FAILURES_PER_TOOL,
) {
    init {
        require(maxConsecutiveFailures > 0) { "maxConsecutiveFailures must be positive." }
        require(maxConsecutiveToolFailures >= maxConsecutiveFailures) {
            "maxConsecutiveToolFailures must be at least maxConsecutiveFailures."
        }
    }

    private val lock = Any()
    private val runs = LinkedHashMap<String, RunFailureState>()

    /** Returns tripped-call info when this call (or its whole tool) already failed past a threshold. */
    fun trippedFailure(runId: String, toolName: String, argumentsJson: String): TrippedToolCall? =
        synchronized(lock) {
            val state = runs[runId] ?: return null
            val toolEntry = state.tools[toolName]
            if (toolEntry != null && toolEntry.consecutiveFailures >= maxConsecutiveToolFailures) {
                return TrippedToolCall(
                    failureCount = toolEntry.consecutiveFailures,
                    lastFailureCode = toolEntry.lastFailureCode,
                    scope = TrippedToolCallScope.TOOL,
                )
            }
            val callEntry = state.calls[callKey(toolName, argumentsJson)] ?: return null
            if (callEntry.consecutiveFailures < maxConsecutiveFailures) return null
            TrippedToolCall(
                failureCount = callEntry.consecutiveFailures,
                lastFailureCode = callEntry.lastFailureCode,
                scope = TrippedToolCallScope.IDENTICAL_ARGUMENTS,
            )
        }

    /** Transport/untyped failures increment both tiers; delivered business results reset availability failures. */
    fun record(runId: String, toolName: String, argumentsJson: String, result: ToolUseResult) {
        synchronized(lock) {
            val state = stateForRun(runId)
            val key = callKey(toolName, argumentsJson)
            if (result.ok || isMcpBusinessResult(result.resultJson)) {
                state.calls.keys.removeAll { it.startsWith(toolName + CALL_KEY_SEPARATOR) }
                state.tools.remove(toolName)
                return
            }
            val previousCall = state.calls[key]
            if (previousCall != null || state.calls.size < MAX_TRACKED_CALLS_PER_RUN) {
                state.calls[key] = previousCall.incremented(result.code)
            }
            state.tools[toolName] = state.tools[toolName].incremented(result.code)
        }
    }

    private fun stateForRun(runId: String): RunFailureState {
        val state = runs.remove(runId) ?: RunFailureState()
        runs[runId] = state
        while (runs.size > MAX_TRACKED_RUNS) {
            runs.remove(runs.keys.first())
        }
        return state
    }

    private fun callKey(toolName: String, argumentsJson: String): String =
        "$toolName$CALL_KEY_SEPARATOR${argumentsJson.trim()}"
}

private class RunFailureState {
    val calls = mutableMapOf<String, FailureEntry>()
    val tools = mutableMapOf<String, FailureEntry>()
}

private data class FailureEntry(
    val consecutiveFailures: Int,
    val lastFailureCode: String?,
)

private fun FailureEntry?.incremented(code: String?): FailureEntry = FailureEntry(
    consecutiveFailures = (this?.consecutiveFailures ?: 0) + 1,
    lastFailureCode = code ?: this?.lastFailureCode,
)

enum class TrippedToolCallScope {
    IDENTICAL_ARGUMENTS,
    TOOL,
}

data class TrippedToolCall(
    val failureCount: Int,
    val lastFailureCode: String?,
    val scope: TrippedToolCallScope = TrippedToolCallScope.IDENTICAL_ARGUMENTS,
)

internal const val DEFAULT_MAX_CONSECUTIVE_TOOL_CALL_FAILURES = 2
internal const val DEFAULT_MAX_CONSECUTIVE_TOOL_FAILURES_PER_TOOL = 4
private const val CALL_KEY_SEPARATOR = " "
private const val MAX_TRACKED_RUNS = 8
private const val MAX_TRACKED_CALLS_PER_RUN = 128

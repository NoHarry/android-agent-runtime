package cc.noharry.agent.kernel.core.loop

import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.NarrationCheck
import cc.noharry.agent.kernel.api.ServiceAnswerCheck
import cc.noharry.agent.kernel.core.context.RuntimeContextHooks
import cc.noharry.agent.kernel.core.tools.RuntimeServiceAnswerCheck
import cc.noharry.agent.kernel.core.memory.RuntimeMemoryRetriever

/**
 * What a host sets of the loop: what it judges about content, worded for the host's users' language and its domain
 * (which final answers only say what the agent will do, [narration]; which lessons from earlier runs fit a request,
 * [memory]; which failed tool results are a service's answer, [serviceAnswer]), how long the context providers have,
 * together, to add to a run's context before the first model call ([contextTimeoutMillis]; one that is late is left
 * out), and the loop's own limits ([limits]).
 */
@ExperimentalAgentRuntimeApi
class LoopSettings @JvmOverloads constructor(
    val narration: NarrationCheck = RuntimeNarrationCheck,
    val memory: RuntimeMemoryRetriever = RuntimeMemoryRetriever(),
    val serviceAnswer: ServiceAnswerCheck = RuntimeServiceAnswerCheck,
    val contextTimeoutMillis: Long = RuntimeContextHooks.CONTEXT_AGGREGATE_TIMEOUT_MS,
    val limits: LoopLimits = LoopLimits(),
) {
    init {
        require(contextTimeoutMillis in 1..MAX_CONTEXT_TIMEOUT_MILLIS) { "contextTimeoutMillis must be 1 to $MAX_CONTEXT_TIMEOUT_MILLIS." }
    }
}

/** The longest the context providers may take together: the first model call waits for them. */
const val MAX_CONTEXT_TIMEOUT_MILLIS = 60_000L

/**
 * The loop's own limits, today's values by default: after how many failures in a row a tool call with the same
 * arguments ([failuresPerCall]) or the tool itself ([failuresPerTool]) is no longer made in the run; after how many
 * identical calls the model is reminded it repeats itself ([repeatReminders]); how many of the newest images at least stay
 * in the conversation sent to the model ([newestImages]; older ones fold to a note three at a time, so up to two more
 * may stay).
 */
@ExperimentalAgentRuntimeApi
class LoopLimits @JvmOverloads constructor(
    val failuresPerCall: Int = DEFAULT_MAX_CONSECUTIVE_TOOL_CALL_FAILURES,
    val failuresPerTool: Int = DEFAULT_MAX_CONSECUTIVE_TOOL_FAILURES_PER_TOOL,
    repeatReminders: List<Int> = DEFAULT_REPEAT_REMINDERS,
    val newestImages: Int = DEFAULT_KEEP_NEWEST_IMAGES,
) {
    val repeatReminders: List<Int> = repeatReminders.sorted()

    init {
        require(failuresPerCall > 0 && failuresPerTool >= failuresPerCall) {
            "failuresPerCall must be positive and failuresPerTool at least as many."
        }
        require(this.repeatReminders.all { it > 1 }) { "A repeat reminder comes after two calls or more." }
        require(newestImages >= 0) { "newestImages must not be negative." }
    }
}

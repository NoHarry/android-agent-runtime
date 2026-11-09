@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.core.loop.*

import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.NarrationCheck
import cc.noharry.agent.spi.ServiceAnswerCheck
import cc.noharry.agent.kernel.core.context.RuntimeContextHooks
import cc.noharry.agent.runtime.RuntimeMemoryRetriever

/**
 * What a host sets of the loop: what it judges about content, worded for the host's users' language and its domain
 * (which final answers only say what the agent will do, [narration]; which lessons from earlier runs fit a request,
 * [memory]; which failed tool results are a service's answer, [serviceAnswer]), how long the context providers have,
 * together, to add to a run's context before the first model call ([contextTimeoutMillis]; one that is late is left
 * out), and the loop's own limits ([limits]).
 */
@ExperimentalAgentRuntimeApi
data class LoopSettings @JvmOverloads constructor(
    val narration: NarrationCheck = RuntimeNarrationCheck,
    val memory: RuntimeMemoryRetriever = RuntimeMemoryRetriever(),
    val serviceAnswer: ServiceAnswerCheck = ServiceAnswerCheck.NONE,
    val contextTimeoutMillis: Long = RuntimeContextHooks.CONTEXT_AGGREGATE_TIMEOUT_MS,
    /** Optional budget override. Null keeps the builder's independently configured limits. */
    val limits: LoopLimits? = null,
    val imageInputEnabled: Boolean = true,
    val backendTools: BackendTools = BackendTools.DIRECT,
    val runContextAsSystem: Boolean = false,
    val learnLessons: Boolean = true,
    val nativeWebFetchEnabled: Boolean = true,
    val disabledNativeTools: Set<String> = emptySet(),
    val networkConfirmationPolicy: NetworkToolConfirmationPolicy = NetworkToolConfirmationPolicy.REQUIRE_PER_HOST,
    val hookTimeoutMillis: Long = 5_000,
    val concurrency: RuntimeConcurrency = RuntimeConcurrency(),
) {
    internal val resolvedLimits: LoopLimits = limits ?: LoopLimits()

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
class LoopLimits @JvmOverloads constructor(
    val failuresPerCall: Int = DEFAULT_MAX_CONSECUTIVE_TOOL_CALL_FAILURES,
    val failuresPerTool: Int = DEFAULT_MAX_CONSECUTIVE_TOOL_FAILURES_PER_TOOL,
    repeatReminders: List<Int> = DEFAULT_REPEAT_REMINDERS,
    val newestImages: Int = DEFAULT_KEEP_NEWEST_IMAGES,
    val maxModelRounds: Int = DEFAULT_MAX_MODEL_ROUNDS,
    val maxToolCallsPerTurn: Int = DEFAULT_MAX_TOOL_CALLS_PER_RUN,
    val maxWallClockMillis: Long? = null,
    val contextWindowTokens: Long? = null,
    val compactionThresholdRatio: Double = 0.8,
    val modelRetryAttempts: Int = DEFAULT_MODEL_RETRY_MAX_ATTEMPTS,
    val retryInitialBackoffMillis: Long = 250,
    val retryMaxBackoffMillis: Long = 2_000,
    val fallbackModels: List<cc.noharry.agent.spi.model.ModelRef> = emptyList(),
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

internal fun LoopSettings.withLimits(limits: LoopLimits): LoopSettings = copy(limits = limits)

/** How provider tools become visible to the model. */
@ExperimentalAgentRuntimeApi
enum class BackendTools { DIRECT, SEARCH, NATIVE_DEFERRED }

/**
 * Process-local caps; provider-specific concurrency declarations remain in force. Runtimes sharing a storage
 * directory share one turn admission queue and use the most restrictive caps among its open runtime instances.
 */
@ExperimentalAgentRuntimeApi
data class RuntimeConcurrency(
    val tools: Int = 4,
    val models: Int = 4,
    val activeThreads: Int = 4,
    val queuedPerThread: Int = 16,
    val queuedTotal: Int = 64,
) {
    init {
        require(listOf(tools, models, activeThreads, queuedPerThread, queuedTotal).all { it > 0 }) {
            "Concurrency limits must be positive."
        }
    }
}

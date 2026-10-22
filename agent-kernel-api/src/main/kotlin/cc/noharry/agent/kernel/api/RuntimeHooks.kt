package cc.noharry.agent.kernel.api

import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeResult

/** Default per-hook timeout used by candidate hook registrations. */
const val DEFAULT_HOOK_TIMEOUT_MILLIS: Long = 5_000L

/**
 * Candidate host hook API for observing and controlling runtime tool use.
 *
 * This API is scaffold until the API freeze boundary is signed.
 */
@ExperimentalAgentRuntimeApi
interface RuntimeHook {
    /** Candidate pre-tool callback. Returning Deny or RequireConfirmation prevents immediate execution. */
    suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision = HookDecision.Allow()

    /** Candidate post-tool callback. Exceptions are recorded internally and do not fail the run. */
    suspend fun onPostToolUse(call: ToolUse, result: ToolUseResult, context: HookContext) {}

    /** Candidate lifecycle callback for run start. */
    suspend fun onRunStarted(context: HookContext) {}

    /** Candidate lifecycle callback for terminal run completion. */
    suspend fun onRunFinished(result: RuntimeResult, context: HookContext) {}
}

/** Candidate record describing a model-visible tool call. */
data class ToolUse(
    val toolName: String,
    val argumentsJson: String,
    val source: ToolSource,
    val toolCallId: String = "",
)

/** Candidate source taxonomy for runtime tool calls. */
enum class ToolSource {
    INTERACTION,
    NATIVE_READ,
    NATIVE_WRITE,
    NETWORK,
    EXEC,
    NATIVE_TODO,
    BACKEND,
    TERMINAL,
}

/** Candidate post-tool result record for hook observers. */
data class ToolUseResult(
    val ok: Boolean,
    val resultJson: String,
    val retryable: Boolean = false,
    val code: String? = null,
)

/** Candidate context passed to host hooks and policy rules. */
data class HookContext(
    val sessionId: String,
    val runId: String,
    val requestId: String,
    val approvedConfirmation: Boolean = false,
)

/** Candidate host hook failure policy for pre-tool callbacks. */
@ExperimentalAgentRuntimeApi
enum class HookFailurePolicy {
    FAIL_CLOSED,
    FAIL_OPEN,
}

/** Candidate host hook registration with a matcher, per-hook timeout, and failure policy. */
@ExperimentalAgentRuntimeApi
data class RuntimeHookRegistration(
    val hook: RuntimeHook,
    val matcher: ToolMatcher = ToolMatcher.All,
    val timeoutMillis: Long = DEFAULT_HOOK_TIMEOUT_MILLIS,
    val failurePolicy: HookFailurePolicy = HookFailurePolicy.FAIL_CLOSED,
) {
    init {
        require(timeoutMillis > 0) { "timeoutMillis must be positive." }
    }
}

/** Candidate matcher used to decide whether a hook registration observes a tool call. */
@ExperimentalAgentRuntimeApi
fun interface ToolMatcher {
    fun matches(call: ToolUse): Boolean

    companion object {
        val All: ToolMatcher = ToolMatcher { true }

        fun toolName(toolName: String): ToolMatcher = ToolMatcher { call -> call.toolName == toolName }

        fun toolNames(toolNames: Set<String>): ToolMatcher = ToolMatcher { call -> call.toolName in toolNames }

        fun source(source: ToolSource): ToolMatcher = ToolMatcher { call -> call.source == source }
    }
}

/** Candidate hook decision. */
@ExperimentalAgentRuntimeApi
sealed interface HookDecision {
    data class Allow(
        val updatedArgumentsJson: String? = null,
    ) : HookDecision

    data class Deny(
        val code: String = RuntimeErrorCode.HOOK_DENIED.wireCode,
        val reason: String,
    ) : HookDecision

    data class RequireConfirmation(
        val reason: String,
        val prompt: String,
    ) : HookDecision

    data class BlockAndContinue(
        val reason: String,
        val prompt: String,
    ) : HookDecision
}

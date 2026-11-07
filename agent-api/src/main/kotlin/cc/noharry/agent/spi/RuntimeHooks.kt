package cc.noharry.agent.spi

import cc.noharry.agent.spi.reliability.RuntimeErrorCode
import cc.noharry.agent.TurnResult

/**
 * Host hook API for observing and controlling runtime tool use.
 */
interface RuntimeHook {
    /** Pre-tool callback. Returning Deny or RequireConfirmation prevents immediate execution. */
    suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision = HookDecision.Allow()

    /** Post-tool callback. Exceptions are recorded internally and do not fail the run. */
    suspend fun onPostToolUse(call: ToolUse, result: ToolUseResult, context: HookContext) {}

    /** Lifecycle callback for turn start. */
    suspend fun onTurnStarted(context: HookContext) {}

    /** Lifecycle callback for terminal turn completion. */
    suspend fun onTurnFinished(result: TurnResult, context: HookContext) {}
}

/** Record describing a model-visible tool call. */
data class ToolUse(
    val toolName: String,
    val argumentsJson: String,
    val source: ToolSource,
    val toolCallId: String = "",
)

/** Source taxonomy for runtime tool calls. */
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

/** Post-tool result record for hook observers. */
data class ToolUseResult(
    val ok: Boolean,
    val resultJson: String,
    val retryable: Boolean = false,
    val code: String? = null,
)

/** Context passed to host hooks and policy rules. */
data class HookContext(
    val sessionId: String,
    val runId: String,
    val requestId: String,
    val approvedConfirmation: Boolean = false,
)

/** Host hook failure policy for pre-tool callbacks. */
enum class HookFailurePolicy {
    FAIL_CLOSED,
    FAIL_OPEN,
}

/** Matcher used to decide whether a hook registration observes a tool call. */
fun interface ToolMatcher {
    fun matches(call: ToolUse): Boolean

    companion object {
        val All: ToolMatcher = ToolMatcher { true }

        fun toolName(toolName: String): ToolMatcher = ToolMatcher { call -> call.toolName == toolName }

        fun toolNames(toolNames: Set<String>): ToolMatcher = ToolMatcher { call -> call.toolName in toolNames }

        fun source(source: ToolSource): ToolMatcher = ToolMatcher { call -> call.source == source }
    }
}

/** Hook decision. */
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

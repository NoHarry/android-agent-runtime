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
    /** Public conversation id ([cc.noharry.agent.AgentThread.id]). */
    val sessionId: String,
    /** Unique execution-attempt id; it can change when a public turn is retried after process loss. */
    val runId: String,
    /** Public turn id ([cc.noharry.agent.TurnResult.turnId]), scoped to [sessionId]. */
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

    /**
     * Asks before the call runs. The thread's approval policy may answer for the person: APPROVE_ALL approves, REMEMBER
     * approves an area approved before, and a web host approved earlier in the turn is not asked again.
     *
     * With [alwaysAsk] the person answers this call themselves, every time: APPROVE_ALL and REMEMBER do not approve it,
     * nor does a remembered area or a yes given to another call earlier in the turn; DENY_ALL still refuses it without
     * asking. A yes covers this call only and is never remembered ([cc.noharry.agent.ApprovalScope.THREAD] counts as
     * ONCE), and the approval tells a client so ([cc.noharry.agent.Approval.alwaysAsk]). A subagent has nobody to ask:
     * such a call in a subagent is refused, whatever policy it inherited. When several hooks ask about one call, it
     * asks every time if one of them says so.
     */
    data class RequireConfirmation @JvmOverloads constructor(
        val reason: String,
        val prompt: String,
        val alwaysAsk: Boolean = false,
    ) : HookDecision

    data class BlockAndContinue(
        val reason: String,
        val prompt: String,
    ) : HookDecision
}

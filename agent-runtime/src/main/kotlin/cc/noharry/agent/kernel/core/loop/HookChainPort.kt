package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.ToolUse
import cc.noharry.agent.spi.ToolUseResult
import cc.noharry.agent.spi.reliability.RuntimeResult

/** Core-owned hook orchestration seam used by the protocol loop. */
internal interface HookChainPort {
    suspend fun preToolUseOutcome(call: ToolUse, context: HookContext): PreToolUseOutcome

    suspend fun postToolUse(call: ToolUse, result: ToolUseResult, context: HookContext)

    suspend fun onRunStarted(context: HookContext)

    suspend fun onRunFinished(result: RuntimeResult, context: HookContext)

    companion object {
        fun empty(): HookChainPort = EmptyHookChain
    }
}

internal sealed interface PreToolUseOutcome {
    data class Allowed(val toolUse: ToolUse) : PreToolUseOutcome

    data class Denied(
        val code: String,
        val reason: String,
    ) : PreToolUseOutcome

    data class RequiresConfirmation(
        val toolUse: ToolUse,
        val reason: String,
        val prompt: String,
    ) : PreToolUseOutcome

    data class BlockedAndContinue(
        val toolUse: ToolUse,
        val reason: String,
        val prompt: String,
    ) : PreToolUseOutcome
}

private object EmptyHookChain : HookChainPort {
    override suspend fun preToolUseOutcome(call: ToolUse, context: HookContext): PreToolUseOutcome =
        PreToolUseOutcome.Allowed(call)

    override suspend fun postToolUse(call: ToolUse, result: ToolUseResult, context: HookContext) = Unit

    override suspend fun onRunStarted(context: HookContext) = Unit

    override suspend fun onRunFinished(result: RuntimeResult, context: HookContext) = Unit
}

package cc.noharry.agent.kernel.core.state
import cc.noharry.agent.kernel.api.ConfirmationDecision
import cc.noharry.agent.kernel.api.PendingConfirmation
import cc.noharry.agent.kernel.core.loop.NativeLoopOutcome
import cc.noharry.agent.kernel.core.loop.PendingNativeToolUse
import java.util.concurrent.ConcurrentHashMap

class PendingToolUseController {
    private val states = ConcurrentHashMap<String, PendingNativeToolUse>()

    fun record(pending: PendingConfirmation, toolUse: PendingNativeToolUse) {
        states[pending.pendingId] = toolUse
    }

    fun remove(pendingId: String) {
        states.remove(pendingId)
    }

    fun clearRun(runId: String) {
        states.entries.forEach { entry ->
            if (entry.value.context.runId == runId) {
                states.remove(entry.key, entry.value)
            }
        }
    }

    fun cachedStateCount(): Int = states.size

    suspend fun resume(
        decision: ConfirmationDecision,
        execute: suspend (PendingNativeToolUse) -> NativeLoopOutcome,
    ): NativeLoopOutcome? {
        val state = states.remove(decision.pendingId) ?: return null
        return execute(state.copy(context = state.context.copy(requestId = decision.requestId)))
    }
}

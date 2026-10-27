package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.core.tools.MaterializerContext

sealed interface NativeLoopOutcome {
    val events: List<AgentEvent>

    data class Terminal(
        val result: RuntimeResult,
        val summary: NativeLoopSummary,
        override val events: List<AgentEvent> = emptyList(),
    ) : NativeLoopOutcome

    data class RequiresConfirmation(
        val prompt: String,
        val summary: NativeLoopSummary,
        override val events: List<AgentEvent> = emptyList(),
        val pendingToolUse: PendingNativeToolUse? = null,
    ) : NativeLoopOutcome

    data class NeedsUserInput(
        val question: String,
        val summary: NativeLoopSummary,
        override val events: List<AgentEvent> = emptyList(),
    ) : NativeLoopOutcome
}

data class PendingNativeToolUse(
    val toolUse: ToolUse,
    val context: MaterializerContext,
    val backendToolName: String? = null,
    val toolCallId: String,
    val historyMessages: List<ModelMessage> = emptyList(),
    val budgetSnapshot: LoopBudgetSnapshot = LoopBudgetSnapshot(),
    val reasoningContent: String? = null,
    /** The round's thinking signature; Claude checks it when the thinking comes back with this call. */
    val thinkingSignature: String? = null,
    /** What the model told the user before this call, when this call is the round's first. */
    val assistantText: String = "",
)

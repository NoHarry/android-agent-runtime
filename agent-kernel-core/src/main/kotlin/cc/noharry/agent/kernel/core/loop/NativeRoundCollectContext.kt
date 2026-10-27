package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.core.tools.DynamicToolSet
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.kernel.core.trace.NativePublicEventBuffer

data class RoundCollectContext(
    val toolResults: MutableList<ModelMessage>,
    val toolContext: MaterializerContext,
    val summaryBuilder: NativeLoopSummaryBuilder,
    val publicEvents: NativePublicEventBuffer,
    val toolSet: DynamicToolSet,
    val budgetTracker: LoopBudgetTracker,
    val thinking: StringBuilder,
    val visibleText: String = "",
    val thinkingSignature: String? = null,
    val allowFinishResponseRetry: Boolean = false,
    val allowObligationCorrection: Boolean = false,
    val obligationLedger: RuntimeObligationLedger = RuntimeObligationLedger(),
    /** call_tool calls unwrapped this round, by call id, so their messages keep the form the model sent. */
    val wireCalls: MutableMap<String, ModelEvent.ToolCall> = mutableMapOf(),
) {
    private var reasoningConsumed = false

    fun reasoningContent(): String? =
        thinking.takeIf { it.isNotBlank() }?.toString()

    fun nextReasoningContentForHistory(): String? {
        val content = reasoningContent() ?: return null
        if (!thinkingSignature.isNullOrBlank()) return content
        if (reasoningConsumed) return DEDUPED_REASONING_MARKER
        reasoningConsumed = true
        return content
    }
}

const val DEDUPED_REASONING_MARKER = "(same reasoning as previous tool call)"

@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.trace
import cc.noharry.agent.kernel.api.WorkspaceExecutionTelemetry
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelImage
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.core.state.StoredModelCall
import cc.noharry.agent.kernel.core.tools.DynamicToolSource
import cc.noharry.agent.kernel.core.tools.MaterializerContext

/** Session/event recording seam used by the protocol loop. */
interface LoopSessionRecorder : ModelCallRecorder {
    /** Records the complete question/input fact in the same canonical event stream. */
    fun recordInteraction(context: MaterializerContext, interaction: cc.noharry.agent.kernel.api.SessionEventPayload.UserInteraction) = Unit

    fun appendMessage(context: MaterializerContext, message: ModelMessage)

    fun appendMessages(context: MaterializerContext, messages: List<ModelMessage>)

    fun recordToolStarted(
        context: MaterializerContext,
        event: ModelEvent.ToolCall,
        source: DynamicToolSource?,
    )

    fun recordToolFinished(
        context: MaterializerContext,
        event: ModelEvent.ToolCall,
        source: DynamicToolSource?,
        resultJson: String,
        telemetry: WorkspaceExecutionTelemetry = WorkspaceExecutionTelemetry(),
    )

    /**
     * Records a finished tool together with the images its provider returned. Recorders that keep
     * no attachments inherit plain [recordToolFinished]; the images still reach the next request.
     */
    fun recordToolFinishedWithImages(
        context: MaterializerContext,
        event: ModelEvent.ToolCall,
        source: DynamicToolSource?,
        resultJson: String,
        images: List<ModelImage>,
        telemetry: WorkspaceExecutionTelemetry = WorkspaceExecutionTelemetry(),
    ) = recordToolFinished(context, event, source, resultJson, telemetry)

    fun recordUsage(context: MaterializerContext, delta: ModelEvent.UsageDelta)

    fun recordModelRequest(context: MaterializerContext, request: ModelRequest) = Unit

    fun recordModelStreamEvent(context: MaterializerContext, event: ModelEvent) = Unit

    /** Number-only diagnostics, attached to the next canonical model completion, not a second content log. */
    fun recordPreparationTimings(context: MaterializerContext, timingsMs: Map<String, Long>) = Unit

    companion object {
        fun noop(): LoopSessionRecorder = NoOpLoopSessionRecorder
    }
}

private object NoOpLoopSessionRecorder : LoopSessionRecorder {
    override fun nextModelCallId(runId: String): String = "call-$runId-0"

    override fun recordModelCall(call: StoredModelCall) = Unit

    override fun appendMessage(context: MaterializerContext, message: ModelMessage) = Unit

    override fun appendMessages(context: MaterializerContext, messages: List<ModelMessage>) = Unit

    override fun recordToolStarted(
        context: MaterializerContext,
        event: ModelEvent.ToolCall,
        source: DynamicToolSource?,
    ) = Unit

    override fun recordToolFinished(
        context: MaterializerContext,
        event: ModelEvent.ToolCall,
        source: DynamicToolSource?,
        resultJson: String,
        telemetry: WorkspaceExecutionTelemetry,
    ) = Unit

    override fun recordUsage(context: MaterializerContext, delta: ModelEvent.UsageDelta) = Unit
}

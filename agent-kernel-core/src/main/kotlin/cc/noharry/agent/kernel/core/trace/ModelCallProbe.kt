package cc.noharry.agent.kernel.core.trace
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelResponseMetadata
import cc.noharry.agent.kernel.core.state.StoredModelCall

interface ModelCallRecorder {
    fun nextModelCallId(runId: String): String

    fun recordModelCall(call: StoredModelCall)
}

class ModelCallProbe(
    private val call: StoredModelCall,
    private val nowEpochMillis: () -> Long,
    private val recorder: ModelCallRecorder,
) {
    private var firstEventAt: Long? = null
    private var firstToolCallAt: Long? = null
    private var eventCount = 0
    private var thinkingEventCount = 0
    private var textEventCount = 0
    private var toolCallEventCount = 0
    private var usageEventCount = 0
    private var inputTokens = 0
    private var outputTokens = 0
    private var reasoningTokens = 0
    private var cacheReadTokens = 0
    private var cacheWriteTokens = 0
    private var providerModel = ""
    private var systemFingerprint = ""
    private var recorded = false

    fun recordEvent(event: ModelEvent) {
        val now = nowEpochMillis()
        if (firstEventAt == null) firstEventAt = now
        eventCount += 1
        when (event) {
            is ModelEvent.ThinkingDelta -> thinkingEventCount += 1
            is ModelEvent.TextDelta -> textEventCount += 1
            is ModelEvent.ToolCall -> {
                toolCallEventCount += 1
                if (firstToolCallAt == null) firstToolCallAt = now
            }
            is ModelEvent.UsageDelta -> {
                usageEventCount += 1
                inputTokens += event.inputTokens
                outputTokens += event.outputTokens
                reasoningTokens += event.reasoningTokens
                cacheReadTokens += event.cacheReadTokens
                cacheWriteTokens += event.cacheWriteTokens
            }
            is ModelResponseMetadata -> {
                if (event.model.isNotBlank()) providerModel = event.model
                if (event.systemFingerprint.isNotBlank()) systemFingerprint = event.systemFingerprint
            }
            is ModelEvent.SearchActivity -> Unit
            ModelEvent.Completed -> Unit
        }
    }

    fun finish(status: String, errorCode: String? = null) {
        if (recorded) return
        recorded = true
        val finishedAt = nowEpochMillis()
        recorder.recordModelCall(
            call.copy(
                status = status,
                firstEventAtEpochMillis = firstEventAt,
                firstToolCallAtEpochMillis = firstToolCallAt,
                finishedAtEpochMillis = finishedAt,
                durationMs = (finishedAt - call.startedAtEpochMillis).coerceAtLeast(0L),
                eventCount = eventCount,
                thinkingEventCount = thinkingEventCount,
                textEventCount = textEventCount,
                toolCallEventCount = toolCallEventCount,
                usageEventCount = usageEventCount,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                reasoningTokens = reasoningTokens,
                cacheReadTokens = cacheReadTokens,
                cacheWriteTokens = cacheWriteTokens,
                providerModel = providerModel,
                systemFingerprint = systemFingerprint,
                errorCode = errorCode,
            ),
        )
    }
}

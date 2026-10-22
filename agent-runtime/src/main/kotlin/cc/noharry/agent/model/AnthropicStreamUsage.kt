package cc.noharry.agent.model

import cc.noharry.agent.kernel.api.model.ModelEvent
import kotlinx.serialization.json.JsonObject

/** Anthropic stream usage frames are partial cumulative snapshots, not additive counters. */
internal class AnthropicStreamUsage {
    private var snapshot = JsonObject(emptyMap())
    private var emitted = ModelEvent.UsageDelta()

    fun update(fields: JsonObject): ModelEvent.UsageDelta? {
        snapshot = JsonObject(snapshot + fields)
        val current = snapshot.modelUsageDelta() ?: return null
        val delta = ModelEvent.UsageDelta(
            inputTokens = (current.inputTokens - emitted.inputTokens).coerceAtLeast(0),
            outputTokens = (current.outputTokens - emitted.outputTokens).coerceAtLeast(0),
            reasoningTokens = (current.reasoningTokens - emitted.reasoningTokens).coerceAtLeast(0),
            cacheReadTokens = (current.cacheReadTokens - emitted.cacheReadTokens).coerceAtLeast(0),
            cacheWriteTokens = (current.cacheWriteTokens - emitted.cacheWriteTokens).coerceAtLeast(0),
        )
        emitted = ModelEvent.UsageDelta(
            inputTokens = maxOf(emitted.inputTokens, current.inputTokens),
            outputTokens = maxOf(emitted.outputTokens, current.outputTokens),
            reasoningTokens = maxOf(emitted.reasoningTokens, current.reasoningTokens),
            cacheReadTokens = maxOf(emitted.cacheReadTokens, current.cacheReadTokens),
            cacheWriteTokens = maxOf(emitted.cacheWriteTokens, current.cacheWriteTokens),
        )
        return delta.takeUnless { it == ModelEvent.UsageDelta() }
    }
}

@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionEventPayload

/** Scalar projection for current tests and Sample UI until they read typed fields directly. */
internal fun SessionEventPayload.scalarFields(): Map<String, String> = when (this) {
    SessionEventPayload.Empty -> emptyMap()
    is SessionEventPayload.UserInteraction -> mapOf("interactionKind" to kind, "state" to state, "questionId" to questionId, "inputRequestId" to inputRequestId)
    is SessionEventPayload.RunLifecycle -> buildMap {
        putIfNotBlank("phase", phase)
        putIfNotBlank("status", status)
        putIfNotBlank("modelRef", modelRef)
        putIfNotBlank("scene", scene)
        putIfNotBlank("errorCode", errorCode)
        putIfNotBlank("todoCompletion", todoCompletion)
    }
    is SessionEventPayload.UserMessage -> mapOf("role" to "user")
    is SessionEventPayload.ModelRequest -> buildMap {
        putIfNotBlank("modelRef", modelRef)
        putIfNotBlank("toolChoice", toolChoice)
        putIfNotBlank("promptCacheKey", promptCacheKey)
    }
    is SessionEventPayload.AssistantChunk -> mapOf("kind" to kind)
    is SessionEventPayload.AssistantMessage -> buildMap {
        putIfNotBlank("finishReason", finishReason)
        put("role", "assistant")
    }
    is SessionEventPayload.ToolCall -> buildMap {
        putIfNotBlank("callId", toolCallId)
        putIfNotBlank("toolName", name)
        putIfNotBlank("source", source)
        putIfNotBlank("schemaRef", schemaRef)
        putIfNotBlank("todoCompletion", todoCompletion)
    }
    is SessionEventPayload.ToolResult -> buildMap {
        putIfNotBlank("callId", toolCallId)
        putIfNotBlank("toolName", name)
        putIfNotBlank("source", source)
        put("ok", ok.toString())
        putIfNotBlank("code", code)
        put("retryable", retryable.toString())
        putIfNotBlank("status", status)
        putIfNotBlank("todoCompletion", todoCompletion)
    }
    is SessionEventPayload.Usage -> mapOf(
        "inputTokens" to inputTokens.toString(),
        "outputTokens" to outputTokens.toString(),
        "reasoningTokens" to reasoningTokens.toString(),
        "cacheReadTokens" to cacheReadTokens.toString(),
        "cacheWriteTokens" to cacheWriteTokens.toString(),
    )
    is SessionEventPayload.ModelResponseMetadata -> emptyMap()
    is SessionEventPayload.ModelCallFinished -> buildMap {
        putIfNotBlank("callId", callId)
        putIfNotBlank("status", status)
        putIfNotBlank("source", source)
        putIfNotBlank("modelRef", modelRef)
        put("durationMs", durationMs.toString())
        put("messageCount", messageCount.toString())
        put("toolCount", toolCount.toString())
        put("eventCount", eventCount.toString())
        putIfNotBlank("errorCode", errorCode)
    }
}

private fun MutableMap<String, String>.putIfNotBlank(key: String, value: String) {
    if (value.isNotBlank()) put(key, value)
}

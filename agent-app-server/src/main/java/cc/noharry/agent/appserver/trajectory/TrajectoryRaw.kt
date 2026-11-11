@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory
import cc.noharry.agent.appserver.protocol.TrajectoryRichAvailability
import cc.noharry.agent.appserver.runtime.append
import cc.noharry.agent.spi.SessionAttachmentRef
import cc.noharry.agent.spi.SessionEvent
import cc.noharry.agent.spi.SessionEventPayload
import cc.noharry.agent.spi.SessionJson

/**
 * Unbounded canonical Raw. Preview tabs may truncate; Raw must not. Pagination happens in
 * the inspector after this string is built.
 */
internal fun SessionEvent.renderRawText(): String = buildString {
    appendLine("schemaVersion=$schemaVersion")
    appendLine("eventId=$eventId")
    appendLine("sessionId=$sessionId")
    appendLine("runId=$runId")
    appendLine("requestId=$requestId")
    appendLine("sequence=$sequence")
    appendLine("recordedAtEpochMillis=$recordedAtEpochMillis")
    appendLine("type=$type")
    appendLine("sourceEventSequences=$sourceEventSequences")
    append(payload.renderRawBody())
}

internal fun SessionEventPayload.isSearchActivity(): Boolean =
    this is SessionEventPayload.ModelResponseMetadata &&
        textField(fields, "kind") == SEARCH_ACTIVITY_KIND

internal fun SessionEventPayload.richAvailability(): TrajectoryRichAvailability = when (this) {
    SessionEventPayload.Empty -> TrajectoryRichAvailability.UNAVAILABLE
    is SessionEventPayload.ModelRequest ->
        if (attachments.isNotEmpty() && !hasImageBytes()) {
            TrajectoryRichAvailability.HASH_ONLY
        } else {
            TrajectoryRichAvailability.AVAILABLE
        }
    else -> TrajectoryRichAvailability.AVAILABLE
}

internal fun canonicalToolCallId(runId: String, raw: String): String {
    if (raw.isBlank() || runId.isBlank()) return raw
    val prefix = "$runId:"
    return if (raw.startsWith(prefix)) raw.removePrefix(prefix) else raw
}

private fun SessionEventPayload.renderRawBody(): String = when (this) {
    SessionEventPayload.Empty -> "payload.kind=Empty"
    is SessionEventPayload.UserInteraction -> "payload.kind=UserInteraction\ninteractionKind=$kind\nstate=$state\ninputRequestId=$inputRequestId\nquestionId=$questionId\ntext=$text\noptions=$options\nreason=$reason"
    is SessionEventPayload.RunLifecycle -> renderRunLifecycle()
    is SessionEventPayload.UserMessage -> renderUserMessage()
    is SessionEventPayload.ModelRequest -> renderModelRequest()
    is SessionEventPayload.AssistantChunk -> renderAssistantChunk()
    is SessionEventPayload.AssistantMessage -> renderAssistantMessage()
    is SessionEventPayload.ToolCall -> renderToolCall()
    is SessionEventPayload.ToolResult -> renderToolResult()
    is SessionEventPayload.Usage -> renderUsage()
    is SessionEventPayload.ModelResponseMetadata -> "payload.kind=ModelResponseMetadata\nfields=${fields.render()}"
    is SessionEventPayload.ModelCallFinished -> renderModelCallFinished()
}

private fun SessionEventPayload.RunLifecycle.renderRunLifecycle(): String = buildString {
    appendLine("payload.kind=RunLifecycle")
    appendLine("phase=$phase")
    appendLine("status=$status")
    appendLine("modelRef=$modelRef")
    appendLine("scene=$scene")
    appendLine("errorCode=$errorCode")
    append("todoCompletion=$todoCompletion")
}

private fun SessionEventPayload.UserMessage.renderUserMessage(): String = buildString {
    appendLine("payload.kind=UserMessage")
    appendLine("text=$text")
    append("attachments=${attachments.renderRefs()}")
}

private fun SessionEventPayload.ModelRequest.renderModelRequest(): String = buildString {
    appendLine("payload.kind=ModelRequest")
    appendLine("modelRef=$modelRef")
    appendLine("toolChoice=$toolChoice")
    appendLine("promptCacheKey=$promptCacheKey")
    appendLine("options=${options.render()}")
    appendLine("attachments=${attachments.renderRefs()}")
    appendLine("systemPrompt=$systemPrompt")
    appendLine("messages=${messages.render()}")
    append("tools=${tools.render()}")
}

private fun SessionEventPayload.AssistantChunk.renderAssistantChunk(): String = buildString {
    appendLine("payload.kind=AssistantChunk")
    appendLine("kind=$kind")
    appendLine("text=$text")
    appendLine("signature=$signature")
    append("metadata=${metadata.render()}")
}

private fun SessionEventPayload.AssistantMessage.renderAssistantMessage(): String = buildString {
    appendLine("payload.kind=AssistantMessage")
    appendLine("text=$text")
    appendLine("toolCalls=${toolCalls.render()}")
    appendLine("finishReason=$finishReason")
    appendLine("usage=${usage.summaryLine()}")
    append("sourceChunkSequences=$sourceChunkSequences")
}

private fun SessionEventPayload.ToolCall.renderToolCall(): String = buildString {
    appendLine("payload.kind=ToolCall")
    appendLine("toolCallId=$toolCallId")
    appendLine("name=$name")
    appendLine("source=$source")
    appendLine("schemaRef=$schemaRef")
    appendLine("todoCompletion=$todoCompletion")
    append("arguments=${arguments.render()}")
}

private fun SessionEventPayload.ToolResult.renderToolResult(): String = buildString {
    appendLine("payload.kind=ToolResult")
    appendLine("toolCallId=$toolCallId")
    appendLine("name=$name")
    appendLine("ok=$ok")
    appendLine("code=$code")
    appendLine("retryable=$retryable")
    appendLine("source=$source")
    appendLine("status=$status")
    appendLine("todoCompletion=$todoCompletion")
    appendLine("attachments=${attachments.renderRefs()}")
    append("result=${result.render()}")
}

private fun SessionEventPayload.Usage.renderUsage(): String = buildString {
    appendLine("payload.kind=Usage")
    appendLine("inputTokens=$inputTokens")
    appendLine("outputTokens=$outputTokens")
    appendLine("reasoningTokens=$reasoningTokens")
    appendLine("cacheReadTokens=$cacheReadTokens")
    appendLine("cacheWriteTokens=$cacheWriteTokens")
    append("extras=${extras.render()}")
}

private fun SessionEventPayload.ModelCallFinished.renderModelCallFinished(): String = buildString {
    appendLine("payload.kind=ModelCallFinished")
    appendLine("callId=$callId")
    appendLine("status=$status")
    appendLine("source=$source")
    appendLine("modelRef=$modelRef")
    appendLine("durationMs=$durationMs")
    appendLine("messageCount=$messageCount")
    appendLine("toolCount=$toolCount")
    appendLine("eventCount=$eventCount")
    append("errorCode=$errorCode")
}

private fun SessionEventPayload.Usage.summaryLine(): String =
    "in=$inputTokens out=$outputTokens reasoning=$reasoningTokens"

private fun List<SessionAttachmentRef>.renderRefs(): String =
    joinToString(prefix = "[", postfix = "]") { ref ->
        "{sha256=${ref.sha256}, mediaType=${ref.mediaType}, byteSize=${ref.byteSize}, logicalName=${ref.logicalName}}"
    }

/** Bytes are inline (`bytesBase64`) or in the session attachment store (`storage`). */
private fun SessionEventPayload.ModelRequest.hasImageBytes(): Boolean {
    val messages = messages as? SessionJson.Array ?: return false
    return messages.values.any { message ->
        val attachments = ((message as? SessionJson.Object)?.values?.get("attachments") as? SessionJson.Array)
            ?: return@any false
        attachments.values.any { attachment ->
            textField(attachment, "bytesBase64").orEmpty().isNotBlank() ||
                textField(attachment, "storage") == SESSION_ATTACHMENT_STORAGE
        }
    }
}

/** Mirrors the runtime marker for attachments whose bytes live in the session attachment store. */
private const val SESSION_ATTACHMENT_STORAGE = "session-attachment"

internal fun textField(value: SessionJson, key: String): String? =
    ((value as? SessionJson.Object)?.values?.get(key) as? SessionJson.Text)?.value

internal fun numberField(value: SessionJson, key: String): String? =
    ((value as? SessionJson.Object)?.values?.get(key) as? SessionJson.Number)?.literal

internal const val SEARCH_ACTIVITY_KIND = "search_activity"

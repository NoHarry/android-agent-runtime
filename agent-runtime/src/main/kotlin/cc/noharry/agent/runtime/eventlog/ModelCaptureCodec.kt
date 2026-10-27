@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionAttachmentRef
import cc.noharry.agent.kernel.api.SessionEventPayload
import cc.noharry.agent.kernel.api.SessionJson
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelImage
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelResponseMetadata
import cc.noharry.agent.kernel.api.model.ModelTool
import java.security.MessageDigest
import java.util.IdentityHashMap

/**
 * [storeAttachment] returns true once an image is in the session attachment store under the given reference; the
 * message JSON then keeps only that reference. Images that could not be stored stay inline as `bytesBase64`. Each
 * image is hashed once per request.
 */
internal fun ModelRequest.toSessionPayload(
    storeAttachment: (ModelImage, SessionAttachmentRef) -> Boolean = { _, _ -> false },
): SessionEventPayload.ModelRequest {
    val refs = IdentityHashMap<ModelImage, SessionAttachmentRef>()
    val refOf = { image: ModelImage -> refs.getOrPut(image) { image.toAttachmentRef() } }
    return SessionEventPayload.ModelRequest(
        messages = messages.toMessageSessionJson { image ->
            val ref = refOf(image)
            image.toAttachmentJson(ref, storeAttachment(image, ref))
        },
        systemPrompt = system.orEmpty(),
        tools = tools.toToolSessionJson(),
        modelRef = modelRef?.wireValue().orEmpty(),
        toolChoice = toolChoice.orEmpty(),
        promptCacheKey = promptCacheKey.orEmpty(),
        options = SessionJson.Null,
        attachments = messages.flatMap { it.images }.map(refOf),
    )
}

internal fun ModelEvent.toSessionAppendPayload(): SessionEventPayload? = when (this) {
    is ModelEvent.ThinkingDelta -> SessionEventPayload.AssistantChunk(
        kind = "thinking",
        text = text,
        signature = signature.orEmpty(),
    )
    is ModelEvent.TextDelta -> SessionEventPayload.AssistantChunk(
        kind = "text",
        text = text,
    )
    is ModelEvent.ToolCall -> SessionEventPayload.ToolCall(
        toolCallId = id,
        name = name,
        arguments = parseJsonOrText(argumentsJson),
        source = MODEL_TOOL_SOURCE,
    )
    is ModelResponseMetadata -> SessionEventPayload.ModelResponseMetadata(fields = metadataFields())
    is ModelEvent.SearchActivity -> SessionEventPayload.ModelResponseMetadata(fields = searchFields())
    is ModelEvent.UsageDelta,
    ModelEvent.Completed,
    -> null
}

private fun List<ModelMessage>.toMessageSessionJson(attachmentJson: (ModelImage) -> SessionJson.Object): SessionJson =
    SessionJson.Array(
        map { message ->
            SessionJson.Object(
                buildMap {
                    put("role", SessionJson.Text(message.role))
                    put("content", SessionJson.Text(message.content))
                    message.toolCallId?.takeIf { it.isNotBlank() }?.let { put("toolCallId", SessionJson.Text(it)) }
                    message.toolName?.takeIf { it.isNotBlank() }?.let { put("toolName", SessionJson.Text(it)) }
                    message.toolArgumentsJson?.takeIf { it.isNotBlank() }?.let {
                        put("toolArguments", parseJsonOrText(it))
                    }
                    message.thinkingSignature?.takeIf { it.isNotBlank() }?.let {
                        put("thinkingSignature", SessionJson.Text(it))
                    }
                    if (message.toolAdditions.isNotEmpty()) {
                        put("toolAdditions", SessionJson.Array(message.toolAdditions.map { SessionJson.Text(it) }))
                    }
                    message.assistantText.takeIf { it.isNotBlank() }?.let { put("assistantText", SessionJson.Text(it)) }
                    if (message.images.isNotEmpty()) {
                        put("attachments", SessionJson.Array(message.images.map(attachmentJson)))
                    }
                },
            )
        },
    )

private fun List<ModelTool>.toToolSessionJson(): SessionJson =
    SessionJson.Array(
        map { tool ->
            SessionJson.Object(
                buildMap {
                    put("name", SessionJson.Text(tool.name))
                    tool.description.takeIf { it.isNotBlank() }?.let { put("description", SessionJson.Text(it)) }
                    put("schema", parseJsonOrText(tool.schemaJson))
                    if (tool.deferred) put("deferred", SessionJson.Bool(true))
                },
            )
        },
    )

internal fun ModelImage.toAttachmentRef(): SessionAttachmentRef =
    SessionAttachmentRef(
        sha256 = sha256Hex(),
        mediaType = mediaType,
        byteSize = bytes.size.toLong(),
        logicalName = sourcePath,
    )

private fun ModelImage.toAttachmentJson(ref: SessionAttachmentRef, stored: Boolean): SessionJson.Object =
    SessionJson.Object(
        buildMap {
            put("sha256", SessionJson.Text(ref.sha256))
            put("mediaType", SessionJson.Text(mediaType))
            put("byteSize", SessionJson.Number(bytes.size.toString()))
            put("logicalName", SessionJson.Text(sourcePath))
            put("detail", SessionJson.Text(detail))
            if (stored) {
                put("storage", SessionJson.Text(SESSION_ATTACHMENT_STORAGE))
            } else {
                put("bytesBase64", SessionJson.Text(java.util.Base64.getEncoder().encodeToString(bytes)))
            }
        },
    )

private fun ModelResponseMetadata.metadataFields(): SessionJson =
    SessionJson.Object(
        buildMap {
            if (model.isNotBlank()) put("model", SessionJson.Text(model))
            if (systemFingerprint.isNotBlank()) {
                put("systemFingerprint", SessionJson.Text(systemFingerprint))
            }
        },
    )

private fun ModelEvent.SearchActivity.searchFields(): SessionJson =
    SessionJson.Object(
        buildMap {
            put("kind", SessionJson.Text(SEARCH_ACTIVITY_KIND))
            if (provider.isNotBlank()) put("provider", SessionJson.Text(provider))
            if (queryPreview.isNotBlank()) put("queryPreview", SessionJson.Text(queryPreview))
            resultCount?.let { put("resultCount", SessionJson.Number(it.toString())) }
        },
    )

private fun ModelImage.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal const val MODEL_TOOL_SOURCE = "model"
/** Marks an attachment whose bytes live in the session attachment store, not inline. */
internal const val SESSION_ATTACHMENT_STORAGE = "session-attachment"
internal const val SEARCH_ACTIVITY_KIND = "search_activity"

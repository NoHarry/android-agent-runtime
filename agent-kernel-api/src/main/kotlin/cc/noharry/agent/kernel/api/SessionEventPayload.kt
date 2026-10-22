package cc.noharry.agent.kernel.api

/**
 * Typed lossless payload for a [SessionEvent].
 *
 * Persist complete model-visible and tool-visible logical data. API keys, Authorization
 * headers, bearer tokens, cookies and transport credentials must be removed before the
 * writer accepts the event.
 */
@ExperimentalAgentRuntimeApi
sealed class SessionEventPayload {
    data object Empty : SessionEventPayload()

    /** Candidate question/input lifecycle fact. Content is complete; delivery is not model approval. */
    data class UserInteraction(
        val kind: String,
        val state: String,
        val inputRequestId: String = "",
        val questionId: String = "",
        val text: String = "",
        val options: List<String> = emptyList(),
        val reason: String = "",
    ) : SessionEventPayload()

    data class RunLifecycle(
        val phase: String = "",
        val status: String = "",
        val modelRef: String = "",
        val scene: String = "",
        val errorCode: String = "",
        val todoCompletion: String = "",
    ) : SessionEventPayload()

    data class UserMessage(
        val text: String,
        val attachments: List<SessionAttachmentRef> = emptyList(),
    ) : SessionEventPayload()

    data class ModelRequest(
        val messages: SessionJson = SessionJson.Null,
        val systemPrompt: String = "",
        val tools: SessionJson = SessionJson.Null,
        val modelRef: String = "",
        val toolChoice: String = "",
        val promptCacheKey: String = "",
        val options: SessionJson = SessionJson.Null,
        val attachments: List<SessionAttachmentRef> = emptyList(),
    ) : SessionEventPayload()

    data class AssistantChunk(
        val kind: String,
        val text: String,
        val signature: String = "",
        val metadata: SessionJson = SessionJson.Null,
    ) : SessionEventPayload()

    data class AssistantMessage(
        val text: String = "",
        val toolCalls: SessionJson = SessionJson.Null,
        val finishReason: String = "",
        val usage: Usage = Usage(),
        val sourceChunkSequences: List<Long> = emptyList(),
    ) : SessionEventPayload()

    data class ToolCall(
        val toolCallId: String,
        val name: String,
        val arguments: SessionJson = SessionJson.Null,
        val schemaRef: String = "",
        val source: String = "",
        val todoCompletion: String = "",
    ) : SessionEventPayload()

    data class ToolResult(
        val toolCallId: String,
        val name: String,
        val result: SessionJson = SessionJson.Null,
        val ok: Boolean = true,
        val code: String = "",
        val retryable: Boolean = false,
        val source: String = "",
        val status: String = "",
        val todoCompletion: String = "",
        /** Candidate: images returned with the result, stored once per session by content hash. */
        val attachments: List<SessionAttachmentRef> = emptyList(),
    ) : SessionEventPayload()

    data class Usage(
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val reasoningTokens: Long = 0,
        val cacheReadTokens: Long = 0,
        val cacheWriteTokens: Long = 0,
        val extras: SessionJson = SessionJson.Null,
    ) : SessionEventPayload()

    data class ModelResponseMetadata(
        val fields: SessionJson = SessionJson.Null,
    ) : SessionEventPayload()

    data class ModelCallFinished(
        val callId: String = "",
        val status: String = "",
        val source: String = "",
        val modelRef: String = "",
        val durationMs: Long = 0,
        val messageCount: Int = 0,
        val toolCount: Int = 0,
        val eventCount: Int = 0,
        val errorCode: String = "",
    ) : SessionEventPayload()
}

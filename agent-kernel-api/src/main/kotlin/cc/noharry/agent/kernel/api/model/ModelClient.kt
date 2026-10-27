package cc.noharry.agent.kernel.api.model

import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import kotlinx.coroutines.flow.Flow

interface ModelClient {
    /**
     * Candidate concurrency declaration: how many requests the runtime may send at once. The default,
     * [ProviderConcurrencyDeclaration.serialized], sends one at a time; a client that can take overlapping requests
     * declares [ProviderConcurrencyDeclaration.threadSafe] or [ProviderConcurrencyDeclaration.sessionFactory].
     */
    @ExperimentalAgentRuntimeApi
    val providerConcurrency: ProviderConcurrencyDeclaration
        get() = ProviderConcurrencyDeclaration.serialized()

    fun complete(request: ModelRequest): Flow<ModelEvent>
}

data class ModelRequest(
    val messages: List<ModelMessage>,
    val tools: List<ModelTool> = emptyList(),
    val system: String? = null,
    val modelRef: ModelRef? = null,
    /** Experimental provider prompt-cache hint. Not frozen until API review closes. */
    @property:ExperimentalAgentRuntimeApi
    val promptCacheKey: String? = null,
    /** Experimental provider tool-selection hint. Not frozen until API review closes. */
    @property:ExperimentalAgentRuntimeApi
    val toolChoice: String? = null,
)

/** Candidate provider/model/variant reference. Not frozen until API review closes. */
data class ModelRef(
    val providerId: String,
    val modelId: String,
    val variant: String? = null,
) {
    /** Safe Java constructor for provider/model callers that omit a variant. */
    constructor(providerId: String, modelId: String) : this(
        providerId = providerId,
        modelId = modelId,
        variant = null,
    )
    fun normalized(): ModelRef =
        copy(
            providerId = providerId.trim(),
            modelId = modelId.trim(),
            variant = variant?.trim()?.takeIf { it.isNotEmpty() },
        )

    fun wireValue(): String {
        val normalized = normalized()
        return listOfNotNull(
            normalized.providerId.takeIf { it.isNotBlank() },
            normalized.modelId.takeIf { it.isNotBlank() },
            normalized.variant?.takeIf { it.isNotBlank() },
        ).joinToString(":")
    }
}

/** Candidate model reasoning variant summary. Not frozen until API review closes. */
data class ModelVariant(
    val id: String,
    val label: String,
)

data class ModelMessage(
    val role: String,
    val content: String,
    val images: List<ModelImage> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgumentsJson: String? = null,
    /**
     * Anthropic extended-thinking signature. When non-blank, the Anthropic
     * serializer emits a `thinking` content block (using [content] as the
     * thinking text) alongside any `tool_use` block. This is required by
     * Anthropic's thinking-mode contract: thinking blocks must be passed back
     * verbatim in subsequent turns. OpenAI serialization ignores this field.
     */
    val thinkingSignature: String? = null,
    /**
     * Backend tools this message loads into the conversation, for providers with mid-conversation tool changes
     * (DeepSeek's `tool_addition`): a tool sent [ModelTool.deferred] becomes callable from here on, and the request's
     * tool list need not change. A message carrying them is a system update. Not frozen until API review closes.
     */
    @property:ExperimentalAgentRuntimeApi
    val toolAdditions: List<String> = emptyList(),
    /**
     * What the model said to the user in the round that made this tool call ("帮你调高一点。"), kept on the round's
     * first call so the next request shows the model its own words; [content] stays the call's reasoning. Empty on
     * other messages. Not frozen until API review closes.
     */
    @property:ExperimentalAgentRuntimeApi
    val assistantText: String = "",
)

/** Candidate bounded image attachment for provider multimodal requests. */
data class ModelImage(
    val bytes: ByteArray,
    val mediaType: String,
    val sourcePath: String,
    val detail: String = IMAGE_DETAIL_AUTO,
) {
    init {
        require(mediaType in ALLOWED_IMAGE_MEDIA_TYPES) { "Unsupported image media type: $mediaType" }
        require(bytes.isNotEmpty()) { "Image bytes must not be empty." }
        require(bytes.size <= MAX_MODEL_IMAGE_BYTES) { "Image exceeds max bytes: $sourcePath" }
        require(detail in ALLOWED_IMAGE_DETAIL_VALUES) { "Unsupported image detail: $detail" }
    }
}

data class ModelTool(
    val name: String,
    val schemaJson: String,
    /**
     * Declared but not loaded (`defer_loading`): it costs no input and cannot be called until a message's
     * [ModelMessage.toolAdditions] names it. Not frozen until API review closes.
     */
    @property:ExperimentalAgentRuntimeApi
    val deferred: Boolean = false,
    /** What the tool does, sent with its definition; native tools keep theirs in the schema. Not frozen until API review closes. */
    @property:ExperimentalAgentRuntimeApi
    val description: String = "",
)

/** Candidate provider web_search capability declaration. Not frozen until API review closes. */
enum class ServerSideSearchCapability(val wireName: String) {
    ANTHROPIC_SERVER_TOOL("anthropic-server-tool"),
    OPENAI_CHAT_OPTIONS("openai-chat-options"),
    OPENAI_RESPONSES_TOOL("openai-responses-tool"),
    NONE("none"),
    ;

    companion object {
        fun fromWireName(value: String?): ServerSideSearchCapability =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: NONE
    }
}

sealed interface ModelEvent {
    data class ThinkingDelta(
        val text: String,
        /** Anthropic extended-thinking signature; null for non-Anthropic providers. */
        val signature: String? = null,
    ) : ModelEvent

    data class TextDelta(val text: String) : ModelEvent

    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String,
    ) : ModelEvent

    data class UsageDelta(
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val reasoningTokens: Int = 0,
        val cacheReadTokens: Int = 0,
        val cacheWriteTokens: Int = 0,
    ) : ModelEvent

    data class SearchActivity(
        val provider: String,
        val queryPreview: String = "",
        val resultCount: Int? = null,
    ) : ModelEvent

    data object Completed : ModelEvent
}

/** Provider response metadata event. Public so implementation modules can emit it. */
data class ModelResponseMetadata(
    val model: String = "",
    val systemFingerprint: String = "",
) : ModelEvent

internal const val MAX_MODEL_IMAGE_BYTES = 4 * 1024 * 1024
internal const val IMAGE_DETAIL_AUTO = "auto"
internal val ALLOWED_IMAGE_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/webp")
internal val ALLOWED_IMAGE_DETAIL_VALUES = setOf("low", "high", IMAGE_DETAIL_AUTO)

package cc.noharry.agent.appserver.protocol

import kotlinx.serialization.Serializable
import java.net.URI

/** [SessionSummary.lifecycleStatus] of a thread with nothing running. */
const val SESSION_LIFECYCLE_IDLE = "IDLE"

/** An outcome or health nobody has reported. */
const val OUTCOME_UNKNOWN = "UNKNOWN"

/** [SessionTurn.replayStatus] before any replay. */
const val REPLAY_STATUS_NOT_RUN = "NOT_RUN"

private const val PERCENT = 100

@Serializable
enum class ModelProvider(val wireName: String, val label: String) {
    OPENAI("openai", "OpenAI"),
    ANTHROPIC("anthropic", "Anthropic");

    companion object {
        fun from(value: String?): ModelProvider =
            entries.firstOrNull { it.wireName == value?.lowercase() } ?: OPENAI
    }
}

@Serializable
enum class ProviderEndpoint(
    val wireName: String,
    val label: String,
    val provider: ModelProvider,
    val defaultStream: Boolean? = null,
) {
    CHAT_COMPLETIONS("chat_completions", "Chat", ModelProvider.OPENAI),
    RESPONSES("responses", "Responses", ModelProvider.OPENAI, defaultStream = true),
    MESSAGES("messages", "Messages", ModelProvider.ANTHROPIC),
    ;

    companion object {
        fun options(provider: ModelProvider): List<ProviderEndpoint> =
            entries.filter { it.provider == provider }

        fun defaultFor(provider: ModelProvider): ProviderEndpoint =
            options(provider).firstOrNull() ?: CHAT_COMPLETIONS

        fun from(value: String?, provider: ModelProvider): ProviderEndpoint =
            options(provider).firstOrNull { it.wireName == value?.trim()?.lowercase() }
                ?: defaultFor(provider)
    }
}

@Serializable
data class ProviderProfileOption(
    val id: String,
    val label: String,
    val provider: ModelProvider,
    val endpoint: ProviderEndpoint,
    val model: String,
    val selected: Boolean,
    val canDelete: Boolean,
)

/** A provider profile as its settings show it. Its API key stays on the server: [hasApiKey] says whether one is saved. */
@Serializable
data class ProviderEditState(
    val model: String = "",
    val variant: String = "",
    val baseUrl: String = "",
    val profileId: String = "",
    val profileLabel: String = "",
    val provider: ModelProvider = ModelProvider.OPENAI,
    val endpoint: ProviderEndpoint = ProviderEndpoint.CHAT_COMPLETIONS,
    val manualProfileEnabled: Boolean = false,
    val profileOptions: List<ProviderProfileOption> = emptyList(),
    val modelOptions: List<String> = emptyList(),
    val variantOptions: List<String> = emptyList(),
    /** The model's own default variant, when it has one (thinking off, for a model that thinks unless told not to). */
    val defaultVariant: String? = null,
    val hasApiKey: Boolean = false,
    /** The id of the model service the profile reaches ([ServiceView.id]), when an adapter knows it; blank otherwise. */
    val service: String = "",
)

/**
 * The variant menu's options and the one shown chosen. A model with a default of its own (a thinking level of none)
 * shows that default chosen and offers no separate Default, which would send the same; other models keep Default
 * (no variant sent).
 */
fun ProviderEditState.variantChoices(): Pair<List<String>, String> {
    val modelDefault = defaultVariant?.takeIf { it in variantOptions }
        ?: return (listOf(DEFAULT_DEBUG_VARIANT_ID) + variantOptions) to variant.ifBlank { DEFAULT_DEBUG_VARIANT_ID }
    return variantOptions to variant.ifBlank { modelDefault }
}

/** The variant menu's 默认: no variant sent. */
const val DEFAULT_DEBUG_VARIANT_ID = "default"

@Serializable
data class SessionSummary(
    val sessionId: String,
    val title: String,
    val updatedAtEpochMillis: Long,
    val turnCount: Int,
    val compactedSummary: String,
    val lifecycleStatus: String = SESSION_LIFECYCLE_IDLE,
    val completionOutcome: String = OUTCOME_UNKNOWN,
    val logHealth: String = OUTCOME_UNKNOWN,
)

@Serializable
data class SessionTurn(
    val turnId: String,
    val requestId: String,
    val runId: String,
    val sessionId: String,
    val scene: String,
    val userText: String,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
    val finalStatus: String,
    val finalResponse: String,
    val tools: List<SessionToolCall>,
    val completionOutcome: String = OUTCOME_UNKNOWN,
    val replayStatus: String = REPLAY_STATUS_NOT_RUN,
    val replaySummary: String = "",
    /** The run's model usage, so the Board can show a whole session's, also after a restart. */
    val usage: TokenUsageState = TokenUsageState(),
)

@Serializable
data class SessionToolCall(
    val sequence: Int,
    val toolName: String,
    val argumentsPreview: String,
    val resultPreview: String,
    val status: String,
    val toolCallId: String = "",
)

@Serializable
data class SessionBoardState(
    val activeSession: SessionSummary?,
    val recentTurns: List<SessionTurn>,
    val lastAction: SessionToolCall?,
)

/** Collapsed state for the optional CDR context drawer; empty when closed. */
@Serializable
data class ContextPanelState(
    val visible: Boolean,
    val todo: List<TodoEntry>,
    val lessons: List<LessonEntry>,
    val skills: List<SkillEntry>,
)

@Serializable
data class TodoEntry(
    val content: String,
    val status: String,
)

@Serializable
data class LessonEntry(
    val scene: String,
    val content: String,
    val confidence: Float,
    val hitCount: Int,
)

@Serializable
data class SkillEntry(
    val name: String,
    val description: String,
)

@Serializable
data class CurrentRequest(
    val requestId: String,
    val text: String,
)

@Serializable
data class TokenUsageState(
    val requestCount: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val totalTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
) {
    val hasUsage: Boolean get() = requestCount > 0 && totalTokens > 0

    /**
     * Cache hit rate over total input tokens (including cache read/write when the
     * provider reports them separately, as the Anthropic Messages API does).
     *
     * The Messages API reports `input_tokens` excluding cache fields, so warm-cache
     * responses often have `cache_read >> input` and `cache_creation == 0`; a rate of
     * `read / (read + write)` would then always show 100%.
     */
    val cacheHitRate: Int?
        get() {
            if (!hasCacheActivity) return null
            val denominator = cacheRateDenominator
            if (denominator <= 0) return null
            val hit = cachedInputTokens.coerceIn(0, denominator)
            return ((hit.toDouble() / denominator.toDouble()) * PERCENT).toInt()
        }

    val hasCacheActivity: Boolean
        get() = cachedInputTokens > 0 || cacheWriteTokens > 0 || cacheHitTokens > 0 || cacheMissTokens > 0

    /** Prefer inclusive input; fall back to hit+miss when only cache parts are known. */
    private val cacheRateDenominator: Int
        get() {
            if (inputTokens > 0) return inputTokens
            val known = cacheHitTokens + cacheMissTokens
            return if (known > 0) known else cachedInputTokens + cacheWriteTokens
        }

    /** Two runs' (or a session's) usage together. */
    operator fun plus(other: TokenUsageState): TokenUsageState =
        TokenUsageState(
            requestCount = requestCount + other.requestCount,
            inputTokens = inputTokens + other.inputTokens,
            outputTokens = outputTokens + other.outputTokens,
            reasoningTokens = reasoningTokens + other.reasoningTokens,
            totalTokens = totalTokens + other.totalTokens,
            cachedInputTokens = cachedInputTokens + other.cachedInputTokens,
            cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
            cacheHitTokens = cacheHitTokens + other.cacheHitTokens,
            cacheMissTokens = cacheMissTokens + other.cacheMissTokens,
        )

}

@Serializable
data class RuntimeDemoState(
    val runId: String = "",
    val status: String = "Idle",
    val thinkingPreview: String = "",
    val assistantPreview: String = "",
    val pendingConfirmationIds: List<String> = emptyList(),
    val steeringPreview: String = "",
    val execOutputPreview: String = "",
    val workspaceId: String = "",
    val retryCount: Int = 0,
    val runningTools: List<String> = emptyList(),
    val finishedTools: List<String> = emptyList(),
    val changedFiles: List<RuntimeFileChange> = emptyList(),
    val tokenUsage: RuntimeTokenUsage = RuntimeTokenUsage(),
    /** The run's end, a runtime run status name ([RuntimeDemoState] is a live debug view). */
    val finalStatus: String? = null,
    val errorCode: String = "",
)

/** The confirmations still waiting; none once the run has ended. */
fun RuntimeDemoState.pendingConfirmationActionIds(): List<String> =
    if (finalStatus == null) pendingConfirmationIds else emptyList()

@Serializable
data class RuntimeFileChange(
    val path: String,
    val addedLines: Int,
    val removedLines: Int,
    val diffTimedOut: Boolean,
)

@Serializable
data class RuntimeTokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
)

@Serializable
data class WorkspaceSelectionState(
    /** The linked folder's name; blank while the app's private directory is used. */
    val label: String = "",
    val safEnabled: Boolean = false,
)

/**
 * Latest HTML page produced by a successful `write_file` call, rendered from the
 * write payload itself.
 *
 * The runtime isolates `/scratch` writes per run (`/scratch/<runId>/...`) and deletes
 * them when the run terminates, so a `file://` preview of the logical path can never
 * resolve. The full page source is already in the tool-call arguments, which is what
 * the board WebView renders via `loadDataWithBaseURL`.
 */
@Serializable
data class ArtifactPreview(
    val path: String,
    val html: String,
    val bytes: Long,
    val refreshToken: String,
)

/**
 * A configured MCP server: a standard MCP server is its Streamable HTTP endpoint, with an optional bearer token and
 * request headers.
 */
@Serializable
enum class McpServerKind {
    HTTP,
}

@Serializable
data class McpServer(
    /** Also the model-visible server name: its tools are `mcp__<id>__<tool>`. */
    val id: String,
    val label: String,
    val kind: McpServerKind,
    val enabled: Boolean,
    val url: String = "",
) {
    val deletable: Boolean get() = true

    fun summary(): String = url
}

/** One MCP provider: a fixed provider (Tools JSON, calendar, notifications) or a configured [server]. */
@Serializable
data class McpProviderState(
    val id: String,
    val label: String,
    val enabled: Boolean,
    val server: McpServer? = null,
)

@Serializable
data class A2aProfile(
    val id: String,
    val label: String,
    val moduleId: String,
    @Serializable(with = UriSerializer::class)
    val endpoint: URI,
    val enabled: Boolean,
    val allowLoopbackHttpForDebug: Boolean,
    val userDefined: Boolean = false,
    /** What the remote agent can do, in the user's words; the delegation tool's description carries it. */
    val description: String = "",
)

@Serializable
data class A2aModuleState(
    val profiles: List<A2aProfile> = emptyList(),
    val activeProfileId: String? = null,
    val loadedModuleId: String? = null,
    val error: String? = null,
    val enabledProfileIds: Set<String> = emptySet(),
    val loadedModuleIds: Map<String, String> = emptyMap(),
    val errorsByProfileId: Map<String, String> = emptyMap(),
    /** Profiles with a stored access token ([A2aTokens]); the token itself never enters state. */
    val tokenProfileIds: Set<String> = emptySet(),
    /** The A2A client modules packaged in the app, which a new profile picks from. */
    val moduleIds: List<String> = emptyList(),
) {
    val activeProfile: A2aProfile?
        get() = profiles.firstOrNull { it.id == activeProfileId }

    val enabledProfiles: List<A2aProfile>
        get() = profiles.filter { it.id in enabledProfileIds }

    val loadedProfiles: List<A2aProfile>
        get() = profiles.filter { it.id in loadedModuleIds }
}

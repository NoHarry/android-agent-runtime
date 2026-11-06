package cc.noharry.agent.model

import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ServerSideSearchCapability
import cc.noharry.agent.transport.HttpTransport
import cc.noharry.agent.transport.OkHttpHttpTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/**
 * An OpenAI-format model service: Chat Completions, or the Responses API with `endpoint`. [serverSideSearch] is off unless
 * set: OpenAI accepts its search options only on some models (Chat Completions' `web_search_options` on the search
 * models) and refuses the request on the others.
 */
class OpenAiModelClient(
    private val apiKeyProvider: () -> String,
    private val transport: HttpTransport = OkHttpHttpTransport(),
) : ModelClient {
    @OptIn(ExperimentalAgentRuntimeApi::class)
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe()
    private var model: String = DEFAULT_OPENAI_MODEL
    private var baseUrl: String = DEFAULT_OPENAI_BASE_URL
    private var stream: Boolean = false
    private var endpointMode: OpenAiEndpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS
    private var serverSideSearch: Boolean = false
    private var searchCapability: ServerSideSearchCapability = ServerSideSearchCapability.OPENAI_CHAT_OPTIONS
    private var maxOutputTokens: Int? = null

    /**
     * @param maxOutputTokens the output limit requests send (`max_completion_tokens` on OpenAI's own API, `max_tokens`
     * elsewhere, `max_output_tokens` on Responses); null sends the model's own limit where the runtime knows one
     * (DeepSeek's, by name), else none. A gateway that caps output lower than a model's own limit needs it set.
     */
    constructor(
        apiKeyProvider: () -> String,
        model: String,
        baseUrl: String = DEFAULT_OPENAI_BASE_URL,
        stream: Boolean = false,
        serverSideSearch: Boolean = false,
        searchCapability: ServerSideSearchCapability = ServerSideSearchCapability.OPENAI_CHAT_OPTIONS,
        transport: HttpTransport = OkHttpHttpTransport(),
        maxOutputTokens: Int? = null,
    ) : this(apiKeyProvider, transport) {
        configure(model, baseUrl, stream, OpenAiEndpointMode.CHAT_COMPLETIONS, serverSideSearch, searchCapability, maxOutputTokens)
    }

    /** As above, on the endpoint named by [endpoint] (`chat_completions` or `responses`). */
    constructor(
        apiKeyProvider: () -> String,
        model: String,
        baseUrl: String = DEFAULT_OPENAI_BASE_URL,
        stream: Boolean = false,
        endpoint: String,
        serverSideSearch: Boolean = false,
        searchCapability: ServerSideSearchCapability = ServerSideSearchCapability.OPENAI_RESPONSES_TOOL,
        transport: HttpTransport = OkHttpHttpTransport(),
        maxOutputTokens: Int? = null,
    ) : this(apiKeyProvider, transport) {
        configure(model, baseUrl, stream, OpenAiEndpointMode.from(endpoint), serverSideSearch, searchCapability, maxOutputTokens)
    }

    private fun configure(
        model: String,
        baseUrl: String,
        stream: Boolean,
        endpointMode: OpenAiEndpointMode,
        serverSideSearch: Boolean,
        searchCapability: ServerSideSearchCapability,
        maxOutputTokens: Int?,
    ) {
        require(model.isNotBlank()) { "OpenAI model must not be blank." }
        require(maxOutputTokens == null || maxOutputTokens > 0) { "OpenAI max output tokens must be positive." }
        require(baseUrl.isNotBlank()) { "OpenAI base URL must not be blank." }
        this.model = model
        this.baseUrl = baseUrl
        this.stream = stream
        this.endpointMode = endpointMode
        this.serverSideSearch = serverSideSearch
        this.searchCapability = searchCapability
        this.maxOutputTokens = maxOutputTokens
    }

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        val apiKey = requireProviderApiKey(OPENAI_PROVIDER_NAME, apiKeyProvider)
        val effectiveModel = request.modelRef?.modelId?.takeIf { it.isNotBlank() } ?: model
        if (endpointMode == OpenAiEndpointMode.RESPONSES && stream) {
            openAiResponsesStreamEvents(
                streamProviderRequest(
                    OPENAI_PROVIDER_NAME,
                    transport,
                    openAiResponsesRequest(
                        apiKey,
                        baseUrl,
                        effectiveModel,
                        request,
                        stream = true,
                        serverSideSearch = serverSideSearch,
                        searchCapability = searchCapability,
                        maxOutputTokens = maxOutputTokens,
                    ),
                ),
            ).collect { emit(it) }
        } else if (endpointMode == OpenAiEndpointMode.RESPONSES) {
            val events = openAiResponsesEvents(
                executeProviderRequest(
                    OPENAI_PROVIDER_NAME,
                    transport,
                    openAiResponsesRequest(
                        apiKey,
                        baseUrl,
                        effectiveModel,
                        request,
                        serverSideSearch = serverSideSearch,
                        searchCapability = searchCapability,
                        maxOutputTokens = maxOutputTokens,
                    ),
                ),
            )
            for (event in events) emit(event)
        } else if (stream) {
            openAiStreamEvents(
                streamProviderRequest(
                    OPENAI_PROVIDER_NAME,
                    transport,
                    openAiRequest(
                        apiKey,
                        baseUrl,
                        effectiveModel,
                        request,
                        stream = true,
                        serverSideSearch = serverSideSearch,
                        searchCapability = searchCapability,
                        maxOutputTokens = maxOutputTokens,
                    ),
                ),
            ).collect { emit(it) }
        } else {
            val events = openAiEvents(
                executeProviderRequest(
                    OPENAI_PROVIDER_NAME,
                    transport,
                    openAiRequest(
                        apiKey,
                        baseUrl,
                        effectiveModel,
                        request,
                        serverSideSearch = serverSideSearch,
                        searchCapability = searchCapability,
                        maxOutputTokens = maxOutputTokens,
                    ),
                ),
            )
            for (event in events) {
                emit(event)
            }
        }
        emit(ModelEvent.Completed)
    }
}

class AnthropicModelClient(
    private val apiKeyProvider: () -> String,
    private val transport: HttpTransport = OkHttpHttpTransport(),
) : ModelClient {
    @OptIn(ExperimentalAgentRuntimeApi::class)
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.threadSafe()
    private var model: String = DEFAULT_ANTHROPIC_MODEL
    private var baseUrl: String = DEFAULT_ANTHROPIC_BASE_URL
    private var stream: Boolean = false
    private var serverSideSearch: Boolean = true
    private var searchCapability: ServerSideSearchCapability = ServerSideSearchCapability.ANTHROPIC_SERVER_TOOL
    private var promptCaching: Boolean = true
    private var maxOutputTokens: Int? = null

    /**
     * @param promptCaching asks the API to cache the request's prefix (a top-level `cache_control`). Claude caches only
     * what a request marks; DeepSeek and other compatible services cache on their own and may not take the field, so it
     * is on only for Anthropic's own API unless set.
     * @param serverSideSearch adds Anthropic's web search server tool; on only for Anthropic's own API unless set, since
     * compatible services do not know it. A request whose tools already have a `web_search` leaves it out.
     * @param maxOutputTokens the `max_tokens` requests send, which the Messages API requires; null takes the model's own
     * limit where the runtime knows one (DeepSeek's), else 16000, with an older Claude's thinking budget on top. Set,
     * it is raised only as far as a thinking budget needs room for an answer. Long answers want `stream = true`.
     */
    constructor(
        apiKeyProvider: () -> String,
        model: String,
        baseUrl: String = DEFAULT_ANTHROPIC_BASE_URL,
        stream: Boolean = false,
        serverSideSearch: Boolean = isAnthropicApi(baseUrl),
        searchCapability: ServerSideSearchCapability = ServerSideSearchCapability.ANTHROPIC_SERVER_TOOL,
        transport: HttpTransport = OkHttpHttpTransport(),
        promptCaching: Boolean = isAnthropicApi(baseUrl),
        maxOutputTokens: Int? = null,
    ) : this(apiKeyProvider, transport) {
        require(model.isNotBlank()) { "Anthropic model must not be blank." }
        require(maxOutputTokens == null || maxOutputTokens > 0) { "Anthropic max output tokens must be positive." }
        require(baseUrl.isNotBlank()) { "Anthropic base URL must not be blank." }
        this.model = model
        this.baseUrl = baseUrl
        this.stream = stream
        this.serverSideSearch = serverSideSearch
        this.searchCapability = searchCapability
        this.promptCaching = promptCaching
        this.maxOutputTokens = maxOutputTokens
    }

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        val apiKey = requireProviderApiKey(ANTHROPIC_PROVIDER_NAME, apiKeyProvider)
        val effectiveModel = request.modelRef?.modelId?.takeIf { it.isNotBlank() } ?: model
        if (stream) {
            anthropicStreamEvents(
                streamProviderRequest(
                    ANTHROPIC_PROVIDER_NAME,
                    transport,
                    anthropicRequest(
                        apiKey,
                        baseUrl,
                        effectiveModel,
                        request,
                        stream = true,
                        serverSideSearch = serverSideSearch,
                        searchCapability = searchCapability,
                        promptCaching = promptCaching,
                        maxOutputTokens = maxOutputTokens,
                    ),
                ),
            ).collect { emit(it) }
        } else {
            val events = anthropicEvents(
                executeProviderRequest(
                    ANTHROPIC_PROVIDER_NAME,
                    transport,
                    anthropicRequest(
                        apiKey,
                        baseUrl,
                        effectiveModel,
                        request,
                        serverSideSearch = serverSideSearch,
                        searchCapability = searchCapability,
                        promptCaching = promptCaching,
                        maxOutputTokens = maxOutputTokens,
                    ),
                ),
            )
            for (event in events) {
                emit(event)
            }
        }
        emit(ModelEvent.Completed)
    }
}

private const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
private const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
private const val DEFAULT_ANTHROPIC_MODEL = "claude-haiku-5-5"
private const val DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1"

private fun isAnthropicApi(baseUrl: String): Boolean =
    runCatching { java.net.URI(baseUrl).host }.getOrNull() == "api.anthropic.com"
private const val ANTHROPIC_PROVIDER_NAME = "Anthropic"

internal enum class OpenAiEndpointMode(val wireName: String) {
    CHAT_COMPLETIONS("chat_completions"),
    RESPONSES("responses"),
    ;

    companion object {
        fun from(value: String): OpenAiEndpointMode =
            entries.firstOrNull { it.wireName == value.trim().lowercase() } ?: CHAT_COMPLETIONS
    }
}

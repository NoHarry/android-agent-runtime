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
    private var serverSideSearch: Boolean = true
    private var searchCapability: ServerSideSearchCapability = ServerSideSearchCapability.OPENAI_CHAT_OPTIONS

    constructor(
        apiKeyProvider: () -> String,
        model: String,
        baseUrl: String = DEFAULT_OPENAI_BASE_URL,
        stream: Boolean = false,
        serverSideSearch: Boolean = true,
        searchCapability: ServerSideSearchCapability = ServerSideSearchCapability.OPENAI_CHAT_OPTIONS,
        transport: HttpTransport = OkHttpHttpTransport(),
    ) : this(apiKeyProvider, transport) {
        configure(model, baseUrl, stream, OpenAiEndpointMode.CHAT_COMPLETIONS, serverSideSearch, searchCapability)
    }

    constructor(
        apiKeyProvider: () -> String,
        model: String,
        baseUrl: String = DEFAULT_OPENAI_BASE_URL,
        stream: Boolean = false,
        endpoint: String,
        serverSideSearch: Boolean = true,
        searchCapability: ServerSideSearchCapability = ServerSideSearchCapability.OPENAI_RESPONSES_TOOL,
        transport: HttpTransport = OkHttpHttpTransport(),
    ) : this(apiKeyProvider, transport) {
        configure(model, baseUrl, stream, OpenAiEndpointMode.from(endpoint), serverSideSearch, searchCapability)
    }

    private fun configure(
        model: String,
        baseUrl: String,
        stream: Boolean,
        endpointMode: OpenAiEndpointMode,
        serverSideSearch: Boolean,
        searchCapability: ServerSideSearchCapability,
    ) {
        require(model.isNotBlank()) { "OpenAI model must not be blank." }
        require(baseUrl.isNotBlank()) { "OpenAI base URL must not be blank." }
        this.model = model
        this.baseUrl = baseUrl
        this.stream = stream
        this.endpointMode = endpointMode
        this.serverSideSearch = serverSideSearch
        this.searchCapability = searchCapability
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

    constructor(
        apiKeyProvider: () -> String,
        model: String,
        baseUrl: String = DEFAULT_ANTHROPIC_BASE_URL,
        stream: Boolean = false,
        serverSideSearch: Boolean = true,
        searchCapability: ServerSideSearchCapability = ServerSideSearchCapability.ANTHROPIC_SERVER_TOOL,
        transport: HttpTransport = OkHttpHttpTransport(),
    ) : this(apiKeyProvider, transport) {
        require(model.isNotBlank()) { "Anthropic model must not be blank." }
        require(baseUrl.isNotBlank()) { "Anthropic base URL must not be blank." }
        this.model = model
        this.baseUrl = baseUrl
        this.stream = stream
        this.serverSideSearch = serverSideSearch
        this.searchCapability = searchCapability
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
private const val DEFAULT_ANTHROPIC_MODEL = "claude-3-5-haiku-latest"
private const val DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1"
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

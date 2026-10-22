package cc.noharry.agent.kernel.api

import cc.noharry.agent.kernel.api.model.ModelImage

/**
 * Candidate generic tool/catalog provider. MCP is a transport adapter, not a parallel SPI.
 *
 * Implementations may be in-process fixtures, device capabilities, or remote transports.
 * The runtime consumes this contract through a normalized catalog. Mounting paths are
 * owned by the MCP adapter, not this generic contract.
 */
interface ToolProvider {
    /** Stable provider identifier used to build model-visible qualified tool names. */
    val providerId: String

    /**
     * Candidate concurrency declaration: how many calls the runtime may make to this provider at once. The default,
     * [ProviderConcurrencyDeclaration.serialized], makes one call at a time, so a provider that is not thread-safe
     * need not override it; a provider whose calls may overlap declares [ProviderConcurrencyDeclaration.threadSafe] or
     * [ProviderConcurrencyDeclaration.sessionFactory].
     */
    @ExperimentalAgentRuntimeApi
    val providerConcurrency: ProviderConcurrencyDeclaration
        get() = ProviderConcurrencyDeclaration.serialized()

    /** Human-readable provider label for catalog listings. */
    val displayName: String
        get() = providerId

    /** Initializes the provider before catalog reads or tool calls. Candidate API, not frozen. */
    suspend fun initialize(context: ToolInitializeContext): ToolInitializeResult =
        ToolInitializeResult.connected()

    /** Lists currently available tools from this provider. Candidate API, not frozen. */
    suspend fun listTools(): List<ToolDescriptor>

    /** Lists resources exposed by this provider. Candidate API, not frozen. */
    suspend fun listResources(): List<ToolResourceDescriptor> = emptyList()

    /** Lists prompts exposed by this provider. Candidate API, not frozen. */
    suspend fun listPrompts(): List<ToolPromptDescriptor> = emptyList()

    /** Calls an original provider tool name with JSON-object arguments. Candidate API, not frozen. */
    suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult
}

/**
 * Optional provider identity for environment-level catalog caching.
 *
 * Providers should implement this only when their `initialize()` and `listTools()` results are
 * stable across runs and sessions in the current virtual environment. Include a provider-specific
 * catalog version in this identity if app or server upgrades can change the visible tool set.
 *
 * The registry cache key binds provider class + [ToolProvider.providerId] + this identity, so
 * two implementations that happen to declare the same identity string cannot share catalogs.
 * Live servers whose toolset version is not genuinely available must not implement this.
 */
interface ToolCatalogCacheIdentity {
    val catalogCacheIdentity: String
}

/** Runtime context passed to provider initialization. Candidate API, not frozen. */
data class ToolInitializeContext(
    val sessionId: String = "",
    val runId: String = "",
    val requestId: String = "",
)

/** Provider initialization result. Candidate API, not frozen. */
data class ToolInitializeResult(
    val status: ToolProviderStatus,
    val message: String? = null,
) {
    companion object {
        /** Convenience connected result. */
        fun connected(message: String? = null): ToolInitializeResult =
            ToolInitializeResult(ToolProviderStatus.CONNECTED, message)

        /** Convenience failed result. */
        fun failed(message: String): ToolInitializeResult =
            ToolInitializeResult(ToolProviderStatus.FAILED, message)
    }
}

/** Runtime-visible provider status. Candidate API, not frozen. */
enum class ToolProviderStatus {
    INITIALIZING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
}

/** Tool descriptor returned by a [ToolProvider]. Candidate API, not frozen. */
data class ToolDescriptor(
    val name: String,
    val description: String = "",
    val inputSchemaJson: String,
    val title: String? = null,
    val effectKind: String = "UNKNOWN",
    val completionImpact: String = "ADVISORY",
    /** Optional exact MCP output JSON Schema; metadata only, never an input parameter. Candidate API. */
    val outputSchemaJson: String? = null,
)

/** Runtime context passed to tool calls. Candidate API, not frozen. */
data class ToolCallContext(
    val sessionId: String,
    val runId: String,
    val requestId: String,
)

/** Tool-call result returned by providers. Candidate API, not frozen. */
data class ToolCallResult(
    val success: Boolean,
    val resultJson: String,
    val retryable: Boolean = false,
    val trace: ToolCallTrace? = null,
    /**
     * Candidate: images the model should see with this result, such as a screenshot. They reach
     * the model only when the run accepts image input, and the EventLog keeps them as
     * content-addressed attachments rather than inline text.
     */
    val images: List<ModelImage> = emptyList(),
)

/** Optional debug trace metadata for fixture and sample providers. Candidate API, not frozen. */
data class ToolCallTrace(
    val validationOk: Boolean,
    val validationCode: String? = null,
    val validationMessage: String? = null,
    val validationSchemaPath: String? = null,
)

/** Resource descriptor for progressive resource browsing. Candidate API, not frozen. */
data class ToolResourceDescriptor(
    val uri: String,
    val name: String? = null,
    val description: String? = null,
)

/** Prompt descriptor for progressive prompt browsing. Candidate API, not frozen. */
data class ToolPromptDescriptor(
    val name: String,
    val description: String? = null,
)

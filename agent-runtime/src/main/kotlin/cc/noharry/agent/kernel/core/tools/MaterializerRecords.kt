package cc.noharry.agent.kernel.core.tools
import cc.noharry.agent.spi.model.ModelRef

internal data class MaterializerContext(
    val sessionId: String = "",
    val runId: String = "",
    val requestId: String = "",
    val scene: String? = null,
    val modelRef: ModelRef? = null,
    val timeoutMillis: Long = DEFAULT_MATERIALIZER_TIMEOUT_MILLIS,
)

internal sealed interface MaterializerReadResult {
    data class Found(
        val content: String,
        val cacheHit: Boolean,
        val metadata: MaterializedSchemaMetadata? = null,
        val cacheMetadata: MaterializerCacheMetadata = MaterializerCacheMetadata.NotCacheable,
    ) : MaterializerReadResult

    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean,
    ) : MaterializerReadResult

    data object NotMaterialized : MaterializerReadResult

    data object NotFound : MaterializerReadResult
}

internal enum class MaterializerCacheScope {
    REQUEST,
    RUN,
    SESSION,
    SHARED,
}

internal data class MaterializerCacheMetadata(
    val cacheable: Boolean,
    val scope: MaterializerCacheScope = MaterializerCacheScope.REQUEST,
    val version: String? = null,
) {
    companion object {
        val NotCacheable = MaterializerCacheMetadata(cacheable = false)

        fun request(version: String? = null): MaterializerCacheMetadata =
            MaterializerCacheMetadata(cacheable = true, scope = MaterializerCacheScope.REQUEST, version = version)

        fun run(version: String? = null): MaterializerCacheMetadata =
            MaterializerCacheMetadata(cacheable = true, scope = MaterializerCacheScope.RUN, version = version)

        fun session(version: String? = null): MaterializerCacheMetadata =
            MaterializerCacheMetadata(cacheable = true, scope = MaterializerCacheScope.SESSION, version = version)

        fun shared(version: String? = null): MaterializerCacheMetadata =
            MaterializerCacheMetadata(cacheable = true, scope = MaterializerCacheScope.SHARED, version = version)
    }
}

internal sealed interface MaterializerListResult {
    data class Entries(
        val entries: List<MaterializedEntry>,
    ) : MaterializerListResult

    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean,
    ) : MaterializerListResult

    data object NotMaterialized : MaterializerListResult

    data object NotFound : MaterializerListResult
}

internal data class MaterializedEntry(
    val name: String,
    val type: String,
)

internal data class MaterializedSchemaMetadata(
    val schemaVersion: String,
    val schemaHash: String,
    val expiresAtEpochMillis: Long,
)

internal data class MaterializedSchemaCacheEntry(
    val content: String,
    val metadata: MaterializedSchemaMetadata,
)

internal const val DEFAULT_MATERIALIZER_TIMEOUT_MILLIS = 1_500L

package cc.noharry.agent.kernel.core.tools
import cc.noharry.agent.kernel.api.model.ModelRef

data class MaterializerContext(
    val sessionId: String = "",
    val runId: String = "",
    val requestId: String = "",
    val scene: String? = null,
    val modelRef: ModelRef? = null,
    val timeoutMillis: Long = DEFAULT_MATERIALIZER_TIMEOUT_MILLIS,
)

sealed interface MaterializerReadResult {
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

enum class MaterializerCacheScope {
    REQUEST,
    RUN,
    SESSION,
    SHARED,
}

data class MaterializerCacheMetadata(
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

sealed interface MaterializerListResult {
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

data class MaterializedEntry(
    val name: String,
    val type: String,
)

data class MaterializedSchemaMetadata(
    val schemaVersion: String,
    val schemaHash: String,
    val expiresAtEpochMillis: Long,
)

data class MaterializedSchemaCacheEntry(
    val content: String,
    val metadata: MaterializedSchemaMetadata,
)

internal const val DEFAULT_MATERIALIZER_TIMEOUT_MILLIS = 1_500L

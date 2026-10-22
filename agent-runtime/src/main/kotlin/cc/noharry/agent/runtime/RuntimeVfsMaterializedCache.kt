package cc.noharry.agent.runtime

import cc.noharry.agent.vfs.VfsReadAttributes
import cc.noharry.agent.kernel.core.MaterializerCacheMetadata
import cc.noharry.agent.kernel.core.MaterializerCacheScope
import cc.noharry.agent.kernel.core.MaterializerContext
import cc.noharry.agent.kernel.core.MaterializerReadResult
import cc.noharry.agent.kernel.core.RUNTIME_COUNTER_MATERIALIZER_BYTE_ENCODES
import cc.noharry.agent.kernel.core.RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_HITS
import cc.noharry.agent.kernel.core.RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_MISSES
import cc.noharry.agent.kernel.core.RuntimeVfsPerformanceCounters

internal data class RuntimeMaterializedCacheKey(
    val sessionId: String,
    val runId: String,
    val requestId: String,
    val scene: String?,
    val modelRef: String?,
)

internal fun MaterializerContext.materializedCacheKey(): RuntimeMaterializedCacheKey =
    RuntimeMaterializedCacheKey(
        sessionId = sessionId,
        runId = runId,
        requestId = requestId,
        scene = scene,
        modelRef = modelRef?.wireValue(),
    )

internal class RuntimeMaterializedCache {
    private val reads = linkedMapOf<RuntimeMaterializedScopedCacheKey, MaterializerReadResult.Found>()
    private val bytes = linkedMapOf<RuntimeMaterializedScopedCacheKey, ByteEntry>()

    suspend fun read(
        path: String,
        context: MaterializerContext,
        cacheMetadata: MaterializerCacheMetadata,
        loader: suspend () -> MaterializerReadResult,
    ): MaterializerReadResult {
        val lookupKey = path.cacheKey(context, cacheMetadata)
        if (lookupKey != null) {
            synchronized(reads) {
                reads[lookupKey]?.let {
                    RuntimeVfsPerformanceCounters.increment(RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_HITS)
                    return it
                }
            }
            RuntimeVfsPerformanceCounters.increment(RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_MISSES)
        }
        val loaded = loader()
        if (loaded is MaterializerReadResult.Found && loaded.cacheMetadata.cacheable) {
            val storageKey = path.cacheKey(context, loaded.cacheMetadata)
            if (storageKey != null) {
                synchronized(reads) {
                    reads[storageKey] = loaded
                }
            }
        }
        return loaded
    }

    fun bytes(path: String, context: MaterializerContext, result: MaterializerReadResult.Found): ByteArray =
        bytes(path, context, result.cacheMetadata, result.content)

    fun bytes(
        path: String,
        context: MaterializerContext,
        cacheMetadata: MaterializerCacheMetadata,
        content: String,
    ): ByteArray {
        val key = path.cacheKey(context, cacheMetadata) ?: path.requestScopedCacheKey(context)
        synchronized(bytes) {
            val cached = bytes[key]
            if (cached != null && cached.content == content) return cached.bytes
        }
        RuntimeVfsPerformanceCounters.increment(RUNTIME_COUNTER_MATERIALIZER_BYTE_ENCODES)
        val encoded = content.toByteArray(Charsets.UTF_8)
        synchronized(bytes) {
            bytes[key] = ByteEntry(content, encoded)
        }
        return encoded
    }

    private data class ByteEntry(
        val content: String,
        val bytes: ByteArray,
    )
}

private data class RuntimeMaterializedScopedCacheKey(
    val path: String,
    val scope: MaterializerCacheScope,
    val sessionId: String,
    val runId: String,
    val requestId: String,
    val scene: String?,
    val modelRef: String?,
    val version: String?,
)

private fun String.cacheKey(
    context: MaterializerContext,
    metadata: MaterializerCacheMetadata,
): RuntimeMaterializedScopedCacheKey? {
    if (!metadata.cacheable) return null
    val contextKey = context.materializedCacheKey()
    return when (metadata.scope) {
        MaterializerCacheScope.REQUEST -> requestScopedCacheKey(context)
        MaterializerCacheScope.RUN -> RuntimeMaterializedScopedCacheKey(
            path = this,
            scope = metadata.scope,
            sessionId = "",
            runId = contextKey.runId,
            requestId = "",
            scene = contextKey.scene,
            modelRef = contextKey.modelRef,
            version = metadata.version,
        )
        MaterializerCacheScope.SESSION -> RuntimeMaterializedScopedCacheKey(
            path = this,
            scope = metadata.scope,
            sessionId = contextKey.sessionId,
            runId = "",
            requestId = "",
            scene = contextKey.scene,
            modelRef = contextKey.modelRef,
            version = metadata.version,
        )
        MaterializerCacheScope.SHARED -> RuntimeMaterializedScopedCacheKey(
            path = this,
            scope = metadata.scope,
            sessionId = "",
            runId = "",
            requestId = "",
            scene = "",
            modelRef = "",
            version = metadata.version,
        )
    }
}

private fun String.requestScopedCacheKey(context: MaterializerContext): RuntimeMaterializedScopedCacheKey {
    val contextKey = context.materializedCacheKey()
    return RuntimeMaterializedScopedCacheKey(
        path = this,
        scope = MaterializerCacheScope.REQUEST,
        sessionId = contextKey.sessionId,
        runId = contextKey.runId,
        requestId = contextKey.requestId,
        scene = contextKey.scene,
        modelRef = contextKey.modelRef,
        version = null,
    )
}

internal data class MaterializedSearchFile(
    val fullPath: String,
    val relativePath: String,
    val content: String,
    val cacheMetadata: MaterializerCacheMetadata,
)

internal sealed interface MaterializedSearchCollectResult {
    data object Found : MaterializedSearchCollectResult
    data object NotFound : MaterializedSearchCollectResult
    data object InvalidPath : MaterializedSearchCollectResult
    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean,
    ) : MaterializedSearchCollectResult
}

internal class MaterializedSearchPattern(pattern: String) {
    private val literal = pattern
    private val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()

    fun matches(line: String): Boolean =
        regex?.containsMatchIn(line) ?: line.contains(literal, ignoreCase = true)
}

internal fun normalizeRelativeSearchPath(relativePath: String): String =
    relativePath.trim('/')

internal fun joinRelativeSearchPath(parent: String, child: String): String =
    listOf(parent.trim('/'), child.trim('/'))
        .filter { it.isNotBlank() }
        .joinToString("/")

internal fun MaterializerReadResult.Found.attributes(): VfsReadAttributes {
    val metadata = metadata
    return VfsReadAttributes(
        booleans = mapOf(
            "materialized" to true,
            "cache_hit" to cacheHit,
        ),
        strings = buildMap {
            metadata?.let {
                put("schema_version", it.schemaVersion)
                put("schema_hash", it.schemaHash)
            }
        },
        longs = buildMap {
            metadata?.let { put("expires_at_epoch_millis", it.expiresAtEpochMillis) }
        },
    )
}

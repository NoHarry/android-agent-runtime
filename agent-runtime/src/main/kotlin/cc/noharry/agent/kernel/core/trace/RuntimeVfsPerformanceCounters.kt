package cc.noharry.agent.kernel.core.trace

import cc.noharry.agent.spi.InternalAgentRuntimeApi
/**
 * Process-wide counts of the runtime's VFS work, recorded per run as deltas; the names are the constants below.
 *
 * @suppress
 */
@InternalAgentRuntimeApi
internal object RuntimeVfsPerformanceCounters {
    private val values = linkedMapOf<String, Long>()

    fun reset() {
        synchronized(values) {
            values.clear()
        }
    }

    fun increment(name: String, amount: Long = 1L) {
        synchronized(values) {
            values[name] = values.getOrDefault(name, 0L) + amount
        }
    }

    fun snapshot(): Map<String, Long> =
        synchronized(values) {
            values.toMap()
        }

    fun deltaSince(baseline: Map<String, Long>): Map<String, Long> =
        snapshot()
            .mapValues { (name, value) -> value - baseline.getOrDefault(name, 0L) }
            .filterValues { it != 0L }
}

internal const val RUNTIME_COUNTER_OVERLAY_ATTACHES = "runtime.overlay.attaches"
internal const val RUNTIME_COUNTER_MATERIALIZER_READS = "runtime.materializer.reads"
internal const val RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_HITS = "runtime.materializer.read_cache.hits"
internal const val RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_MISSES = "runtime.materializer.read_cache.misses"
internal const val RUNTIME_COUNTER_MATERIALIZER_BYTE_ENCODES = "runtime.materializer.byte_encodes"
internal const val RUNTIME_COUNTER_MATERIALIZER_SEARCH_FILES = "runtime.materializer.search.files"
internal const val RUNTIME_COUNTER_MATERIALIZER_SEARCH_BYTE_ENCODES = "runtime.materializer.search.byte_encodes"
internal const val RUNTIME_COUNTER_SCHEMA_CACHE_CLEANUP_SCANS = "runtime.schema_cache.cleanup_scans"
internal const val RUNTIME_COUNTER_PROVIDER_CATALOG_CACHE_HITS = "runtime.provider_catalog.cache.hits"
internal const val RUNTIME_COUNTER_PROVIDER_CATALOG_CACHE_MISSES = "runtime.provider_catalog.cache.misses"

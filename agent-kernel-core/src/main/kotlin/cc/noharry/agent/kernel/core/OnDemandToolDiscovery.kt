@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.ToolPrefetchProvider
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Run-scoped discovery budget and repeated-query cache; exhausted lookups keep ordinary local search. */
class OnDemandToolDiscovery {
    private val lock = Mutex()
    private val runs = linkedMapOf<Pair<String, String>, DiscoveryState>()

    suspend fun select(
        query: String, context: MaterializerContext, registry: ToolProviderRegistry,
        recorder: LoopSessionRecorder, activatedNames: Set<String>, maxTools: Int,
    ): List<McpToolSearchHit> = lock.withLock {
        val key = context.sessionId to context.runId
        val state = runs.remove(key) ?: DiscoveryState()
        runs[key] = state
        while (runs.size > MAX_DISCOVERY_RUNS) runs.remove(runs.keys.first())
        val cacheKey = query.trim().lowercase() to maxTools
        state.results[cacheKey]?.let { return@withLock it }
        val remaining = DISCOVERY_BUDGET_MS - state.spentMs
        if (remaining <= 0 || state.results.size >= MAX_DISCOVERY_REQUESTS) return@withLock emptyList()
        val started = System.nanoTime()
        val selected = try {
            discoverRuntimeTools(query, context, registry, recorder, activatedNames, maxTools, remaining)
        } finally {
            state.spentMs += ((System.nanoTime() - started) / NANOS_PER_MILLI).coerceAtLeast(0L)
        }
        state.results[cacheKey] = selected
        selected
    }
}

private class DiscoveryState {
    var spentMs = 0L
    val results = mutableMapOf<Pair<String, Int>, List<McpToolSearchHit>>()
}

/** Advisory search at the model's request; remote failure preserves ordinary keyword discovery. */
private suspend fun discoverRuntimeTools(
    query: String,
    context: MaterializerContext,
    registry: ToolProviderRegistry,
    recorder: LoopSessionRecorder,
    activatedNames: Set<String>,
    maxTools: Int,
    budgetMs: Long,
): List<McpToolSearchHit> {
    val catalog = registry.prefetchCatalog(context)
    val selected = mutableListOf<McpToolSearchHit>()
    val started = System.nanoTime()
    val limit = maxTools.coerceIn(0, MAX_DISCOVERY_TOOLS)
    val discoveryId = ":search:${discoverySequence.incrementAndGet()}"
    for ((provider, tools) in catalog) {
        val selector = provider as ToolPrefetchProvider
        if (!selector.prefetchOnSearch || !selector.remotePrefetchEnabled) continue
        val remainingMs = budgetMs - (System.nanoTime() - started) / NANOS_PER_MILLI
        if (remainingMs <= 0 || selected.size >= limit) break
        val candidates = tools.filterNot { it.qualifiedName in activatedNames }
        if (candidates.isEmpty()) continue
        val found = prefetchProvider(
            selector, candidates, query, context, recorder, registry,
            minOf(selector.prefetchTimeoutMillis.coerceAtLeast(1L), remainingMs), limit - selected.size, discoveryId,
        )
        selected += found.map { McpToolSearchHit(it, ADVISORY_SELECTION_SCORE) }
    }
    return selected.distinctBy { it.tool.qualifiedName }
}

private val discoverySequence = AtomicLong()
private const val DISCOVERY_BUDGET_MS = 15_000L
private const val MAX_DISCOVERY_TOOLS = 24
private const val MAX_DISCOVERY_REQUESTS = 3
private const val MAX_DISCOVERY_RUNS = 8
private const val NANOS_PER_MILLI = 1_000_000L
// Rank marker only; not the remote model's confidence or a business-success score.
private const val ADVISORY_SELECTION_SCORE = 1.0

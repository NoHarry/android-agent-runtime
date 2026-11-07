package cc.noharry.agent.spi

/**
 * Optional advisory discovery extension implemented alongside [ToolProvider].
 * Selects original names from the supplied catalog, never executes tools.
 */
interface ToolPrefetchProvider {
    /** Fast host-owned catalogue hints only. Must not block, use network, or execute business tools. */
    fun localToolNames(request: ToolPrefetchRequest): List<String> = emptyList()

    /** False for local-only hint providers; avoids a synthetic remote discovery call. */
    val remotePrefetchEnabled: Boolean get() = true

    /** Opt-in: invoke remote selection only from search_tools, after a model asks for missing capabilities. */
    val prefetchOnSearch: Boolean get() = false

    /** Requested budget, bounded by the Kernel's aggregate prefetch deadline. */
    val prefetchTimeoutMillis: Long get() = 3_000L

    /** Returns complementary candidate names; failures fall back to ordinary discovery. */
    suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace): List<String>
}

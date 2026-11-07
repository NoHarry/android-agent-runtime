package cc.noharry.agent.spi

/**
 * Writes logical selector payloads to the run's canonical EventLog.
 * Never include transport headers, credentials, URLs containing credentials, or cookies.
 */
interface ToolPrefetchTrace {
    /** Record a complete JSON request before sending it; sequential pairs are allowed, one in flight. */
    fun request(json: String)

    /** Record the complete successful JSON response for the current request before starting another. */
    fun response(json: String)

    /** Optional JSON-object timing metadata for this pair; no URLs, headers, credentials or error text. */
    fun diagnostics(json: String) = Unit
}

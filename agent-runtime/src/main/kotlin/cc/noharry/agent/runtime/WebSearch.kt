package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/**
 * The search behind the native `web_search` tool (owner 2026-10-17: DeepSeek's Search API instead of the provider's
 * server-side search, whose queries and results never reach the host). Results go to the model and the event log
 * like any tool result. Candidate API; not frozen.
 */
@ExperimentalAgentRuntimeApi
interface WebSearchProvider {
    /** Where searches go, shown as the network activity's domain (e.g. `api.deepseek.com`). */
    val label: String

    /** Up to [count] results for [query]; throws when the service fails. */
    suspend fun search(query: String, count: Int): List<WebSearchResult>
}

/**
 * Fetches a page for web_fetch when the device's own request was refused or failed (a site that blocks the device, a dead
 * route), e.g. through a provider's fetch API. Separate from [WebSearchProvider] (owner 2026-10-18: 拆成独立选项), so a
 * runtime that keeps the provider's server-side search can still have it. Candidate API; not frozen.
 */
@ExperimentalAgentRuntimeApi
interface WebFetchFallback {
    /** Where the page comes from, shown as the result's `via` (e.g. `api.deepseek.com`). */
    val label: String

    /** The page's text, or null when this service cannot get it. */
    suspend fun fetch(url: String): WebFallbackPage?
}

/** One search result. Candidate API; not frozen. */
@ExperimentalAgentRuntimeApi
data class WebSearchResult(
    val title: String,
    val url: String,
    /** What the page says, sized for a model to read. */
    val summary: String,
    val site: String? = null,
    /** Publication date as the service gives it, e.g. RFC 3339. */
    val date: String? = null,
)

/** A page fetched by a [WebFetchFallback] for web_fetch. Candidate API; not frozen. */
@ExperimentalAgentRuntimeApi
data class WebFallbackPage(val title: String, val markdown: String)

/** What the network backend needs from a [WebSearchProvider], without the experimental type in its signature. */
internal class WebSearchService(
    val label: String,
    /** The web_search tool's result JSON for its arguments. */
    val search: suspend (argumentsJson: String) -> String,
)

@OptIn(ExperimentalAgentRuntimeApi::class)
internal fun WebSearchProvider.asService(): WebSearchService = WebSearchService(label) { runWebSearch(this, it) }

/** What the network backend needs from a [WebFetchFallback]. */
internal class WebFetchService(
    val label: String,
    /** A page's title and Markdown when web_fetch's own request failed, or null. */
    val fetch: suspend (url: String) -> Pair<String, String>?,
)

@OptIn(ExperimentalAgentRuntimeApi::class)
internal fun WebFetchFallback.asService(): WebFetchService = WebFetchService(label) { url -> fetch(url)?.let { it.title to it.markdown } }

/** The web_search tool's result for [argumentsJson]: results, or an error the model can act on. */
@OptIn(ExperimentalAgentRuntimeApi::class)
internal suspend fun runWebSearch(provider: WebSearchProvider, argumentsJson: String): String {
    val arguments = parseSearchArguments(argumentsJson) ?: return searchError("web_search needs a query of 1 to $MAX_QUERY_CHARS characters.")
    val (query, count) = arguments
    val results = try {
        withTimeoutOrNull(WEB_SEARCH_TIMEOUT_MILLIS) { provider.search(query, count) } ?: return searchError("Web search timed out.", retryable = true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        return searchError("Web search failed: ${failure.message ?: failure.javaClass.simpleName}", retryable = true)
    }
    return buildJsonObject {
        put("ok", true)
        put("query", query)
        putJsonArray("results") {
            results.take(count).forEach { result ->
                add(buildJsonObject {
                    put("title", result.title)
                    put("url", result.url)
                    result.site?.takeIf(String::isNotBlank)?.let { put("site", it) }
                    result.date?.takeIf(String::isNotBlank)?.let { put("date", it) }
                    put("summary", result.summary.take(MAX_SUMMARY_CHARS))
                })
            }
        }
        if (results.isEmpty()) put("message", "No results; try other words.")
    }.toString()
}

/** Validates the arguments web_search would run with: a usable query. */
internal fun webSearchArgumentsError(argumentsJson: String): String? =
    if (parseSearchArguments(argumentsJson) == null) searchError("web_search needs a query of 1 to $MAX_QUERY_CHARS characters.") else null

private fun parseSearchArguments(argumentsJson: String): Pair<String, Int>? {
    val arguments = runCatching { Json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull() ?: return null
    val query = arguments["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (query.isEmpty() || query.length > MAX_QUERY_CHARS) return null
    val count = (arguments["count"]?.jsonPrimitive?.intOrNull ?: DEFAULT_RESULT_COUNT).coerceIn(1, MAX_RESULT_COUNT)
    return query to count
}

private fun searchError(message: String, retryable: Boolean = false): String = buildJsonObject {
    put("ok", false)
    put("code", RuntimeErrorCode.NETWORK_FETCH_FAILED.wireCode)
    put("message", message)
    put("retryable", retryable)
}.toString()

private const val MAX_QUERY_CHARS = 128
private const val DEFAULT_RESULT_COUNT = 5
private const val MAX_RESULT_COUNT = 10
/** About what a search service writes for a model per result; longer summaries cost tokens on every later round. */
private const val MAX_SUMMARY_CHARS = 800
private const val WEB_SEARCH_TIMEOUT_MILLIS = 20_000L

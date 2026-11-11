@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.models.deepseek
import android.content.Context
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.model.WebLookup
import cc.noharry.agent.runtime.WebFallbackPage
import cc.noharry.agent.runtime.WebSearchResult
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * DeepSeek's Search and Fetch APIs (api-docs.deepseek.com/guides/search) behind the runtime's web_search tool: unlike a
 * service's server-side search, the queries and results reach the model, the event log and the card. The Fetch API also
 * gets pages that refuse the device, for web_fetch ([FetchFallback][cc.noharry.agent.appserver.model.FetchFallback]). A
 * refused request (429, the account's limit per second) or a busy one is tried again a little later.
 */
internal class DeepSeekWebSearch(
    private val apiKey: () -> String,
    private val baseUrl: String = DEEPSEEK_TOOLS_URL,
    private val client: OkHttpClient = SEARCH_CLIENT,
) : WebLookup {
    /** DeepSeek's host, or the relay's a check found the APIs behind ([DeepSeekProbes]). */
    override val label: String = runCatching { java.net.URI(baseUrl).host }.getOrNull() ?: DEEPSEEK_HOST

    override suspend fun search(query: String, count: Int): List<WebSearchResult> {
        val body = buildJsonObject {
            put("query", query)
            put("count", count)
        }
        val results = post("search", body.toString())?.get("results")?.jsonArray ?: throw IOException("Search answered without results.")
        return results.map { it.jsonObject }.map { item ->
            WebSearchResult(
                title = item.text("title"),
                url = item.text("url"),
                summary = item.text("summary").ifBlank { item.text("snippet") },
                site = item.text("site_name").ifBlank { null },
                date = item.text("date").ifBlank { null },
            )
        }.filter { it.url.isNotBlank() }
    }

    override suspend fun fetch(url: String): WebFallbackPage? {
        val page = try {
            post("fetch", buildJsonObject { put("url", url) }.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        val content = page.text("content").ifBlank { return null }
        return WebFallbackPage(page.text("title"), content)
    }

    /** The answer's JSON, null for a page that is not there (404), or an exception the tool reports. */
    private suspend fun post(path: String, json: String): JsonObject? {
        for (attempt in 0..RETRIES) {
            if (attempt > 0) delay(RETRY_DELAY_MS * attempt)
            val request = Request.Builder()
                .url("$baseUrl/$path")
                .header("Authorization", "Bearer ${apiKey()}")
                .post(json.toRequestBody(JSON_TYPE))
                .build()
            val (code, text) = client.newCall(request).awaitText()
            when {
                code in HTTP_SUCCESS -> return Json.parseToJsonElement(text).jsonObject
                code == NOT_FOUND -> return null
                code !in RETRIED_CODES || attempt == RETRIES -> throw IOException("DeepSeek $path $code: ${text.errorMessage()}")
            }
        }
        throw IOException("DeepSeek $path did not answer.")
    }
}

/**
 * The answer's status and body. Cancelling the caller cancels the request, so the runtime's search and fallback
 * timeouts hold; a blocking execute() would have kept the tool waiting up to this client's 40 s per attempt.
 */
private suspend fun Call.awaitText(): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use { it.code to it.body?.string().orEmpty() } }
            if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
        }
    })
}

private val HTTP_SUCCESS = 200..299

/**
 * DeepSeek's Search and Fetch APIs with [profile]'s key: on DeepSeek's own host, or behind a relay where the check
 * ([DeepSeekProbes]) found them for the base URL and key the profile has now; null otherwise.
 */
internal fun ProviderConfig.deepSeekToolsOf(context: Context, profile: ProviderProfile): DeepSeekWebSearch? {
    val url = deepSeekToolsUrl(context, profile) ?: return null
    if (profile.isDeepSeek()) return DeepSeekWebSearch(apiKey = { effectiveApiKey(profile) })
    return DeepSeekWebSearch(apiKey = { effectiveApiKey(profile) }, baseUrl = url, client = deepSeekRelayClient(context))
}

internal fun ProviderConfig.deepSeekToolsUrl(context: Context, profile: ProviderProfile): String? {
    val key = effectiveApiKey(profile).ifBlank { return null }
    return if (profile.isDeepSeek()) DEEPSEEK_TOOLS_URL else DeepSeekProbes.current(context, profile, key)?.tools?.url
}

/** Whether [this] profile is on DeepSeek's own host. */
@cc.noharry.agent.appserver.AppServerPluginApi
fun ProviderProfile.isDeepSeek(): Boolean = baseUrl.isDeepSeekUrl()

/**
 * Whether [this] base URL is DeepSeek's own API: https, its host exactly (any case), the default port, no user info. A
 * profile there shares DeepSeek's key and searches with it, so a look-alike (http, another port) is not.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
fun String.isDeepSeekUrl(): Boolean = runCatching {
    val uri = java.net.URI(trim())
    uri.scheme.equals("https", ignoreCase = true) && uri.host?.lowercase() == DEEPSEEK_HOST && (uri.port == -1 || uri.port == HTTPS_PORT) && uri.rawUserInfo == null
}.getOrDefault(false)

private const val HTTPS_PORT = 443

/** DeepSeek's own API host; a profile there has the balance query and the Search and Fetch APIs. */
internal const val DEEPSEEK_HOST = "api.deepseek.com"

private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun String.errorMessage(): String =
    runCatching { Json.parseToJsonElement(this).jsonObject["error"]?.jsonObject?.text("message") }.getOrNull()?.ifBlank { null } ?: take(MAX_ERROR_CHARS)

private const val DEEPSEEK_TOOLS_URL = "https://api.deepseek.com/v1/tools"
private const val NOT_FOUND = 404
/** Read timeout, too many requests, and the service's own trouble: worth another try (the API's guidance). */
private val RETRIED_CODES = setOf(408, 429, 500, 502, 503, 504)
private const val RETRIES = 2
private const val RETRY_DELAY_MS = 600L
private const val MAX_ERROR_CHARS = 160
private val JSON_TYPE = "application/json".toMediaType()
private val SEARCH_CLIENT = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(40, TimeUnit.SECONDS)
    .build()

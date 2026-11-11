package cc.noharry.agent.appserver.models.deepseek
import android.content.Context
import cc.noharry.agent.appserver.http.appServerHttpClient
import cc.noharry.agent.appserver.httpFailure
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.alternatives
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.jsonrpc.ProtocolJson
import cc.noharry.agent.appserver.protocol.logText
import cc.noharry.agent.appserver.protocol.text as plainText
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Whether a profile that is not on api.deepseek.com still reaches DeepSeek's own API behind it (a relay, or a gateway on
 * an official key). Checked on request for the profile's endpoint, base URL and key, and kept only while those stay the
 * same; each check unlocks what it proved, through this profile's own address and key:
 * - [balance]: `/user/balance` answers with DeepSeek's balance fields → the balance query;
 * - [tools]: a one-result search answers with results → the Search and Fetch APIs (web_search, the fetch fallback, photos);
 * - [passthrough] (Messages only): only deferred tools, then a `tool_addition` naming an undeclared tool, each come back
 *   as DeepSeek's own 400 → the relay sends the body on unchanged, so native deferred tools and the system-role run
 *   context work. A gateway that rebuilds the request drops those fields and the model answers instead.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
data class DeepSeekProbe(
    val fingerprint: String,
    val balance: DeepSeekCheck,
    val tools: DeepSeekCheck,
    /** null off the Messages endpoint, which is the only one with native updates. */
    val passthrough: DeepSeekCheck?,
    val checkedAt: Long,
)

/** 「balance ✓ MODEL_BALANCE … | tools ✗ HTTP_STATUS 404 | passthrough ✓ …」 for logcat; no key. */
@cc.noharry.agent.appserver.AppServerPluginApi
fun DeepSeekProbe.describe(): String =
    listOfNotNull("balance" to balance, "tools" to tools, passthrough?.let { "passthrough" to it })
        .joinToString(" | ") { (name, check) ->
            "$name ${if (check.passed) "✓ ${check.url} ${check.note.logText()}" else "✗ ${check.note.logText()}"}"
        }

/** Passed when [url] is set: where it worked. [note] says what came back, for the Settings page and logcat. */
@cc.noharry.agent.appserver.AppServerPluginApi
data class DeepSeekCheck(val url: String?, val note: Finding) {
    val passed: Boolean get() = url != null
}

/** One check per profile id, beside the profile settings rather than in them. */
@cc.noharry.agent.appserver.AppServerPluginApi
object DeepSeekProbes {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun saved(context: Context, profileId: String): DeepSeekProbe? =
        prefs(context).getString(profileId, null)?.let { runCatching { decodeDeepSeekProbe(it) }.getOrNull() }

    /** [profile]'s check, only if it was made for the endpoint, base URL and [apiKey] the profile has now. */
    fun current(context: Context, profile: ProviderProfile, apiKey: String): DeepSeekProbe? =
        saved(context, profile.id)?.takeIf { it.fingerprint == deepSeekProbeFingerprint(profile.endpoint, profile.baseUrl, apiKey) }

    suspend fun check(context: Context, profile: ProviderProfile, apiKey: String): DeepSeekProbe = withContext(Dispatchers.IO) {
        probeDeepSeek(profile.endpoint, profile.baseUrl, apiKey, profile.model, deepSeekProbeClient(context))
            .also { save(context, profile.id, it) }
    }

    fun save(context: Context, profileId: String, probe: DeepSeekProbe) {
        prefs(context).edit().putString(profileId, encodeDeepSeekProbe(probe)).apply()
    }

    /** Runs [block] on this object's own scope, for callers without one (the adb receiver). */
    fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PROBE_PREFS, Context.MODE_PRIVATE)
}

internal suspend fun probeDeepSeek(
    endpoint: ProviderEndpoint,
    baseUrl: String,
    apiKey: String,
    model: String,
    client: OkHttpClient,
): DeepSeekProbe = withContext(Dispatchers.IO) {
    val key = apiKey.trim()
    // At once, so a relay that does not answer costs one timeout rather than five.
    val balance = async {
        firstPassing(deepSeekBalanceCandidates(baseUrl)) { url -> checkBalance(client, url, key) }
    }
    val tools = async {
        firstPassing(deepSeekToolsCandidates(baseUrl)) { url -> checkSearch(client, url, key) }
    }
    val passthrough = async {
        if (endpoint == ProviderEndpoint.MESSAGES) checkPassthrough(client, baseUrl, key, model) else null
    }
    DeepSeekProbe(
        fingerprint = deepSeekProbeFingerprint(endpoint, baseUrl, key),
        balance = balance.await(),
        tools = tools.await(),
        passthrough = passthrough.await(),
        checkedAt = System.currentTimeMillis(),
    )
}

/**
 * Where DeepSeek's root may sit behind [baseUrl]: the base without its `/v1` or `/anthropic` (a relay keeping DeepSeek's
 * paths), then the base itself (a relay mapping its own prefix, such as `/v1`, onto that root).
 */
internal fun deepSeekRootCandidates(baseUrl: String): List<String> {
    val base = baseUrl.trim().trimEnd('/')
    val root = API_SUFFIXES.firstOrNull { base.endsWith(it) }?.let { base.removeSuffix(it) } ?: base
    return listOf(root, base).filter { it.startsWith("http://") || it.startsWith("https://") }.distinct()
}

internal fun deepSeekBalanceCandidates(baseUrl: String): List<String> = deepSeekRootCandidates(baseUrl).map { "$it/user/balance" }

internal fun deepSeekToolsCandidates(baseUrl: String): List<String> = deepSeekRootCandidates(baseUrl).map { "$it/v1/tools" }

/** Ties a check to what it was made with; the key goes in only as part of a hash. */
internal fun deepSeekProbeFingerprint(endpoint: ProviderEndpoint, baseUrl: String, apiKey: String): String {
    val identity = listOf(endpoint.wireName, baseUrl.trim().trimEnd('/'), apiKey.trim()).joinToString("\n")
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** The trust some devices need for some relays (the host's extra roots), with timeouts for a search. */
internal fun deepSeekRelayClient(context: Context): OkHttpClient =
    appServerHttpClient(context).connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()

private fun deepSeekProbeClient(context: Context): OkHttpClient =
    appServerHttpClient(context).connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

private inline fun firstPassing(urls: List<String>, check: (String) -> DeepSeekCheck): DeepSeekCheck {
    val failed = mutableListOf<Finding>()
    for (url in urls) {
        val result = check(url)
        if (result.passed) return result
        failed += result.note
    }
    val notes = failed.distinct()
    return DeepSeekCheck(null, if (notes.isEmpty()) finding(FindingCode.MODEL_PROBE_NOT_HTTP) else notes.singleOrNull() ?: alternatives(notes))
}

private fun checkBalance(client: OkHttpClient, url: String, key: String) = attempt {
    val request = Request.Builder().url(url).header("Authorization", "Bearer $key").header("Accept", "application/json").build()
    val (code, body) = client.call(request)
    val json = body.jsonObjectOrNull()
    when {
        code != HTTP_OK -> DeepSeekCheck(null, body.failure(code))
        json == null || ("balance_infos" !in json && "is_available" !in json) ->
            DeepSeekCheck(null, finding(FindingCode.MODEL_PROBE_NOT_BALANCE))
        else -> DeepSeekCheck(url, Finding(FindingCode.MODEL_BALANCE, balance = parseDeepSeekBalance(body).view()))
    }
}

/** One real search with one result (a paid request); a path the relay does not have answers 404 without reaching DeepSeek. */
private fun checkSearch(client: OkHttpClient, url: String, key: String) = attempt {
    val body = buildJsonObject {
        put("query", PROBE_QUERY)
        put("count", 1)
    }
    val request = Request.Builder().url("$url/search").header("Authorization", "Bearer $key").post(body.toString().toRequestBody(JSON_TYPE)).build()
    val (code, text) = client.call(request)
    when {
        code != HTTP_OK -> DeepSeekCheck(null, text.failure(code))
        text.jsonObjectOrNull()?.get("results") !is JsonArray -> DeepSeekCheck(null, finding(FindingCode.MODEL_PROBE_NOT_SEARCH))
        else -> DeepSeekCheck(url, finding(FindingCode.MODEL_PROBE_SEARCH_AVAILABLE))
    }
}

/**
 * Two requests with the run's headers that DeepSeek refuses before the model runs, so a pass costs nothing: only
 * deferred tools ("All tools cannot be deferred") shows `defer_loading` arrives, and a
 * system update loading a tool nobody declared ("tool_addition references unknown tool") shows the in-history system
 * message and `tool_addition` arrive. A relay that drops either lets the model answer a few tokens instead.
 */
private fun checkPassthrough(
    client: OkHttpClient,
    baseUrl: String,
    key: String,
    model: String,
): DeepSeekCheck {
    if (model.isBlank()) return DeepSeekCheck(null, finding(FindingCode.MODEL_PROBE_NO_MODEL))
    val url = baseUrl.trim().trimEnd('/').let { if (it.endsWith(MESSAGES_PATH)) it else it + MESSAGES_PATH }
    return attempt {
        val (deferCode, deferText) = client.call(messagesRequest(url, key, deferOnlyBody(model)))
        when {
            deferCode == HTTP_BAD_REQUEST && deferText.contains(ALL_DEFERRED_REFUSAL, ignoreCase = true) -> Unit
            deferCode == HTTP_OK -> return@attempt DeepSeekCheck(null, finding(FindingCode.MODEL_PROBE_DROPPED, "defer_loading"))
            else -> return@attempt DeepSeekCheck(null, deferText.failure(deferCode))
        }
        val (code, text) = client.call(messagesRequest(url, key, toolAdditionBody(model)))
        when {
            code == HTTP_BAD_REQUEST && text.contains(UNKNOWN_TOOL_REFUSAL, ignoreCase = true) ->
                DeepSeekCheck(url, finding(FindingCode.MODEL_PROBE_PASSED_THROUGH))
            code == HTTP_OK -> DeepSeekCheck(null, finding(FindingCode.MODEL_PROBE_DROPPED, "tool_addition"))
            else -> DeepSeekCheck(null, text.failure(code))
        }
    }
}

private fun messagesRequest(url: String, key: String, body: JsonObject): Request = Request.Builder().url(url)
    .header("x-api-key", key)
    .header("anthropic-version", MESSAGES_API_VERSION)
    .header("anthropic-beta", TOOL_CHANGES_BETA)
    .post(body.toString().toRequestBody(JSON_TYPE))
    .build()

/** Every tool deferred, which DeepSeek refuses once `defer_loading` reaches it. */
internal fun deferOnlyBody(model: String): JsonObject = probeBody(model, deferredOnly = true)

/** A plain and a deferred tool, and a system update loading a tool nobody declared. */
internal fun toolAdditionBody(model: String): JsonObject = probeBody(model, deferredOnly = false)

private fun probeBody(model: String, deferredOnly: Boolean): JsonObject = buildJsonObject {
    put("model", model)
    put("max_tokens", PROBE_MAX_TOKENS)
    put("stream", false)
    // No thinking, so nothing about its budget is refused ahead of the tools.
    putJsonObject("thinking") { put("type", "disabled") }
    putJsonArray("tools") {
        if (!deferredOnly) add(probeTool(PROBE_TOOL, deferred = false))
        add(probeTool(PROBE_DEFERRED_TOOL, deferred = true))
    }
    putJsonArray("messages") {
        add(buildJsonObject {
            put("role", "user")
            put("content", "hi")
        })
        if (!deferredOnly) {
            add(buildJsonObject {
                put("role", "system")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "tool_addition")
                        putJsonObject("tool") {
                            put("type", "tool_reference")
                            put("name", PROBE_UNDECLARED_TOOL)
                        }
                    })
                })
            })
        }
    }
}

private fun probeTool(name: String, deferred: Boolean): JsonObject = buildJsonObject {
    put("name", name)
    put("description", "Connection check.")
    putJsonObject("input_schema") { put("type", "object") }
    if (deferred) put("defer_loading", true)
}

internal fun encodeDeepSeekProbe(probe: DeepSeekProbe): String = buildJsonObject {
    put("fingerprint", probe.fingerprint)
    put("balance", probe.balance.toJson())
    put("tools", probe.tools.toJson())
    probe.passthrough?.let { put("passthrough", it.toJson()) }
    put("checkedAt", probe.checkedAt)
}.toString()

internal fun decodeDeepSeekProbe(text: String): DeepSeekProbe {
    val json = Json.parseToJsonElement(text).jsonObject
    return DeepSeekProbe(
        fingerprint = json.text("fingerprint"),
        balance = json.check("balance") ?: throw IOException("No balance check."),
        tools = json.check("tools") ?: throw IOException("No tools check."),
        passthrough = json.check("passthrough"),
        checkedAt = json["checkedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
    )
}

private fun DeepSeekCheck.toJson(): JsonObject = buildJsonObject {
    url?.let { put("url", it) }
    put("finding", ProtocolJson.encodeToJsonElement(Finding.serializer(), note))
}

/** A saved check; one saved before findings has its note in the words of then, kept as it is. */
private fun JsonObject.check(key: String): DeepSeekCheck? = (this[key] as? JsonObject)?.let { json ->
    val note = (json["finding"] as? JsonObject)?.let { runCatching { ProtocolJson.decodeFromJsonElement(Finding.serializer(), it) }.getOrNull() }
        ?: plainText(json.text("note"))
    DeepSeekCheck(json.text("url").ifBlank { null }, note)
}

private inline fun attempt(block: () -> DeepSeekCheck): DeepSeekCheck = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    val reason = (error.message ?: error.javaClass.simpleName).take(MAX_NOTE_CHARS)
    DeepSeekCheck(null, finding(FindingCode.MODEL_PROBE_UNREACHABLE, reason))
}

private fun OkHttpClient.call(request: Request): Pair<Int, String> =
    newCall(request).execute().use { it.code to it.body?.string().orEmpty() }

private fun String.jsonObjectOrNull(): JsonObject? = runCatching { Json.parseToJsonElement(this).jsonObject }.getOrNull()

/** 「HTTP 404」 with the answer's own error message when it has one; never the body itself, which may be a web page. */
private fun String.failure(code: Int): Finding {
    val message = jsonObjectOrNull()?.let { json ->
        (json["error"] as? JsonObject)?.text("message") ?: json.text("message")
    }?.ifBlank { null }
    return httpFailure(code, message?.take(MAX_NOTE_CHARS))
}

private fun JsonObject.text(key: String): String = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()

private val API_SUFFIXES = listOf("/v1", "/anthropic")
private val JSON_TYPE = "application/json".toMediaType()
private const val PROBE_PREFS = "app_server_deepseek_probe"
private const val PROBE_QUERY = "DeepSeek"
private const val PROBE_TOOL = "connection_check"
private const val PROBE_DEFERRED_TOOL = "connection_check_deferred"
private const val PROBE_UNDECLARED_TOOL = "connection_check_undeclared"
private const val ALL_DEFERRED_REFUSAL = "cannot be deferred"
private const val UNKNOWN_TOOL_REFUSAL = "references unknown tool"
private const val MESSAGES_PATH = "/messages"
private const val MESSAGES_API_VERSION = "2023-06-01"
private const val TOOL_CHANGES_BETA = "mid-conversation-tool-changes-2026-07-01"
private const val PROBE_MAX_TOKENS = 16
private const val HTTP_OK = 200
private const val HTTP_BAD_REQUEST = 400
private const val MAX_NOTE_CHARS = 120

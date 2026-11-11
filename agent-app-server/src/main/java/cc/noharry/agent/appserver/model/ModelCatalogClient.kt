package cc.noharry.agent.appserver.model
import android.content.Context
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A service's model list as its wire protocol lists models: `GET <base URL>/models` (or [fetch]'s `modelsUrl`), each
 * entry's `id` from `data`, with the wire's key header.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
class ModelCatalogClient(
    private val transport: HttpTransport,
) {
    /**
     * The ids at [baseUrl]'s list (asked as [provider]'s wire asks), or at [modelsUrl] when the service lists its models
     * elsewhere (an OpenAI-compatible list, asked with a bearer key), without [idPrefix] where an id starts with it. The
     * key is read for each address asked ([ConnectionKey.sendTo]): a saved key the list's address may not have is a
     * [cc.noharry.agent.appserver.FoundException], and nothing is sent.
     */
    suspend fun fetch(
        provider: ModelProvider,
        apiKey: ConnectionKey,
        baseUrl: String,
        modelsUrl: String? = null,
        idPrefix: String = "",
    ): List<String> {
        val url = modelsUrl ?: "${baseUrl.trimEnd('/')}/models"
        // A service's own list is OpenAI-compatible, whatever endpoint the profile is on.
        val wire = if (modelsUrl != null) ModelProvider.OPENAI else provider
        val response = transport.execute(wire.modelsRequest(apiKey.sendTo(url), url))
        if (response.statusCode in SUCCESS_STATUS_RANGE) {
            return parseModelIds(response.body.toString(Charsets.UTF_8), idPrefix)
        }
        // A gateway's Anthropic-compatible path often has no list of its own, while the same backend lists its models on
        // its OpenAI-compatible path beside it.
        val fallbackUrl = if (modelsUrl != null) null else openAiModelsFallbackUrl(baseUrl)?.let { "$it/models" }
        if (fallbackUrl != null && apiKey.allows(fallbackUrl)) {
            val fallbackResponse = transport.execute(ModelProvider.OPENAI.modelsRequest(apiKey.sendTo(fallbackUrl), fallbackUrl))
            if (fallbackResponse.statusCode in SUCCESS_STATUS_RANGE) {
                return parseModelIds(fallbackResponse.body.toString(Charsets.UTF_8), idPrefix)
            }
        }
        error("Model catalog request failed: status=${response.statusCode}")
    }

    /** [fetch] with [apiKey], a key the caller has itself (its own, or one the person typed), which goes where it asks. */
    suspend fun fetch(
        provider: ModelProvider,
        apiKey: String,
        baseUrl: String,
        modelsUrl: String? = null,
        idPrefix: String = "",
    ): List<String> = fetch(provider, ConnectionKey.typed(apiKey), baseUrl, modelsUrl, idPrefix)
}

/**
 * The models a profile at [baseUrl] offers: its service adapter's own listing ([ProviderAdapter.listModels]) when it
 * has one, the service's list alone when it lists none ([ModelService.listsModels]), else the wire protocol's list at
 * the service's models endpoint ([ModelService.modelsUrl], or `<base URL>/models`). [apiKey] goes only where it may.
 */
internal suspend fun listModels(context: Context, transport: HttpTransport, profile: ProviderProfile, apiKey: ConnectionKey): List<String> {
    val (adapter, service) = serviceAt(profile.baseUrl) ?: return ModelCatalogClient(transport).fetch(profile.provider, apiKey, profile.baseUrl)
    adapter.listModels(context, service, profile, apiKey)?.let { return it }
    if (!service.listsModels) return service.models.map { it.id }
    return ModelCatalogClient(transport).fetch(profile.provider, apiKey, profile.baseUrl, service.modelsUrl, service.listedIdPrefix)
}

/**
 * The OpenAI-compatible base beside an Anthropic-compatible one: a base URL ending `/anthropic` or `/anthropic/v1` with
 * that suffix replaced by `/v1`; null for any other.
 */
private fun openAiModelsFallbackUrl(baseUrl: String): String? {
    val trimmed = baseUrl.trimEnd('/')
    return when {
        trimmed.endsWith(ANTHROPIC_V1_SUFFIX, ignoreCase = true) -> trimmed.dropLast(ANTHROPIC_V1_SUFFIX.length) + "/v1"
        trimmed.endsWith(ANTHROPIC_SUFFIX, ignoreCase = true) -> trimmed.dropLast(ANTHROPIC_SUFFIX.length) + "/v1"
        else -> null
    }
}

internal fun parseModelIds(body: String, idPrefix: String = ""): List<String> {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
    val data = root["data"] as? JsonArray ?: return emptyList()
    return data.mapNotNull { element ->
        val model = element as? JsonObject ?: return@mapNotNull null
        model["id"]?.jsonPrimitive?.contentOrNull?.removePrefix(idPrefix)?.takeIf { it.isNotBlank() }
    }.distinct().sorted()
}

private fun ModelProvider.modelsRequest(apiKey: String, url: String): HttpRequest {
    val headers = when (this) {
        ModelProvider.OPENAI -> mapOf("Authorization" to "Bearer $apiKey")
        ModelProvider.ANTHROPIC -> mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to MESSAGES_API_VERSION,
        )
    }
    return HttpRequest(method = "GET", url = url, headers = headers)
}

/** The version header the Messages API takes. */
private const val MESSAGES_API_VERSION = "2023-06-01"
private const val ANTHROPIC_SUFFIX = "/anthropic"
private const val ANTHROPIC_V1_SUFFIX = "/anthropic/v1"
private val SUCCESS_STATUS_RANGE = 200..299

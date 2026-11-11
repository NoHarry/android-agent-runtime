package cc.noharry.agent.appserver.model
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@cc.noharry.agent.appserver.AppServerPluginApi
class ModelCatalogClient(
    private val transport: HttpTransport,
) {
    suspend fun fetch(provider: ModelProvider, apiKey: String, baseUrl: String): List<String> {
        val response = transport.execute(provider.modelsRequest(apiKey, baseUrl))
        if (response.statusCode in SUCCESS_STATUS_RANGE) {
            return parseModelIds(response.body.toString(Charsets.UTF_8))
        }
        // Some providers (e.g. DeepSeek's /anthropic proxy) do not expose a
        // /models endpoint on their compatibility gateway. Fall back to the
        // OpenAI-style /v1/models path which the same backend typically serves.
        val fallbackUrl = openAiModelsFallbackUrl(baseUrl)
        if (fallbackUrl != null && fallbackUrl != baseUrl) {
            val fallbackResponse = transport.execute(
                ModelProvider.OPENAI.modelsRequest(apiKey, fallbackUrl),
            )
            if (fallbackResponse.statusCode in SUCCESS_STATUS_RANGE) {
                return parseModelIds(fallbackResponse.body.toString(Charsets.UTF_8))
            }
        }
        error("Model catalog request failed: status=${response.statusCode}")
    }
}

/**
 * Derives an OpenAI-style models URL from a provider baseUrl.
 *
 * DeepSeek's Anthropic proxy is at `https://api.deepseek.com/anthropic`,
 * which only serves `/v1/messages` — not `/models`. The model list is
 * available at the OpenAI endpoint `https://api.deepseek.com/v1/models`.
 * This function strips a trailing `/anthropic` (or `/anthropic/v1`) suffix
 * and replaces it with `/v1` so the fallback hits the correct path.
 */
private fun openAiModelsFallbackUrl(baseUrl: String): String? {
    val trimmed = baseUrl.trimEnd('/')
    return when {
        trimmed.endsWith("/anthropic/v1", ignoreCase = true) ->
            trimmed.substringBeforeLast("/anthropic/v1", trimmed) + "/v1"
        trimmed.endsWith("/anthropic", ignoreCase = true) ->
            trimmed.substringBeforeLast("/anthropic", trimmed) + "/v1"
        else -> null
    }
}

internal fun parseModelIds(body: String): List<String> {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
    val data = root["data"] as? JsonArray ?: return emptyList()
    return data.mapNotNull { element ->
        val model = element as? JsonObject ?: return@mapNotNull null
        model["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }.distinct().sorted()
}

private fun ModelProvider.modelsRequest(apiKey: String, baseUrl: String): HttpRequest {
    val headers = when (this) {
        ModelProvider.OPENAI -> mapOf("Authorization" to "Bearer $apiKey")
        ModelProvider.ANTHROPIC -> mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to ANTHROPIC_VERSION,
        )
    }
    return HttpRequest(
        method = "GET",
        url = "${baseUrl.trimEnd('/')}/models",
        headers = headers,
    )
}

private const val ANTHROPIC_VERSION = "2023-06-01"
private val SUCCESS_STATUS_RANGE = 200..299

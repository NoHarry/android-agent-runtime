package cc.noharry.agent.appserver.usage
import cc.noharry.agent.appserver.protocol.TokenUsageState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@cc.noharry.agent.appserver.AppServerPluginApi
data class TokenDelta(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val cachedInputTokens: Int,
    val cacheHitTokens: Int,
    val cacheMissTokens: Int,
    val reasoningTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
)

internal fun cc.noharry.agent.Usage.toUsageDelta(): TokenDelta {
    // One event is one model request: the runtime sums the call's usage frames and normalizes Anthropic's exclusive
    // cache counts into inclusive inputTokens, so input already covers the cache reads.
    val input = inputTokens.coerceAtLeast(cacheReadTokens + cacheWriteTokens)
    val miss = (input - cacheReadTokens).coerceAtLeast(0)
    return TokenDelta(
        inputTokens = input,
        outputTokens = outputTokens,
        totalTokens = input + outputTokens + reasoningTokens,
        cachedInputTokens = cacheReadTokens,
        cacheHitTokens = cacheReadTokens,
        cacheMissTokens = miss,
        reasoningTokens = reasoningTokens,
        cacheWriteTokens = cacheWriteTokens,
    )
}

internal fun parseTokenUsage(text: String): TokenDelta? =
    usageObjects(text).mapNotNull(::usageDelta).lastOrNull()

private fun usageObjects(text: String): List<JsonObject> {
    val roots = mutableListOf<JsonObject>()
    parseRoot(text)?.let { roots += it }
    text.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith(SSE_DATA_PREFIX) }
        .map { it.removePrefix(SSE_DATA_PREFIX).trim() }
        .filter { it.isNotBlank() && it != SSE_DONE }
        .mapNotNull(::parseRoot)
        .forEach { roots += it }
    return roots.flatMap { root ->
        listOfNotNull(
            root.objectValue("usage"),
            root.objectValue("response")?.objectValue("usage"),
            // Anthropic message_start nests usage under message.
            root.objectValue("message")?.objectValue("usage"),
        )
    }
}

private fun usageDelta(usage: JsonObject): TokenDelta? {
    val details = usage.objectValue("prompt_tokens_details") ?: usage.objectValue("input_tokens_details")
    val deepSeekHit = usage.intValue("prompt_cache_hit_tokens")
    val anthropicHit = usage.intValue("cache_read_input_tokens")
    val cached = deepSeekHit ?: anthropicHit ?: details?.intValue("cached_tokens") ?: 0
    val deepSeekMiss = usage.intValue("prompt_cache_miss_tokens")
    val anthropicWrite = usage.intValue("cache_creation_input_tokens")
    val anthropicExclusiveCache =
        usage.containsKey("cache_read_input_tokens") || usage.containsKey("cache_creation_input_tokens")
    // DeepSeek miss is uncached input; Anthropic creation is cache write only.
    val cacheWrite = deepSeekMiss ?: anthropicWrite ?: 0
    val rawInput = usage.intValue("prompt_tokens") ?: usage.intValue("input_tokens") ?: 0
    val input = if (anthropicExclusiveCache) {
        rawInput + cached + cacheWrite
    } else {
        maxOf(rawInput, cached + (deepSeekMiss ?: maxOf(rawInput - cached, 0)))
    }
    val miss = deepSeekMiss ?: (input - cached).coerceAtLeast(0)
    val output = usage.intValue("completion_tokens") ?: usage.intValue("output_tokens") ?: 0
    val reasoning = usage.objectValue("completion_tokens_details")
        ?.intValue("reasoning_tokens")
        ?: usage.objectValue("output_tokens_details")?.intValue("reasoning_tokens")
        ?: 0
    val total = usage.intValue("total_tokens") ?: (input + output + reasoning)
    if (input == 0 && output == 0 && total == 0 && cached == 0 && cacheWrite == 0) return null
    return TokenDelta(
        inputTokens = input,
        outputTokens = output,
        totalTokens = total,
        cachedInputTokens = cached,
        cacheHitTokens = cached,
        cacheMissTokens = miss,
        reasoningTokens = reasoning,
        cacheWriteTokens = cacheWrite,
    )
}

private fun parseRoot(text: String): JsonObject? =
    try {
        JSON.parseToJsonElement(text.trim()).jsonObject
    } catch (_: RuntimeException) {
        null
    }

private fun JsonObject.objectValue(name: String): JsonObject? =
    this[name] as? JsonObject

private fun JsonObject.intValue(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull

private val JSON = Json { ignoreUnknownKeys = true }
private const val SSE_DATA_PREFIX = "data:"
private const val SSE_DONE = "[DONE]"
private const val PERCENT = 100

/** [this] with one more request's [delta]. */
internal fun TokenUsageState.plus(delta: TokenDelta): TokenUsageState =
    copy(
        requestCount = requestCount + 1,
        inputTokens = inputTokens + delta.inputTokens,
        outputTokens = outputTokens + delta.outputTokens,
        reasoningTokens = reasoningTokens + delta.reasoningTokens,
        totalTokens = totalTokens + delta.totalTokens,
        cachedInputTokens = cachedInputTokens + delta.cachedInputTokens,
        cacheWriteTokens = cacheWriteTokens + delta.cacheWriteTokens,
        cacheHitTokens = cacheHitTokens + delta.cacheHitTokens,
        cacheMissTokens = cacheMissTokens + delta.cacheMissTokens,
    )

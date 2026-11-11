package cc.noharry.agent.appserver.usage
import cc.noharry.agent.appserver.protocol.ModelPrice
import cc.noharry.agent.appserver.protocol.ModelUsage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One model request as the runtime reported it. */
@cc.noharry.agent.appserver.AppServerPluginApi
fun TokenDelta.toModelUsage() = ModelUsage(
    requests = 1,
    inputTokens = inputTokens.toLong(),
    cacheReadTokens = cacheHitTokens.toLong(),
    cacheWriteTokens = cacheWriteTokens.toLong(),
    outputTokens = outputTokens.toLong(),
)

/**
 * The models.dev price for [model] (a proxy prefix such as `cline-pass/` is ignored). The first-party provider,
 * guessed from the name, wins; otherwise any provider listing the exact ID, then IDs equal up to `.`/`-`. Free
 * plan entries (zero cost) are skipped, and among several resellers the median input price is taken. DeepSeek is
 * always priced at DeepSeek's own list: every flash model at deepseek-flash, and other versions
 * the list lacks at the closest official model of the same tier.
 */
internal fun matchModelsDevPrice(catalog: JsonObject, model: String): ModelPrice? {
    val key = model.substringAfterLast('/').lowercase()
    val loose = key.looseModelKey()
    val official = officialProviders(key)
    val entries = catalog.entries.flatMap { (providerId, provider) ->
        val models = (provider as? JsonObject)?.get("models") as? JsonObject ?: return@flatMap emptyList()
        models.entries.mapNotNull { (id, entry) -> (entry as? JsonObject)?.let { Triple(providerId, id.lowercase(), it) } }
    }
    fun priced(candidates: List<Triple<String, String, JsonObject>>): ModelPrice? {
        val prices = candidates.mapNotNull { (provider, _, entry) -> entry.price(provider) }.filter { it.input > 0 || it.output > 0 }
        return prices.sortedBy { it.input }.getOrNull((prices.size - 1) / 2)
    }
    tierAlias(key)?.let { alias -> pricedAs(entries.filter { it.first in official && it.second == alias }, alias)?.let { return it } }
    val firstParty = priced(entries.filter { it.first in official && it.second.substringAfterLast('/') == key })
        ?: priced(entries.filter { it.first in official && it.second.substringAfterLast('/').looseModelKey() == loose })
    if (firstParty != null) return firstParty
    if (official.any(OFFICIAL_ONLY::contains)) return closestOfficialTier(entries.filter { it.first in official }, key)
    return priced(entries.filter { it.second.substringAfterLast('/') == key })
        ?: priced(entries.filter { it.first in official && it.second.substringAfterLast('/').looseModelKey() == loose })
        ?: priced(entries.filter { it.second.substringAfterLast('/').looseModelKey() == loose })
}

/**
 * The official model of [key]'s tier (its last word, such as flash or pro) whose name shares the longest start with
 * [key]; ties go to the plainer name. Labelled with the model it was priced at.
 */
private fun closestOfficialTier(official: List<Triple<String, String, JsonObject>>, key: String): ModelPrice? {
    val tier = key.substringAfterLast('-')
    // Longest shared start wins; among equals, the plainer (shorter) name.
    val best = official.filter { (_, id, _) -> id.split('-').contains(tier) }
        .maxWithOrNull(compareBy({ it.second.commonPrefixWith(key).length }, { -it.second.length }))
        ?: return null
    return pricedAs(listOf(best), best.second)
}

/** [candidates]' price labelled with the official model it was taken from. */
private fun pricedAs(candidates: List<Triple<String, String, JsonObject>>, alias: String): ModelPrice? =
    candidates.firstNotNullOfOrNull { (provider, _, entry) -> entry.price(provider)?.takeIf { it.input > 0 || it.output > 0 } }
        ?.copy(pricedAs = alias)

/** Owner's pricing aliases: DeepSeek prices every flash model at deepseek-flash. */
private fun tierAlias(key: String): String? =
    if (key.startsWith("deepseek") && key.split('-', '.').contains("flash")) DEEPSEEK_FLASH else null

private fun JsonObject.price(provider: String): ModelPrice? {
    val cost = this["cost"] as? JsonObject ?: return null
    fun field(name: String) = cost[name]?.jsonPrimitive?.doubleOrNull
    return ModelPrice(
        input = field("input") ?: return null,
        output = field("output") ?: return null,
        cacheRead = field("cache_read"),
        cacheWrite = field("cache_write"),
        source = provider,
    )
}

private fun String.looseModelKey(): String = replace('.', '-').replace('_', '-').removeSuffix("-latest")

private fun officialProviders(key: String): Set<String> = when {
    key.startsWith("deepseek") -> setOf("deepseek")
    key.startsWith("qwen") -> setOf("alibaba", "alibaba-cn")
    key.startsWith("mimo") -> setOf("xiaomi")
    key.startsWith("gpt") || key.startsWith("o1") || key.startsWith("o3") || key.startsWith("o4") -> setOf("openai")
    key.startsWith("claude") -> setOf("anthropic")
    key.startsWith("gemini") -> setOf("google")
    key.startsWith("kimi") || key.startsWith("moonshot") -> setOf("moonshotai", "moonshotai-cn")
    key.startsWith("glm") -> setOf("zhipuai", "zai")
    key.startsWith("minimax") || key.startsWith("abab") -> setOf("minimax")
    else -> emptySet()
}

/** Vendors priced only at their own list, never a reseller's. */
private val OFFICIAL_ONLY = setOf("deepseek")
private const val DEEPSEEK_FLASH = "deepseek-flash"

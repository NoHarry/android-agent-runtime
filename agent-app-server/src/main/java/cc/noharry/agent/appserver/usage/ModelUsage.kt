package cc.noharry.agent.appserver.usage
import cc.noharry.agent.appserver.model.modelPrice
import cc.noharry.agent.appserver.protocol.ModelPrice
import cc.noharry.agent.appserver.protocol.PriceBook
import cc.noharry.agent.appserver.protocol.ModelUsage
import java.time.LocalDate
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
 * The models.dev price for [model] (a relay's prefix such as `relay/` is ignored): the listings of its exact ID, else of
 * IDs equal up to `.`, `_`, `-` and a `-latest` suffix, from every provider that lists it. Free plan entries (zero cost)
 * are skipped, and among several the median input price is taken. A model a service's adapter prices is priced from its
 * list instead ([cc.noharry.agent.appserver.model.ProviderAdapter.price]).
 */
internal fun matchModelsDevPrice(catalog: JsonObject, model: String): ModelPrice? {
    val key = model.substringAfterLast('/').lowercase()
    val loose = key.looseModelKey()
    val entries = catalog.entries.flatMap { (providerId, provider) ->
        val models = (provider as? JsonObject)?.get("models") as? JsonObject ?: return@flatMap emptyList()
        models.entries.mapNotNull { (id, entry) -> (entry as? JsonObject)?.let { Triple(providerId, id.lowercase(), it) } }
    }
    fun priced(candidates: List<Triple<String, String, JsonObject>>): ModelPrice? {
        val prices = candidates.mapNotNull { (provider, _, entry) -> entry.price(provider) }.filter { it.input > 0 || it.output > 0 }
        return prices.sortedBy { it.input }.getOrNull((prices.size - 1) / 2)
    }
    return priced(entries.filter { it.second.substringAfterLast('/') == key })
        ?: priced(entries.filter { it.second.substringAfterLast('/').looseModelKey() == loose })
}

/**
 * [this] book with each of [models] (and each model it prices) priced as the server takes it on [day]: a price typed in
 * stays, else the host's or its service's ([modelPrice], for the profile at the base URL it last ran at, [ranAt]), else
 * the one the last sync found.
 */
internal fun PriceBook.withKnownPrices(models: Collection<String>, ranAt: Map<String, String> = emptyMap(), day: LocalDate = LocalDate.now()): PriceBook {
    val priced = prices.toMutableMap()
    (models + prices.keys).toSet().forEach { model ->
        if (priced[model]?.manual == true) return@forEach
        modelPrice(model, ranAt[model], day)?.let { priced[model] = it }
    }
    return copy(prices = priced)
}

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

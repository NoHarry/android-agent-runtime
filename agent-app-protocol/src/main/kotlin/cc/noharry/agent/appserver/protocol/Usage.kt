package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One model's usage over a day. Input includes cache reads and writes; output includes reasoning tokens. */
@Serializable
data class ModelUsage(
    val requests: Long = 0,
    val inputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val outputTokens: Long = 0,
) {
    operator fun plus(other: ModelUsage) = ModelUsage(
        requests + other.requests,
        inputTokens + other.inputTokens,
        cacheReadTokens + other.cacheReadTokens,
        cacheWriteTokens + other.cacheWriteTokens,
        outputTokens + other.outputTokens,
    )
}

/** USD per million tokens. A missing cache price bills those tokens at the input price. */
@Serializable
data class ModelPrice(
    val input: Double,
    val output: Double,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
    /** The models.dev provider it came from ("deepseek"); blank when typed in. */
    val source: String = "",
    /** The model whose price it is, when this model had none of its own (DeepSeek's flash models at deepseek-flash). */
    val pricedAs: String? = null,
    /** Typed in Settings: a sync never overwrites it. */
    val manual: Boolean = false,
)

fun ModelUsage.costUsd(price: ModelPrice): Double {
    val fresh = (inputTokens - cacheReadTokens - cacheWriteTokens).coerceAtLeast(0)
    return (
        fresh * price.input +
            cacheReadTokens * (price.cacheRead ?: price.input) +
            cacheWriteTokens * (price.cacheWrite ?: price.input) +
            outputTokens * price.output
        ) / TOKENS_PER_MILLION
}

/** Usage by day (ISO date, device time zone) and model. */
typealias UsageDays = Map<String, Map<String, ModelUsage>>

/** Model prices plus the dollar exchange rate costs are shown at. */
@Serializable
data class PriceBook(
    val prices: Map<String, ModelPrice> = emptyMap(),
    val usdToCny: Double = DEFAULT_USD_TO_CNY,
    val syncedAtEpochMillis: Long? = null,
)

/** Usage and cost: the ledger and the price book. */
@Serializable
data class UsageData(val days: UsageDays = emptyMap(), val prices: PriceBook = PriceBook())

/**
 * Bumped whenever the ledger or the prices change; the ledger itself is read with [UsageRequests.READ] (it grows
 * with every model call, too much to send each time).
 */
@Serializable
@SerialName("usage")
data class UsageSection(val revision: Long = 0) : StateSection

/** A hand-typed price for [model], or none to hand it back to the next sync. */
@Serializable
data class ModelPriceParams(val model: String, val price: ModelPrice? = null)

@Serializable
data class RateParams(val usdToCny: Double)

/** How a models.dev sync went: [matched] of [total] models priced, or why it failed. */
@Serializable
data class PriceSyncResult(val matched: Int = 0, val total: Int = 0, val failure: String? = null)

/** Usage and cost requests. */
object UsageRequests {
    val READ = RequestMethod("usage/read", Empty.serializer(), UsageData.serializer())
    val SET_PRICE = RequestMethod("usage/setPrice", ModelPriceParams.serializer(), Empty.serializer())
    val SET_RATE = RequestMethod("usage/setRate", RateParams.serializer(), Empty.serializer())

    /** Prices every model in the ledger (and the one in use) from models.dev; hand-typed prices stay. */
    val SYNC_PRICES = RequestMethod("usage/syncPrices", Empty.serializer(), PriceSyncResult.serializer())
}

const val DEFAULT_USD_TO_CNY = 7.2
private const val TOKENS_PER_MILLION = 1_000_000.0

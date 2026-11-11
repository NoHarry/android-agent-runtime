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

/**
 * A model's price per million tokens, in [currency] (`USD` or `CNY`). A missing cache price bills those tokens at the
 * input price.
 */
@Serializable
data class ModelPrice(
    val input: Double,
    val output: Double,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
    /**
     * Where it came from: the id of the model service whose own list it is (its adapter's data), or the models.dev
     * provider whose listing it is; blank when typed in.
     */
    val source: String = "",
    /** The model whose price it is, when this model had none of its own and is priced as that one. */
    val pricedAs: String? = null,
    /** Typed in Settings: a sync never overwrites it. */
    val manual: Boolean = false,
    /** The currency the prices are in. */
    val currency: String = USD,
    /** The day ([java.time.LocalDate] text) the service's list said so, when it came from one. */
    val asOf: String? = null,
    /**
     * The region of the service's list it is from (`cn`, `global`: the region of the endpoint a profile is on), for a
     * service with a list per region; null for one list everywhere.
     */
    val region: String? = null,
    /** The first day ([java.time.LocalDate] text) it applies, for a change the service announced; null for now. */
    val effectiveFrom: String? = null,
)

/** What [this] usage cost at [price], in the price's currency. */
fun ModelUsage.cost(price: ModelPrice): Double {
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

/** [amount] of [currency] in US dollars at this book's rate; null for a currency other than USD and CNY. */
fun PriceBook.usd(amount: Double, currency: String): Double? = when (currency.uppercase()) {
    USD -> amount
    CNY -> amount / usdToCny
    else -> null
}

/**
 * What [usage] of [model] cost in US dollars at this book's price for it; null when the model has no price, or one in
 * a currency the book does not convert.
 */
fun PriceBook.costUsd(model: String, usage: ModelUsage): Double? =
    prices[model]?.let { price -> usd(usage.cost(price), price.currency) }

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

/** How a price sync went: [matched] of [total] models priced, or why it failed. */
@Serializable
data class PriceSyncResult(val matched: Int = 0, val total: Int = 0, val failure: String? = null)

/** Usage and cost requests. */
object UsageRequests {
    val READ = RequestMethod("usage/read", Empty.serializer(), UsageData.serializer())
    val SET_PRICE = RequestMethod("usage/setPrice", ModelPriceParams.serializer(), Empty.serializer())
    val SET_RATE = RequestMethod("usage/setRate", RateParams.serializer(), Empty.serializer())

    /**
     * Prices every model in the ledger (and the one in use): from its service's own list where a model service's adapter
     * has one, else from models.dev; hand-typed prices stay.
     */
    val SYNC_PRICES = RequestMethod("usage/syncPrices", Empty.serializer(), PriceSyncResult.serializer())
}

const val DEFAULT_USD_TO_CNY = 7.2

/** [ModelPrice.currency] of prices in US dollars. */
const val USD = "USD"

/** [ModelPrice.currency] of prices in Chinese yuan. */
const val CNY = "CNY"

/** The currencies a price may be in, which the book converts ([PriceBook.usd]). */
val PRICE_CURRENCIES: Set<String> = setOf(USD, CNY)
private const val TOKENS_PER_MILLION = 1_000_000.0

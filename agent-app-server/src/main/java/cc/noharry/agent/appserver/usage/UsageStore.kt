package cc.noharry.agent.appserver.usage
import android.content.Context
import cc.noharry.agent.appserver.model.modelPrice
import cc.noharry.agent.appserver.protocol.DEFAULT_USD_TO_CNY
import cc.noharry.agent.appserver.protocol.USD
import cc.noharry.agent.appserver.protocol.ModelPrice
import cc.noharry.agent.appserver.protocol.ModelUsage
import cc.noharry.agent.appserver.protocol.PriceBook
import cc.noharry.agent.appserver.protocol.UsageDays
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Usage and cost: every model call's tokens, kept per day and model with where each model last ran (its profile's base
 * URL, which picks a service's regional prices), and the price book. The book keeps what was typed in and what a sync
 * found at models.dev; the host's and the services' prices are not kept but taken each time the book is read
 * ([withKnownPrices]), so a change to them applies at once. Stored under `files/model-usage/`, which Clear runtime data
 * does not touch; there is no way to clear it from the UI. All file work runs on one background thread, in order.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
object UsageStore {
    private val io = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "model-usage") }
    private val mutableUsage = MutableStateFlow<UsageDays>(emptyMap())
    private val mutablePrices = MutableStateFlow(PriceBook())
    val usage: StateFlow<UsageDays> = mutableUsage
    val prices: StateFlow<PriceBook> = mutablePrices

    @Volatile private var dir: File? = null
    private var loaded = false
    private var savePending = false
    private var ranAt: Map<String, String> = emptyMap()

    fun init(context: Context) {
        dir = File(context.applicationContext.filesDir, USAGE_DIR)
        io.execute(::ensureLoaded)
    }

    /** The ledger and prices once every change asked for before has been made. */
    suspend fun read(): Pair<UsageDays, PriceBook> = readWithPlaces().let { it.first to it.second }

    /** The ledger, the kept prices and the base URL each model last ran at, once every change asked for before has been made. */
    internal suspend fun readWithPlaces(): Triple<UsageDays, PriceBook, Map<String, String>> = suspendCoroutine { done ->
        io.execute {
            ensureLoaded()
            done.resume(Triple(mutableUsage.value, mutablePrices.value, ranAt))
        }
    }

    /** Adds one call's usage to today's row for [model], which ran on a profile at [baseUrl]. Any thread. */
    fun record(model: String, usage: ModelUsage, baseUrl: String? = null) {
        val name = model.ifBlank { "unknown" }
        val day = LocalDate.now().toString()
        io.execute {
            ensureLoaded()
            val days = mutableUsage.value.toMutableMap()
            val models = days[day].orEmpty().toMutableMap()
            models[name] = (models[name] ?: ModelUsage()) + usage
            days[day] = models
            if (!baseUrl.isNullOrBlank()) ranAt = ranAt + (name to baseUrl.trim())
            mutableUsage.value = days
            scheduleSave()
        }
    }

    /** Sets or, with null, removes the hand-typed price for [model]; removing lets the next sync fill it. */
    internal fun setManualPrice(model: String, price: ModelPrice?) = io.execute {
        ensureLoaded()
        val prices = mutablePrices.value.prices.toMutableMap()
        if (price == null) prices.remove(model) else prices[model] = price.copy(source = "", pricedAs = null, manual = true, region = null, effectiveFrom = null)
        mutablePrices.value = mutablePrices.value.copy(prices = prices)
        savePrices()
    }

    internal fun setUsdToCny(rate: Double) = io.execute {
        ensureLoaded()
        mutablePrices.value = mutablePrices.value.copy(usdToCny = rate)
        savePrices()
    }

    /**
     * Prices every model seen in the ledger plus [extraModels]: one the host or its service prices ([modelPrice]) needs
     * nothing kept, and a price models.dev gave it before is dropped; the rest from models.dev, fetched only for those;
     * hand-typed prices stay. Calls [done] on the store thread with how many were matched, or the failure.
     */
    internal fun syncPrices(extraModels: Collection<String>, done: (Result<Pair<Int, Int>>) -> Unit) = io.execute {
        ensureLoaded()
        val result = runCatching {
            val models = (mutableUsage.value.values.flatMap { it.keys } + extraModels).filter { it != "unknown" }.toSet()
            val (book, matched) = mutablePrices.value.synced(models, { model -> modelPrice(model, ranAt[model]) }) { fetchModelsDev() }
            mutablePrices.value = book.copy(syncedAtEpochMillis = System.currentTimeMillis())
            savePrices()
            matched to models.size
        }
        done(result)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val root = dir ?: return
        runCatching { File(root, LEDGER_FILE).takeIf(File::isFile)?.readText() }.getOrNull()?.let { text ->
            runCatching { parseUsage(text) }.getOrNull()?.let { mutableUsage.value = it }
            ranAt = runCatching { parseRanAt(text) }.getOrNull().orEmpty()
        }
        runCatching { File(root, PRICES_FILE).takeIf(File::isFile)?.readText()?.let(::parsePriceBook) }.getOrNull()?.let { mutablePrices.value = it }
    }

    /** Coalesces a burst of records into one write a second later. */
    private fun scheduleSave() {
        if (savePending) return
        savePending = true
        io.schedule({
            savePending = false
            write(LEDGER_FILE, usageJson(mutableUsage.value, ranAt))
        }, SAVE_DELAY_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun savePrices() = write(PRICES_FILE, priceBookJson(mutablePrices.value))

    private fun write(name: String, text: String) {
        val root = dir ?: return
        runCatching {
            root.mkdirs()
            val temp = File(root, "$name.tmp")
            temp.writeText(text)
            if (!temp.renameTo(File(root, name))) throw IOException("rename failed")
        }
    }

    private fun fetchModelsDev(): JsonObject {
        val request = Request.Builder().url(MODELS_DEV_URL).build()
        return MODELS_DEV_HTTP.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("models.dev HTTP ${response.code}")
            Json.parseToJsonElement(response.body?.string() ?: throw IOException("models.dev returned nothing")).jsonObject
        }
    }
}

/**
 * [this] book after a sync of [models], and how many have a price: a hand-typed one stays; one [known] prices (the host
 * or its service, taken on each read and never kept) loses any price models.dev gave it before; the rest are priced
 * from the models.dev catalog [fetch] gives, asked only when one is left.
 */
internal fun PriceBook.synced(models: Set<String>, known: (String) -> ModelPrice?, fetch: () -> JsonObject): Pair<PriceBook, Int> {
    val kept = prices.toMutableMap()
    val open = models.filter { kept[it]?.manual != true }
    val live = open.filter { known(it) != null }.toSet()
    kept -= live
    val rest = open.filterNot { it in live }
    val catalog = if (rest.isEmpty()) null else fetch()
    val synced = rest.mapNotNull { model -> catalog?.let { matchModelsDevPrice(it, model) }?.let { model to it } }.toMap()
    kept += synced
    return copy(prices = kept) to live.size + synced.size
}

internal fun usageJson(days: UsageDays, ranAt: Map<String, String> = emptyMap()): String = buildJsonObject {
    put("version", 1)
    if (ranAt.isNotEmpty()) putJsonObject("ranAt") { ranAt.forEach { (model, baseUrl) -> put(model, baseUrl) } }
    putJsonObject("days") {
        days.forEach { (day, models) ->
            putJsonObject(day) {
                models.forEach { (model, usage) ->
                    putJsonObject(model) {
                        put("requests", usage.requests)
                        put("input", usage.inputTokens)
                        put("cacheRead", usage.cacheReadTokens)
                        put("cacheWrite", usage.cacheWriteTokens)
                        put("output", usage.outputTokens)
                    }
                }
            }
        }
    }
}.toString()

internal fun parseUsage(text: String): UsageDays {
    val days = Json.parseToJsonElement(text).jsonObject["days"]?.jsonObject ?: return emptyMap()
    return days.mapValues { (_, models) ->
        models.jsonObject.mapValues { (_, value) ->
            val usage = value.jsonObject
            fun long(name: String) = usage[name]?.jsonPrimitive?.longOrNull ?: 0L
            ModelUsage(long("requests"), long("input"), long("cacheRead"), long("cacheWrite"), long("output"))
        }
    }
}

/** The base URL each model last ran at, from the ledger's text. */
internal fun parseRanAt(text: String): Map<String, String> =
    Json.parseToJsonElement(text).jsonObject["ranAt"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()

/** The book as kept: what was typed in and what models.dev gave ([UsageStore.syncPrices] keeps nothing else). */
internal fun priceBookJson(book: PriceBook): String = buildJsonObject {
    put("version", PRICE_BOOK_VERSION)
    put("usdToCny", book.usdToCny)
    book.syncedAtEpochMillis?.let { put("syncedAt", it) }
    putJsonObject("prices") {
        book.prices.forEach { (model, price) ->
            putJsonObject(model) {
                put("input", price.input)
                put("output", price.output)
                price.cacheRead?.let { put("cacheRead", it) }
                price.cacheWrite?.let { put("cacheWrite", it) }
                put("source", price.source)
                price.pricedAs?.let { put("pricedAs", it) }
                put("manual", price.manual)
                put("currency", price.currency)
                price.asOf?.let { put("asOf", it) }
            }
        }
    }
}.toString()

/**
 * A kept book. Whether a price was typed in is what its `manual` says; only a book from before that field (none) takes
 * one with no source, or the old 「手填」, as typed. A book of version 1 also kept the host's and the services' prices,
 * not typed and with a blank or service source: one with a blank source is dropped (models.dev always names its
 * provider), and the others are re-read from the host and the services on each read, which win over them.
 */
internal fun parsePriceBook(text: String): PriceBook {
    val root = Json.parseToJsonElement(text).jsonObject
    val version = root["version"]?.jsonPrimitive?.longOrNull ?: 1L
    val prices = root["prices"]?.jsonObject?.mapNotNull { (model, value) ->
        val entry = value.jsonObject
        fun double(name: String) = entry[name]?.jsonPrimitive?.doubleOrNull
        val input = double("input") ?: return@mapNotNull null
        val output = double("output") ?: return@mapNotNull null
        val source = storedSource(entry["source"]?.jsonPrimitive?.content)
        val manual = entry["manual"]?.jsonPrimitive?.booleanOrNull ?: (entry["source"] == null || source.first.isEmpty())
        if (!manual && source.first.isEmpty() && version < PRICE_BOOK_VERSION) return@mapNotNull null
        model to ModelPrice(
            input = input,
            output = output,
            cacheRead = double("cacheRead"),
            cacheWrite = double("cacheWrite"),
            source = source.first,
            pricedAs = entry["pricedAs"]?.jsonPrimitive?.content ?: source.second,
            manual = manual,
            currency = entry["currency"]?.jsonPrimitive?.content ?: USD,
            asOf = entry["asOf"]?.jsonPrimitive?.content,
        )
    }?.toMap().orEmpty()
    return PriceBook(
        prices = prices,
        usdToCny = root["usdToCny"]?.jsonPrimitive?.doubleOrNull ?: DEFAULT_USD_TO_CNY,
        syncedAtEpochMillis = root["syncedAt"]?.jsonPrimitive?.longOrNull,
    )
}

/**
 * A stored source as (provider, priced as): a book written before sources were data kept 「models.dev · <provider>」,
 * 「…（按 <model>）」 when priced at another model, or 「手填」 for one typed in (blank provider).
 */
private fun storedSource(stored: String?): Pair<String, String?> {
    val text = stored.orEmpty()
    val pricedAs = LEGACY_PRICED_AS.matchEntire(text)
    val source = (pricedAs?.groupValues?.get(1) ?: text).removePrefix(LEGACY_SOURCE_PREFIX).takeUnless { it == LEGACY_MANUAL_SOURCE }.orEmpty()
    return source to pricedAs?.groupValues?.get(2)
}

private val LEGACY_PRICED_AS = Regex("(.*)（按 (.+)）")
private const val LEGACY_SOURCE_PREFIX = "models.dev · "
private const val LEGACY_MANUAL_SOURCE = "手填"

/** Not one of the Clear runtime data targets, and nothing in the UI clears it. */
internal const val USAGE_DIR = "model-usage"
private const val LEDGER_FILE = "usage-ledger.json"
private const val PRICES_FILE = "model-prices.json"
private const val SAVE_DELAY_MILLIS = 1_000L

/** 2: the book keeps only typed and models.dev prices. */
private const val PRICE_BOOK_VERSION = 2L
private const val MODELS_DEV_URL = "https://models.dev/api.json"
private val MODELS_DEV_HTTP = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()

package cc.noharry.agent.appserver.server

import android.content.Context
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.PRICE_CURRENCIES
import cc.noharry.agent.appserver.protocol.PriceSyncResult
import cc.noharry.agent.appserver.protocol.UsageData
import cc.noharry.agent.appserver.protocol.UsageRequests
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcErrorCodes
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.usage.UsageStore
import cc.noharry.agent.appserver.usage.withKnownPrices
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Usage and cost: the ledger and prices (each model's as the server takes it: typed in, else the host's or its
 * service's, else synced), hand-set prices and the rate, and a sync.
 */
internal class UsageRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(UsageRequests.READ) {
            val (days, prices, ranAt) = UsageStore.readWithPlaces()
            UsageData(days, prices.withKnownPrices(days.values.flatMap { it.keys }, ranAt))
        }
        on(UsageRequests.SET_PRICE) { params ->
            params.price?.let { price ->
                if (price.currency !in PRICE_CURRENCIES) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "A price's currency is USD or CNY, not ${price.currency}.")
            }
            UsageStore.setManualPrice(params.model, params.price)
            Empty
        }
        on(UsageRequests.SET_RATE) {
            UsageStore.setUsdToCny(it.usdToCny)
            Empty
        }
        on(UsageRequests.SYNC_PRICES) {
            // The model in use is priced too, even before its first call is in the ledger.
            val active = withContext(Dispatchers.IO) {
                runCatching { ProviderConfig.load(app).let { it.modelRef(it.activeProfile).modelId } }.getOrNull()
            }
            val result = suspendCoroutine { done -> UsageStore.syncPrices(listOfNotNull(active)) { done.resume(it) } }
            result.fold(
                { (matched, total) -> PriceSyncResult(matched, total) },
                { PriceSyncResult(failure = it.message.orEmpty()) },
            )
        }
    }
}

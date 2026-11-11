package cc.noharry.agent.appserver.server

import android.content.Context
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.PriceSyncResult
import cc.noharry.agent.appserver.protocol.UsageData
import cc.noharry.agent.appserver.protocol.UsageRequests
import cc.noharry.agent.appserver.usage.UsageStore
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Usage and cost: the ledger and prices, hand-set prices and the rate, and a models.dev sync. */
internal class UsageRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(UsageRequests.READ) { UsageStore.read().let { (days, prices) -> UsageData(days, prices) } }
        on(UsageRequests.SET_PRICE) {
            UsageStore.setManualPrice(it.model, it.price)
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

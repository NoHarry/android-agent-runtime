package cc.noharry.agent.appserver.models

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.modelFacts
import cc.noharry.agent.appserver.model.modelPrice
import cc.noharry.agent.appserver.models.deepseek.DeepSeekAdapter
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.ModelFacts
import cc.noharry.agent.appserver.protocol.ModelPrice
import cc.noharry.agent.appserver.protocol.ModelPriceParams
import cc.noharry.agent.appserver.protocol.ModelSettingsParams
import cc.noharry.agent.appserver.protocol.ModelSettingsRequests
import cc.noharry.agent.appserver.protocol.ModelSettingsSaveParams
import cc.noharry.agent.appserver.protocol.ModelSettingsView
import cc.noharry.agent.appserver.protocol.ModelUsage
import cc.noharry.agent.appserver.protocol.PriceBook
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.protocol.UsageRequests
import cc.noharry.agent.appserver.protocol.costUsd
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.appserver.server.AppServerConfig
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.server.PluginAdditions
import cc.noharry.agent.appserver.server.RouteTable
import cc.noharry.agent.appserver.server.coreModules
import cc.noharry.agent.appserver.usage.UsageStore
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Who wins: the person's own settings (a model's window and picture switch, a price typed in) over the host's
 * (`AppServerConfig.modelFacts`, `modelPrices`), and the host's over the service's data; and DeepSeek's own pricing of
 * the models it does not list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class OverridesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val profile = ProviderEditState("deepseek-flash", baseUrl = "https://api.deepseek.com", profileId = "overrides")

    @Before
    fun services() = AppServerTesting.running(PluginAdditions(providerAdapters = ModelsPlugin.adapters()))

    @After
    fun reset() = AppServerTesting.reset()

    @Test
    fun thePersonsSettingsWinOverTheHostsFactsWhichWinOverTheServices() = runBlocking {
        val service = ServiceCatalog.service("deepseek").model("deepseek-flash")!!.facts
        assertEquals(ModelSettingsView("deepseek-flash", null, service.contextWindow!!, service.imageInput!!, service), settings())

        AppServerTesting.install(hostConfig())
        val host = settings()
        assertEquals(500_000L to false, host.defaultWindow to host.imageInput)
        assertEquals(service.maxOutputTokens, host.facts.maxOutputTokens)

        call(ModelSettingsRequests.MODELS_SAVE, ModelSettingsSaveParams(profile, listOf(ModelSettingsView("deepseek-flash", windowOverride = 64_000, imageInput = true))))
        val person = settings()
        assertEquals(64_000L to true, person.windowOverride to person.imageInput)
    }

    @Test
    fun aPriceTypedInWinsOverTheHostsWhichWinsOverTheServices() = runBlocking {
        UsageStore.init(context)
        UsageStore.record("deepseek-flash", ModelUsage(requests = 1, inputTokens = 1_000_000, outputTokens = 1_000_000))
        UsageStore.record("deepseek-v4-pro", ModelUsage(requests = 1, inputTokens = 1_000_000))
        val listed = ServiceCatalog.service("deepseek").model("deepseek-flash")!!.price!!
        assertEquals(listed, prices().prices["deepseek-flash"])

        AppServerTesting.install(hostConfig())
        assertEquals(ModelPrice(input = 0.2, output = 0.8, currency = "USD"), prices().prices.getValue("deepseek-flash"))

        call(UsageRequests.SET_PRICE, ModelPriceParams("deepseek-flash", ModelPrice(0.5, 0.5)))
        val book = prices()
        assertEquals(true, book.prices.getValue("deepseek-flash").manual)
        // A service's price in yuan is shown in dollars at the book's rate.
        val pro = book.prices.getValue("deepseek-v4-pro")
        assertEquals("CNY", pro.currency)
        assertEquals(pro.input / book.usdToCny, book.costUsd("deepseek-v4-pro", ModelUsage(requests = 1, inputTokens = 1_000_000))!!, 1e-9)
    }

    @Test
    fun deepSeekPricesTheModelsItDoesNotListAsItsOwnOfTheirKindAndNoOtherServicesModel() {
        val deepSeek = DeepSeekAdapter.services.single()
        fun price(model: String) = DeepSeekAdapter.price(deepSeek, model, deepSeek.defaultRegion, LocalDate.of(2026, 11, 11))
        val flash = price("deepseek-flash")!!
        assertEquals(flash.copy(pricedAs = "deepseek-flash"), price("deepseek-v4.2-flash"))
        assertEquals("deepseek-v4-pro", price("deepseek-v4.2-pro")?.pricedAs)
        assertNull(price("deepseek-chat"))
        assertNull(price("other-flash"))
        // A relay's name for it is DeepSeek's; another service's listing of a DeepSeek model is that service's.
        assertEquals(flash.copy(pricedAs = "deepseek-flash"), modelPrice("relay/deepseek-v4.1-flash"))
        // By name alone (a relay's), from the list of the service's first region: SiliconFlow's China site.
        val hosted = ServiceCatalog.service("siliconflow").model("deepseek-ai/DeepSeek-V4-Flash")!!.price
        assertEquals(hosted, modelPrice("deepseek-ai/DeepSeek-V4-Flash"))
        assertEquals("CNY", hosted?.currency)
        // The facts of a model a relay names come from the service that lists it.
        assertEquals(1_048_576L, modelFacts("https://relay.example.com/v1", "relay/deepseek-flash").contextWindow)
    }

    /** The host guide's overrides, as it quotes them. */
    private fun hostConfig(): AppServerConfig {
        // guide:start host-overrides
        val config = AppServerConfig(
            name = "notes",
            // A gateway that serves this model with a smaller window and no pictures, at the price the host pays.
            modelFacts = mapOf("deepseek-flash" to ModelFacts(contextWindow = 500_000, imageInput = false)),
            modelPrices = mapOf("deepseek-flash" to ModelPrice(input = 0.2, output = 0.8, currency = "USD")),
        )
        // guide:end host-overrides
        return config
    }

    private suspend fun settings(): ModelSettingsView =
        call(ModelSettingsRequests.MODELS_READ, ModelSettingsParams(profile, listOf("deepseek-flash"))).models.single()

    private suspend fun prices(): PriceBook = call(UsageRequests.READ, Empty).prices

    private suspend fun <P, R> call(method: RequestMethod<P, R>, params: P): R {
        var answer: (suspend (P) -> R)? = null
        val table = object : RouteTable {
            @Suppress("UNCHECKED_CAST")
            override fun <A, B> on(route: RequestMethod<A, B>, block: suspend (A) -> B) {
                if (route.name == method.name) answer = block as suspend (P) -> R
            }
        }
        coreModules(context).forEach { module -> with(module) { table.install() } }
        return checkNotNull(answer)(params)
    }
}

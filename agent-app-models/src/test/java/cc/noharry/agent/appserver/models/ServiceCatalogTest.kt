package cc.noharry.agent.appserver.models

import java.net.URI
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The data file holds what each service's own pages said on the day it names: every value has a source on the service's
 * own site, every service is reached over HTTPS on a host it is recognized by, and every number is one a service could
 * mean.
 */
class ServiceCatalogTest {
    private val data = ServiceCatalog.load()
    private val services = ServiceCatalog.read(data)

    @Test
    fun theThirteenServicesAreThereEachOnceInOrder() {
        assertEquals(EXPECTED, services.map { it.id })
        assertEquals(1, data.format)
    }

    @Test
    fun everySourceIsOnTheServicesOwnSiteAndEveryServiceSaysWhen() {
        data.services.forEach { service ->
            val domains = OFFICIAL.getValue(service.id)
            val sources = listOf(service.source, service.pricesSource) + service.models.map { it.source } + service.errors.map { it.source } +
                listOfNotNull(service.retry?.source) + Regex("""https://\S+""").findAll(service.keyHint).map { it.value.trimEnd(')', ';', ',') } + service.keyPages.values +
                service.priceLists.map { it.source } + service.models.flatMap { model -> model.prices.map { it.source } + model.requestOutputSource } +
                Regex("""https://[^\s)]+""").findAll(service.defaultModelReason).map { it.value.trimEnd('.', ',', ';') }
            sources.filter { it.isNotEmpty() }.forEach { source ->
                val host = URI(source).host.orEmpty()
                assertTrue("${service.id}: $source is not on ${domains.joinToString()}", source.startsWith("https://") && domains.any { host == it || host.endsWith(".$it") })
            }
            assertEquals(service.id, "2026-11-11", LocalDate.parse(service.asOf).toString())
            assertTrue(service.id, service.currency in setOf("USD", "CNY"))
            service.priceLists.forEach { list ->
                assertTrue(service.id, list.currency in setOf("USD", "CNY") && list.region != service.pricesRegion)
                assertEquals(service.id, "2026-11-11", LocalDate.parse(list.asOf).toString())
            }
        }
    }

    @Test
    fun everyEndpointIsHttpsAndOnAHostTheServiceIsRecognizedBy() {
        services.forEach { service ->
            service.endpoints.forEach { endpoint ->
                assertTrue(endpoint.baseUrl, endpoint.baseUrl.startsWith("https://") && !endpoint.baseUrl.endsWith("/"))
                assertTrue(endpoint.baseUrl, service.serves(endpoint.baseUrl))
            }
            service.modelsUrl?.let { assertTrue(it, it.startsWith("https://") && service.serves(it)) }
            assertTrue(service.id, service.keyHint.contains("https://"))
        }
    }

    @Test
    fun everyModelsFactsAndPricesAreOnesAServiceCouldMean() {
        services.forEach { service ->
            assertEquals(service.id, service.models.size, service.models.map { it.id }.toSet().size)
            service.models.forEach { model ->
                val facts = model.facts
                val window = facts.contextWindow
                val output = facts.maxOutputTokens
                assertTrue(model.id, window == null || window in 8_192L..2_000_000L)
                assertTrue(model.id, output == null || output in 1_024L..(window ?: Long.MAX_VALUE))
                assertTrue(model.id, facts.reasoningEfforts.all { it.matches(Regex("[a-z]+")) })
                // A request's output is asked for below the documented ceiling, never the window.
                facts.requestOutputTokens?.let { asked -> assertTrue(model.id, asked <= (output ?: 0) && asked < (window ?: 0)) }
                (listOfNotNull(model.price) + model.prices).forEach { price ->
                    assertTrue(model.id, price.input > 0 && price.output > 0 && (price.cacheRead ?: 0.0) <= price.input)
                    assertEquals(model.id, service.id, price.source)
                    assertEquals(model.id, "2026-11-11", price.asOf)
                    assertTrue(model.id, price.region == null || service.endpoints.any { it.region == price.region })
                    price.effectiveFrom?.let { assertTrue(model.id, LocalDate.parse(it).isAfter(LocalDate.parse(price.asOf))) }
                }
            }
            service.errors.forEach { rule -> assertTrue(service.id, rule.status != null || rule.pattern != null) }
        }
    }

    /**
     * A screen words its own sentence around a service's key page: every region a service is reached in has one (its own,
     * else the service's one for every region, under a blank region), each a page the free-text hint names too, and a
     * service whose regions are separate sites (their keys made, and only working, on their own site) has one per region.
     */
    @Test
    fun everyRegionOfAServiceHasTheKeyPageItsHintNames() {
        services.forEach { service ->
            val regions = service.endpoints.map { it.region }.toSet()
            assertTrue(service.id, service.keyPages.isNotEmpty())
            service.keyPages.forEach { (region, page) ->
                assertTrue("${service.id} $region", region.isEmpty() || region in regions)
                assertTrue("${service.id} $page", page.startsWith("https://") && page in service.keyHint)
            }
            regions.forEach { region -> assertTrue("${service.id} $region", (service.keyPages[region] ?: service.keyPages[""]) != null) }
        }
        val separateSites = services.filter { service -> service.keyPages.values.distinct().size > 1 }.map { it.id }
        assertEquals(listOf("moonshot", "zhipu", "dashscope", "minimax", "siliconflow"), separateSites)
        assertEquals(mapOf("" to "https://console.x.ai/team/default/api-keys"), ServiceCatalog.service("xai").keyPages)
    }

    @Test
    fun aProfileIsPricedFromItsRegionsListAndAnAnnouncedChangeFromItsDay() {
        val day = LocalDate.of(2026, 11, 11)
        val kimi = ServiceCatalog.service("moonshot").model("kimi-k3.1-pro")!!
        assertEquals("CNY", kimi.priceIn("cn", day)?.currency)
        assertEquals(13.0, kimi.priceIn("cn", day)!!.input, 0.0)
        assertEquals("USD", kimi.priceIn("global", day)?.currency)
        assertEquals(2.0, kimi.priceIn("global", day)!!.input, 0.0)
        // A region the data has no list for gets none, not the China list.
        val qwen = ServiceCatalog.service("dashscope")
        assertNull(qwen.model("qwen3.8-max")!!.priceIn(qwen.regionOf("https://ws.eu-central-1.maas.aliyuncs.com/compatible-mode/v1"), day))
        assertEquals("global", qwen.regionOf("https://ws.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1"))
        val xai = ServiceCatalog.service("xai")
        assertNull(xai.model("grok-4.3")!!.priceIn(xai.regionOf("https://us.api.x.ai/v1"), day))
        // One list everywhere: DeepSeek's.
        assertEquals("CNY", ServiceCatalog.service("deepseek").model("deepseek-flash")!!.priceIn("", day)?.currency)
        // Gemini 4 doubles on 2026-12-01, Gemini 3.8 Flash on 2027-01-01.
        val gemini = ServiceCatalog.service("gemini")
        fun input(model: String, on: LocalDate) = gemini.model(model)!!.priceIn("", on)!!.input
        assertEquals(2.0, input("gemini-4-carbon", LocalDate.of(2026, 11, 30)), 0.0)
        assertEquals(4.0, input("gemini-4-carbon", LocalDate.of(2026, 12, 1)), 0.0)
        assertEquals(0.75, input("gemini-3.8-flash", LocalDate.of(2026, 12, 31)), 0.0)
        assertEquals(1.5, input("gemini-3.8-flash", LocalDate.of(2027, 1, 1)), 0.0)
        assertEquals(0.3, input("gemini-3.5-flash-lite", LocalDate.of(2027, 1, 1)), 0.0)
    }

    private companion object {
        val EXPECTED = listOf(
            "deepseek", "openai", "anthropic", "gemini", "xai", "openrouter", "groq", "moonshot", "zhipu", "dashscope",
            "volcengine", "minimax", "siliconflow",
        )

        /** Each service's own sites, where its facts were read and its keys are made. */
        val OFFICIAL = mapOf(
            "deepseek" to listOf("deepseek.com"),
            "openai" to listOf("openai.com"),
            "anthropic" to listOf("claude.com", "anthropic.com"),
            "gemini" to listOf("google.com", "google.dev"),
            "xai" to listOf("x.ai"),
            "openrouter" to listOf("openrouter.ai"),
            "groq" to listOf("groq.com"),
            "moonshot" to listOf("kimi.com", "kimi.ai", "moonshot.cn", "moonshot.ai"),
            "zhipu" to listOf("bigmodel.cn", "z.ai"),
            "dashscope" to listOf("aliyun.com", "alibabacloud.com", "aliyuncs.com"),
            "volcengine" to listOf("volcengine.com", "volces.com"),
            "minimax" to listOf("minimax.io", "minimax.cn"),
            "siliconflow" to listOf("siliconflow.cn", "siliconflow.com"),
        )
    }
}

package cc.noharry.agent.appserver.models

import java.net.URI
import java.time.LocalDate
import org.junit.Assert.assertEquals
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
                listOfNotNull(service.retry?.source) + Regex("""https://\S+""").findAll(service.keyHint).map { it.value.trimEnd(')', ';', ',') }
            sources.filter { it.isNotEmpty() }.forEach { source ->
                val host = URI(source).host.orEmpty()
                assertTrue("${service.id}: $source is not on ${domains.joinToString()}", source.startsWith("https://") && domains.any { host == it || host.endsWith(".$it") })
            }
            assertEquals(service.id, "2026-11-11", LocalDate.parse(service.asOf).toString())
            assertTrue(service.id, service.currency in setOf("USD", "CNY"))
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
                val price = model.price ?: return@forEach
                assertTrue(model.id, price.input > 0 && price.output > 0 && (price.cacheRead ?: 0.0) <= price.input)
                assertEquals(model.id, service.id, price.source)
                assertEquals(model.id, "2026-11-11", price.asOf)
            }
            service.errors.forEach { rule -> assertTrue(service.id, rule.status != null || rule.pattern != null) }
        }
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

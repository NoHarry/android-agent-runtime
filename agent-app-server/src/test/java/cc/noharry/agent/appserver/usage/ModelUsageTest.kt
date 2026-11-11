package cc.noharry.agent.appserver.usage
import cc.noharry.agent.appserver.protocol.ModelPrice
import cc.noharry.agent.appserver.protocol.ModelUsage
import cc.noharry.agent.appserver.protocol.PriceBook
import cc.noharry.agent.appserver.runtime.runtimeDataDirs
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelUsageTest {
    private val catalog = Json.parseToJsonElement(
        """
        {
          "deepseek": {"models": {
            "deepseek-v4-flash": {"id": "deepseek-v4-flash", "cost": {"input": 0.15, "output": 0.6, "cache_read": 0.003}},
            "deepseek-flash": {"id": "deepseek-flash", "cost": {"input": 0.14, "output": 0.56, "cache_read": 0.002}},
            "deepseek-v4-pro": {"id": "deepseek-v4-pro", "cost": {"input": 0.66, "output": 1.98, "cache_read": 0.022}}
          }},
          "alibaba-token-plan": {"models": {"deepseek-v4.1-flash": {"cost": {"input": 0, "output": 0}}}},
          "resale-a": {"models": {"deepseek-v4-1-flash": {"cost": {"input": 0.3, "output": 1.2}}}},
          "resale-b": {"models": {"deepseek/deepseek-v4-flash": {"cost": {"input": 0.9, "output": 2.0}}}},
          "resale-c": {"models": {"other/deepseek-v4-1-flash": {"cost": {"input": 0.5, "output": 1.5}}}},
          "resale-d": {"models": {"deepseek-v4-1-flash": {"cost": {"input": 0.1, "output": 0.4}}}},
          "alibaba": {"models": {"qwen3-max": {"cost": {"input": 1.2, "output": 6.0}}}},
          "resale-e": {"models": {"qwen3-max": {"cost": {"input": 2.0, "output": 9.0}}, "glm-x": {"cost": {"input": 0.1, "output": 0.2}}},
                       "resale-f": {"cost": {}}},
          "resale-g": {"models": {"glm-x": {"cost": {"input": 0.3, "output": 0.6}}}},
          "resale-h": {"models": {"glm-x": {"cost": {"input": 0.5, "output": 1.0}}}}
        }
        """,
    ).jsonObject

    @Test
    fun aModelIsPricedFromEveryListingOfItsIdAtTheirMedianFreeOnesLeftOut() {
        // Three listings of qwen3-max: 1.2 and 2.0; the median of two is the lower.
        assertEquals("alibaba", matchModelsDevPrice(catalog, "proxy/qwen3-max")!!.source)
        // glm-x at three resellers, 0.1, 0.3 and 0.5.
        assertEquals(0.3, matchModelsDevPrice(catalog, "glm-x")!!.input, 0.0)
        // deepseek-v4.1-flash: the free plan's entry is left out; ids equal up to dots and dashes count.
        assertEquals(0.3, matchModelsDevPrice(catalog, "deepseek-v4.1-flash")!!.input, 0.0)
        assertNull(matchModelsDevPrice(catalog, "jev-1.13.0"))
    }

    @Test
    fun aPriceTypedInStaysAndTheHostsPriceWinsOverASyncedOne() {
        val synced = ModelPrice(1.0, 2.0, source = "resale-a")
        val typed = ModelPrice(0.5, 0.5, manual = true)
        cc.noharry.agent.appserver.runtime.AppHost.install(modelPrices = mapOf("m" to ModelPrice(3.0, 4.0, source = "host"), "t" to ModelPrice(9.0, 9.0)))
        try {
            val book = PriceBook(mapOf("m" to synced, "t" to typed, "s" to synced)).withKnownPrices(listOf("m", "t", "s", "new"))
            assertEquals(3.0, book.prices.getValue("m").input, 0.0)
            assertEquals(typed, book.prices["t"])
            assertEquals(synced, book.prices["s"])
            assertNull(book.prices["new"])
        } finally {
            cc.noharry.agent.appserver.runtime.AppHost.install(modelPrices = emptyMap())
        }
    }

    @Test
    fun theLedgerAndPriceBookRoundTrip() {
        val days = mapOf("2026-10-11" to mapOf("m" to ModelUsage(3, 100, 40, 5, 20)))
        val book = PriceBook(
            mapOf(
                "m" to ModelPrice(0.1, 0.2, 0.01, null, manual = true),
                "f" to ModelPrice(1.0, 2.0, source = "example", pricedAs = "example-flash", currency = "CNY", asOf = "2026-11-11"),
            ),
            7.1,
            42L,
        )

        assertEquals(days, parseUsage(usageJson(days)))
        assertEquals(book, parsePriceBook(priceBookJson(book)))
    }

    @Test
    fun aPriceBookWrittenBeforeSourcesWereDataIsReadAsData() {
        val old = """{"version":1,"usdToCny":7.2,"prices":{""" +
            """"a":{"input":1.0,"output":2.0,"source":"手填","manual":true},""" +
            """"b":{"input":1.0,"output":2.0,"source":"models.dev · deepseek（按 deepseek-flash）","manual":false}}}"""
        val prices = parsePriceBook(old).prices

        assertEquals(ModelPrice(1.0, 2.0, manual = true), prices["a"])
        assertEquals(ModelPrice(1.0, 2.0, source = "deepseek", pricedAs = "deepseek-flash"), prices["b"])
    }

    @Test
    fun clearingRuntimeDataKeepsTheUsage() {
        val targets = runtimeDataDirs(File("/files")).map(File::getName)

        assertFalse(USAGE_DIR in targets)
        assertTrue(targets.isNotEmpty())
    }

}

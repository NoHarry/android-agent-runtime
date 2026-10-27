@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.core.tools.NetworkToolPolicyDecision
import cc.noharry.agent.transport.WebFetchRequest
import cc.noharry.agent.transport.WebFetchResponse
import cc.noharry.agent.transport.WebFetchTransport
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchToolTest {
    private val queries = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
    private val provider = object : WebSearchProvider {
        override val label = "search.example.com"

        override suspend fun search(query: String, count: Int): List<WebSearchResult> {
            queries += query to count
            return listOf(WebSearchResult("广州周末市集", "https://news.example.com/a", "海心沙美食周".repeat(200), "大洋网", "2026-10-17T16:00:00Z"))
        }
    }
    private val fetched = Collections.synchronizedList(mutableListOf<String>())
    private val fallback = object : WebFetchFallback {
        override val label = "fetch.example.com"

        override suspend fun fetch(url: String): WebFallbackPage {
            fetched += url
            return WebFallbackPage("备用", "# 备用正文")
        }
    }

    @Test
    fun theModelSearchesAndSeesTheResultsWithoutAConfirmation() = runBlocking {
        val model = GoldenScriptedModel(listOf(round(tool("web_search", """{"query":"广州 周末 市集","count":3}""")), round(finish("查到了"))))
        AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("web-search-").toFile())
            .modelClient(model)
            .webSearch(provider)
            .build()
            .run(goldenRequest())
            .toList()

        assertTrue(model.seenRequests.first().tools.any { it.name == "web_search" })
        val toolResult = model.seenRequests[1].messages.last { it.role == "tool" }.content
        assertEquals(toolResult, listOf("广州 周末 市集" to 3), queries.toList())
        val result = Json.parseToJsonElement(toolResult).jsonObject
        val first = result["results"]!!.jsonArray.single().jsonObject
        assertEquals("https://news.example.com/a", first["url"]!!.jsonPrimitive.content)
        // A long summary is cut to what a model needs, since it rides along every later round.
        assertEquals(800, first["summary"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun aFetchFallbackWithoutASearchProviderStillGetsARefusedPage() = runBlocking {
        // `.invalid` never resolves, so the device's own request fails without leaving the machine.
        val model = GoldenScriptedModel(listOf(round(tool("web_fetch", """{"url":"https://flights.invalid/schedule"}""")), round(finish("读到了"))))
        AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("web-fetch-fallback-").toFile())
            .modelClient(model)
            .networkConfirmationPolicy(NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS)
            .webFetchFallback(fallback)
            .build()
            .run(goldenRequest())
            .toList()

        // The provider's server-side search stays: no native web_search is offered.
        assertFalse(model.seenRequests.first().tools.any { it.name == "web_search" })
        val toolResult = Json.parseToJsonElement(model.seenRequests[1].messages.last { it.role == "tool" }.content).jsonObject
        assertEquals(listOf("https://flights.invalid/schedule"), fetched.toList())
        assertEquals("fetch.example.com", toolResult["via"]!!.jsonPrimitive.content)
    }

    @Test
    fun withoutAProviderThereIsNoWebSearch() = runBlocking {
        val model = GoldenScriptedModel(listOf(round(finish("好"))))
        AgentRuntimeBuilder.create().workspaceRoot(Files.createTempDirectory("web-search-").toFile()).modelClient(model).build().run(goldenRequest()).toList()

        assertFalse(model.seenRequests.first().tools.any { it.name == "web_search" })
    }

    @Test
    fun aRefusedPageIsFetchedByTheFallbackButAMissingOneIsNot() = runBlocking {
        val refused = NetworkToolBackend(transport = StatusTransport(403), confirmationPolicy = NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS, fetchFallback = fallback.asService())
        val viaService = Json.parseToJsonElement(refused.execute("web_fetch", """{"url":"https://blocked.example.com/page"}""")).jsonObject
        assertEquals("fetch.example.com", viaService["via"]!!.jsonPrimitive.content)
        assertEquals("# 备用正文", viaService["body"]!!.jsonPrimitive.content)

        // 携程 refuses the device with its own 432.
        val antiBot = NetworkToolBackend(transport = StatusTransport(432), confirmationPolicy = NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS, fetchFallback = fallback.asService())
        assertTrue(antiBot.execute("web_fetch", """{"url":"https://hotels.example.com/detail"}""").contains("\"via\""))

        val missing = NetworkToolBackend(transport = StatusTransport(404), confirmationPolicy = NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS, fetchFallback = fallback.asService())
        assertFalse(missing.execute("web_fetch", """{"url":"https://news.example.com/gone"}""").contains("\"via\""))

        // A redirect the device refuses to follow (another host needs its own confirmation) is not fetched elsewhere.
        val elsewhere = NetworkToolBackend(
            transport = StatusTransport(302, location = "https://other.example.org/landing"),
            confirmationPolicy = NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS,
            fetchFallback = fallback.asService(),
        )
        val refusedRedirect = elsewhere.execute("web_fetch", """{"url":"https://short.example.com/x"}""")
        assertTrue(refusedRedirect, refusedRedirect.contains("Cross-host redirect"))
        assertFalse(refusedRedirect.contains("\"via\""))
        assertEquals(listOf("https://blocked.example.com/page", "https://hotels.example.com/detail"), fetched.toList())

        // A search service alone fetches nothing for web_fetch.
        val searchOnly = NetworkToolBackend(transport = StatusTransport(403), confirmationPolicy = NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS, searchService = provider.asService())
        assertFalse(searchOnly.execute("web_fetch", """{"url":"https://blocked.example.com/page"}""").contains("\"via\""))

        // A search is never held for a host confirmation; an empty query is refused before it goes out.
        val asking = NetworkToolBackend(searchService = provider.asService())
        assertEquals(NetworkToolPolicyDecision.Allowed, asking.authorize("run", "web_search", """{"query":"天气"}"""))
        assertTrue(asking.authorize("run", "web_search", """{"query":" "}""") is NetworkToolPolicyDecision.Denied)
        assertTrue(asking.authorize("run", "web_fetch", """{"url":"https://a.example.com"}""") is NetworkToolPolicyDecision.RequiresConfirmation)
    }

    private class StatusTransport(private val status: Int, private val location: String? = null) : WebFetchTransport {
        override suspend fun execute(request: WebFetchRequest) = WebFetchResponse(
            statusCode = status,
            headers = mapOf("Content-Type" to listOf("text/html; charset=utf-8")) + listOfNotNull(location?.let { "Location" to listOf(it) }),
            body = "<html><body>no</body></html>".toByteArray(),
        )
    }
}

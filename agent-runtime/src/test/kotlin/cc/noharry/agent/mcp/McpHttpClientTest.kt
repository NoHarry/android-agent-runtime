@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.mcp

import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import cc.noharry.agent.transport.HttpTransport
import cc.noharry.agent.kernel.api.ProviderConcurrencyMode
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A standard MCP server over Streamable HTTP: initialize and its session, JSON or SSE replies, a lost session. */
class McpHttpClientTest {
    /** Answers each POST with what [reply] returns for the JSON-RPC method, echoing the request id. */
    private class FakeServer(private val reply: (method: String, id: Int?, request: HttpRequest) -> HttpResponse) : HttpTransport {
        val requests: MutableList<HttpRequest> = java.util.Collections.synchronizedList(mutableListOf())

        override fun stream(request: HttpRequest): Flow<HttpEvent> = emptyFlow()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            val body = Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject
            return reply(body["method"]!!.jsonPrimitive.content, body["id"]?.jsonPrimitive?.int, request)
        }

        fun methods() = requests.map { Json.parseToJsonElement(it.body!!.toString(Charsets.UTF_8)).jsonObject["method"]!!.jsonPrimitive.content }
    }

    private fun json(text: String, vararg headers: Pair<String, String>) =
        HttpResponse(200, (headers.toList() + ("Content-Type" to "application/json")).associate { it.first to listOf(it.second) }, text.toByteArray())

    private fun sse(text: String) = HttpResponse(200, mapOf("content-type" to listOf("text/event-stream")), text.toByteArray())

    private fun client(server: FakeServer, headers: Map<String, String> = emptyMap()) =
        McpHttpClient("https://mcp.test/mcp", { headers }, server, "sample", "1.0")

    private val toolsReply = """{"tools":[{"name":"get_weather","description":"天气","inputSchema":{"type":"object"}}]}"""

    @Test
    fun initializeOpensASessionThatEveryLaterRequestCarries() = runBlocking {
        val server = FakeServer { method, id, _ ->
            when (method) {
                "initialize" -> json("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-03-26","capabilities":{}}}""", "Mcp-Session-Id" to "s-1")
                "notifications/initialized" -> HttpResponse(202)
                // A tools/list answered as an SSE stream, with a progress notification first.
                else -> sse("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$toolsReply}\n\n")
            }
        }
        val mcp = client(server, mapOf("Authorization" to "Bearer t"))

        val tools = listMcpToolsCatalog(mcp)

        assertEquals(listOf("get_weather"), tools.map { it.name })
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), server.methods())
        val last = server.requests.last().headers
        assertEquals("s-1", last["Mcp-Session-Id"])
        assertEquals("2025-03-26", last["MCP-Protocol-Version"])
        assertEquals("Bearer t", last["Authorization"])
        assertEquals("application/json, text/event-stream", last["Accept"])
        assertNull("initialize itself has no session yet", server.requests.first().headers["Mcp-Session-Id"])
    }

    @Test
    fun aToolCallReturnsTheJsonRpcResponseForTheProvider() = runBlocking {
        val server = FakeServer { method, id, _ ->
            when (method) {
                "initialize" -> json("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18"}}""")
                "notifications/initialized" -> HttpResponse(202)
                else -> json("""{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"晴"}],"isError":false}}""")
            }
        }
        val provider = McpToolProvider(client(server), serverId = "weather", displayName = "Weather")

        val result = provider.callTool("get_weather", """{"city":"武汉"}""", cc.noharry.agent.kernel.api.ToolCallContext("session", "run", "request"))

        assertTrue(result.success)
        assertTrue(result.resultJson.contains("晴"))
    }

    @Test
    fun aLostSessionIsOpenedAgainOnce() = runBlocking {
        var sessions = 0
        var expired = true
        val server = FakeServer { method, id, request ->
            when {
                method == "initialize" -> json("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18"}}""", "Mcp-Session-Id" to "s-${++sessions}")
                method == "notifications/initialized" -> HttpResponse(202)
                request.headers["Mcp-Session-Id"] == "s-1" && expired -> HttpResponse(404).also { expired = false }
                else -> json("""{"jsonrpc":"2.0","id":$id,"result":$toolsReply}""")
            }
        }
        val mcp = client(server)
        mcp.connect()

        assertEquals(1, listMcpToolsCatalog(mcp).size)
        assertEquals(2, sessions)
        assertEquals("s-2", server.requests.last().headers["Mcp-Session-Id"])
    }

    @Test
    fun callsInFlightTogetherThatLoseTheSessionOpenItAgainOnce() = runBlocking {
        val sessions = AtomicInteger()
        val lost = AtomicInteger()
        val server = FakeServer { method, id, request ->
            when {
                method == "initialize" -> json("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18"}}""", "Mcp-Session-Id" to "s-${sessions.incrementAndGet()}")
                method == "notifications/initialized" -> HttpResponse(202)
                // The server forgot s-1; it answers 404 only once all three calls have arrived.
                request.headers["Mcp-Session-Id"] == "s-1" -> {
                    lost.incrementAndGet()
                    while (lost.get() < 3) Thread.sleep(1)
                    HttpResponse(404)
                }
                else -> json("""{"jsonrpc":"2.0","id":$id,"result":$toolsReply}""")
            }
        }
        val mcp = client(server)
        mcp.connect()

        val replies = List(3) { async(Dispatchers.Default) { mcp.sendRequest("tools/list", JsonObject(emptyMap())) } }.awaitAll()

        assertTrue(replies.all { it.ok })
        assertEquals(2, sessions.get())
        assertEquals(McpToolProvider(mcp, serverId = "weather").providerConcurrency.mode, ProviderConcurrencyMode.RUNTIME_SHARED_THREAD_SAFE)
    }

    @Test
    fun aServerNobodyCouldReachIsNotTriedAgainForAWhileButOneThatAnsweredIs() = runBlocking {
        var now = 0L
        val down = FakeServer { _, _, _ -> throw IOException("timeout") }
        val skipping = McpHttpClient("https://mcp.test/mcp", { emptyMap() }, down, "sample", "1.0", unreachableCooldownMillis = 60_000L, nowMillis = { now })
        assertTrue(skipping.connect().errorMessage!!.contains("Cannot connect"))
        now = 59_000L
        assertTrue(skipping.connect().errorMessage!!.contains("Cannot connect"))
        assertEquals("the second run did not wait on it again", 1, down.requests.size)
        now = 61_000L
        skipping.connect()
        assertEquals(2, down.requests.size)
        skipping.close()
        skipping.connect()
        assertEquals("its switch toggled, it is tried at once", 3, down.requests.size)

        val refusing = FakeServer { _, _, _ -> HttpResponse(401) }
        val answered = McpHttpClient("https://mcp.test/mcp", { emptyMap() }, refusing, "sample", "1.0", unreachableCooldownMillis = 60_000L, nowMillis = { now })
        answered.connect()
        answered.connect()
        assertEquals(2, refusing.requests.size)
    }

    @Test
    fun withARetryScopeAServerNobodyCouldReachIsTriedAgainInTheBackgroundNeverInTheCallersWay() = runBlocking {
        var now = 0L
        var up = false
        val server = FakeServer { method, id, _ ->
            if (!up) throw IOException("timeout")
            when (method) {
                "initialize" -> json("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18"}}""", "Mcp-Session-Id" to "s-1")
                else -> HttpResponse(202)
            }
        }
        val mcp = McpHttpClient("https://mcp.test/mcp", { emptyMap() }, server, "sample", "1.0", unreachableCooldownMillis = 60_000L, nowMillis = { now }, retryScope = this)
        assertFalse(mcp.connect().ok)
        up = true
        now = 61_000L
        assertFalse("says at once it was unreachable, and tries again behind", mcp.connect().ok)
        kotlinx.coroutines.yield()
        assertTrue("the next caller finds it back", mcp.connect().ok)
    }

    @Test
    fun refusalsAndUnreachableServersSayWhatToCheck() = runBlocking {
        val refused = client(FakeServer { _, _, _ -> HttpResponse(401) }).connect()
        assertFalse(refused.ok)
        assertTrue(refused.errorMessage!!.contains("token"))

        val old = client(FakeServer { _, _, _ -> HttpResponse(405) }).connect()
        assertTrue(old.errorMessage!!.contains("SSE"))

        val down = client(FakeServer { _, _, _ -> throw IOException("timeout") }).connect()
        assertTrue(down.errorMessage!!.contains("Cannot connect"))

        val listing = listMcpTools(client(FakeServer { method, id, _ ->
            if (method == "tools/list") json("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"no tools"}}""")
            else json("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18"}}""")
        }))
        assertEquals("tools/list failed: no tools", listing.problem)
    }

    @Test
    fun theSseEventWithTheRequestIdIsTheResponse() {
        val id = Json.parseToJsonElement("7")
        val stream = "data: {\"jsonrpc\":\"2.0\",\"id\":6,\"result\":{}}\r\n\r\ndata: {\"jsonrpc\":\"2.0\",\r\ndata: \"id\":7,\"result\":{\"ok\":true}}\r\n\r\n"

        val response: JsonObject? = sseResponse(stream, id)

        assertEquals("true", response!!["result"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
    }

    @Test
    fun aKeyIsAddedToTheAddressOnlyWhenARequestIsSent() {
        assertEquals("https://a.test/mcp", withQuery("https://a.test/mcp", emptyMap()))
        assertEquals("https://a.test/mcp?key=k%201", withQuery("https://a.test/mcp?key=old", mapOf("key" to "k 1")))
    }

    @Test
    fun anAppSpeaksItsOwnWordsThroughMessages() = runBlocking {
        val messages = object : McpClientMessages {
            override fun httpProblem(status: Int): String? = if (status == 401) "refused-$status" else super.httpProblem(status)
        }
        val refusing = FakeServer { _, _, _ -> HttpResponse(401) }
        val refused = McpHttpClient("https://mcp.test/mcp", { emptyMap() }, refusing, "sample", "1.0", messages = messages).connect()

        assertEquals("refused-401", refused.errorMessage)
    }
}

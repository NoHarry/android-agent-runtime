package cc.noharry.agent.appserver.console
import cc.noharry.agent.mcp.McpHttpClient
import cc.noharry.agent.mcp.listMcpTools
import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import cc.noharry.agent.transport.HttpTransport
import cc.noharry.agent.appserver.mcp.ChineseMcpMessages
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's MCP connections say what to check in Chinese, as 连接应用 and the tool preview show it. */
class ChineseMcpMessagesTest {
    private class FakeServer(private val reply: (method: String, id: Int?) -> HttpResponse) : HttpTransport {
        override fun stream(request: HttpRequest): Flow<HttpEvent> = emptyFlow()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            val body = Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject
            return reply(body["method"]!!.jsonPrimitive.content, body["id"]?.jsonPrimitive?.int)
        }
    }

    private fun client(server: FakeServer) =
        McpHttpClient("https://mcp.test/mcp", { emptyMap() }, server, "sample", "1.0", messages = ChineseMcpMessages)

    private fun json(text: String) =
        HttpResponse(200, mapOf("Content-Type" to listOf("application/json")), text.toByteArray())

    @Test
    fun refusalsAndUnreachableServersSayWhatToCheck() = runBlocking {
        val refused = client(FakeServer { _, _ -> HttpResponse(401) }).connect()
        assertFalse(refused.ok)
        assertTrue(refused.errorMessage!!.contains("令牌"))

        val old = client(FakeServer { _, _ -> HttpResponse(405) }).connect()
        assertTrue(old.errorMessage!!.contains("SSE"))

        val down = client(FakeServer { _, _ -> throw IOException("timeout") }).connect()
        assertEquals("连不上：timeout", down.errorMessage)

        val listing = listMcpTools(
            client(FakeServer { method, id ->
                if (method == "tools/list") json("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"no tools"}}""")
                else json("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18"}}""")
            }),
            ChineseMcpMessages,
        )
        assertEquals("tools/list 出错：no tools", listing.problem)
    }
}

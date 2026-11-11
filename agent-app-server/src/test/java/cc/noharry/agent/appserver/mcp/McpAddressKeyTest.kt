package cc.noharry.agent.appserver.mcp

import cc.noharry.agent.mcp.McpHttpClient
import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import cc.noharry.agent.transport.HttpTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** A key a server wants in its address goes there only as a request is sent, and its refusal reads in Chinese. */
class McpAddressKeyTest {
    @Test
    fun theKeyIsAddedToTheAddressOnlyWhenARequestIsSent() = runBlocking {
        val sent = mutableListOf<String>()
        val http = object : HttpTransport {
            override fun stream(request: HttpRequest): Flow<HttpEvent> = emptyFlow()

            override suspend fun execute(request: HttpRequest): HttpResponse {
                sent += request.url
                return HttpResponse(200, mapOf("Content-Type" to listOf("application/json")), """{"status":"0","info":"INVALID_USER_KEY"}""".toByteArray())
            }
        }
        val client = McpHttpClient("https://mcp.amap.com/mcp?key=old", { emptyMap() }, http, "sample", "1.0", query = { mapOf("key" to "k 1") }, messages = ChineseMcpMessages)

        val result = client.connect()

        assertEquals("https://mcp.amap.com/mcp?key=k%201", sent.single())
        assertEquals("""服务没有回应 initialize：{"status":"0","info":"INVALID_USER_KEY"}""", result.errorMessage)
    }
}

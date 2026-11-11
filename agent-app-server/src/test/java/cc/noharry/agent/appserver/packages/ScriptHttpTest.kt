@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.packages

import cc.noharry.agent.appserver.protocol.HostReach
import cc.noharry.agent.spi.ToolCallContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * host.http from a script in the real engine, to a server on this device (HTTP is allowed there when the package
 * names it): secrets placed by the host and kept from the script, named hosts only, no redirects, sizes and time capped.
 */
class ScriptHttpTest {
    private val server = MockWebServer()
    private val base get() = "http://127.0.0.1:${server.port}"

    @Before
    fun start() = server.start(InetAddress.getByName("127.0.0.1"), 0)

    @After
    fun stop() = server.shutdown()

    @Test
    fun aSecretGoesWhereItsPlaceholderIsAndNeverBackToTheScript() {
        server.enqueue(MockResponse().setBody("""{"echo":"s3cret-key","fare":12.5}""").addHeader("X-Echo", "key=s3cret-key"))
        val result = run(
            """
            function run() {
              const answer = host.http({ url: "@BASE@/fare?from=Bern&key=@KEY@", headers: { "Authorization": "Bearer @KEY@" } });
              return { status: answer.status, body: JSON.parse(answer.body), echo: answer.headers["x-echo"] };
            }
            """,
        )
        val request = server.takeRequest()
        assertEquals("/fare?from=Bern&key=s3cret-key", request.path)
        assertEquals("Bearer s3cret-key", request.getHeader("Authorization"))
        assertEquals("""{"status":200,"body":{"echo":"${'$'}{apiKey}","fare":12.5},"echo":"key=${'$'}{apiKey}"}""", result)
        assertFalse("s3cret" in result)
    }

    @Test
    fun aRequestReachesOnlyANamedHostOverHttpsWithSecretsOnlyWhereTheyMayGo() {
        val refusals = mapOf(
            "{ url: 'http://127.0.0.2:1/x' }" to "127.0.0.2 is not among the hosts this package asked for",
            "{ url: '@BASE@/@KEY@/x' }" to "not in the address",
            "{ url: '@BASE@/x?key=Bearer%20@KEY@' }" to "not a whole query value",
            "{ url: '@BASE@/x?@KEY@=1' }" to "in a parameter's name",
            "{ url: '@BASE@/x', headers: { 'X-Key': '${'$'}{other}' } }" to "names no secret of this package",
            "{ url: '@BASE@/x', headers: { 'X-Key': '${'$'}{api-key}' } }" to "broken placeholder",
            "{ url: '@BASE@/x', headers: { Host: 'elsewhere' } }" to "does not set the header Host",
            "{ url: 'http://user@127.0.0.1:1/x' }" to "the url is not one",
            "{ url: '@BASE@/x', method: 'TRACE' }" to "method TRACE",
            "{ url: '@BASE@/x', body: 'x' }" to "a GET request has no body",
            "{ url: '@BASE@/x', cookies: true }" to "cookies is not a member",
        )
        refusals.forEach { (request, expected) -> assertRefused(expected, request) }
        assertRefused("HTTPS only", "{ url: 'http://api.example.com/x' }", grant(network = setOf("api.example.com")))
        assertRefused("the secret apiKey is not set", "{ url: '@BASE@/x?key=@KEY@' }", grant(values = emptyMap()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aRedirectComesBackAsItIs() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "$base/elsewhere"))
        val result = run("function run() { const a = host.http({ url: '@BASE@/x' }); return [a.status, a.headers.location]; }")
        assertEquals("""{"result":[302,"$base/elsewhere"]}""", result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aPostCarriesItsBodyAndType() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("made"))
        val result = run("function run() { const a = host.http({ method: 'post', url: '@BASE@/x', headers: { 'Content-Type': 'application/json' }, body: '{\"n\":1}' }); return a.status + ' ' + a.body; }")
        val request = server.takeRequest()
        assertEquals(listOf("POST", "{\"n\":1}", "application/json"), listOf(request.method, request.body.readUtf8(), request.getHeader("Content-Type")))
        assertEquals("""{"result":"201 made"}""", result)
    }

    @Test
    fun whatIsSentAndReadIsCapped() {
        assertRefused("the request is over 64 KiB", "{ url: '@BASE@/x', method: 'POST', body: 'x'.repeat(70000) }")
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody("x".repeat(300 * 1024)))
        assertRefused("the answer is over 256 KiB", "{ url: '@BASE@/big' }")
    }

    @Test
    fun aRequestHasWhatIsLeftOfTheCallsTimeAndNoLimitOfItsOwn() {
        server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS))
        val http = ScriptHttp(grant(), ScriptLimits(), scriptHttpClient())
        val request = JsonObject(mapOf("url" to JsonPrimitive("$base/slow")))
        val refused = assertThrows(ScriptRefusal::class.java) { runBlocking { http.send(request, leftMillis = 300) } }
        assertEquals("host.http: no answer in time", refused.message)

        // A call of a tool asking 1 s ends then, its request with it, though the answer would come in 3 s.
        server.enqueue(MockResponse().setHeadersDelay(3, TimeUnit.SECONDS))
        val tool = checkPackage(TestPackages.build(TestPackages.scriptFiles(listOf(TestPackages.tool { put("timeoutSeconds", 1) })))).manifest.tools.single()
        val runner = ScriptRunner(grant(), ScriptHttp(grant(), ScriptLimits(), scriptHttpClient()), ScriptHostTools(emptySet(), emptyList()), {})
        val started = System.nanoTime()
        val code = "function run() { return host.http({ url: '$base/slower' }).status; }"
        val result = runBlocking { runner.call(tool, code, """{"from": "Bern", "to": "Basel"}""", ToolCallContext("s", "r", "q")) }
        val millis = (System.nanoTime() - started) / 1_000_000
        assertFalse(result.resultJson, result.success)
        // Either the request's refusal reaches the script just before the engine's own deadline, or that deadline ends it.
        assertTrue(result.resultJson, "no answer in time" in result.resultJson || "the script ran over its time (1 s)" in result.resultJson)
        assertTrue("ended after $millis ms", millis in 900..2_500)
    }

    @Test
    fun aScriptsRequestNeverGoesThroughAProxyWhichWouldLookTheNameUpItself() {
        val proxied = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 3128)))
        val client = scriptHttpClient(proxied)
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertTrue(client.dns is ScriptDns)
    }

    @Test
    fun aNameAnswersOnlyWhereItSaysItIs() {
        fun dns(vararg addresses: String) = ScriptDns(object : Dns {
            override fun lookup(hostname: String) = addresses.map { InetAddress.getByName(it) }
        })
        assertThrows(UnknownHostException::class.java) { dns("10.0.0.1").lookup("api.example.com") }
        assertThrows(UnknownHostException::class.java) { dns("93.184.216.34", "127.0.0.1").lookup("api.example.com") }
        assertThrows(UnknownHostException::class.java) { dns("93.184.216.34").lookup("localhost") }
        assertEquals(1, dns("93.184.216.34").lookup("api.example.com").size)
        assertEquals(1, dns("127.0.0.1").lookup("localhost").size)
        assertEquals(1, dns("192.168.1.9").lookup("printer.local").size)
        val reaches = listOf("127.0.0.1", "::1", "0.0.0.0", "10.1.2.3", "172.20.0.1", "192.168.0.1", "169.254.1.1", "100.64.1.1", "fd00::1", "fe80::1", "8.8.8.8", "2001:db8::1")
            .map { addressReach(InetAddress.getByName(it)) }
        val (device, local, internet) = Triple(HostReach.DEVICE, HostReach.LOCAL_NETWORK, HostReach.INTERNET)
        assertEquals(listOf(device, device, device, local, local, local, local, local, local, local, internet, internet), reaches)
    }

    private fun assertRefused(expected: String, request: String, grant: ScriptGrant = grant(), limits: ScriptLimits = ScriptLimits()) {
        val result = run("function run() { try { host.http($request); return 'sent'; } catch (e) { return e.message; } }", grant, limits)
        val message = (Json.parseToJsonElement(result) as JsonObject)["result"]!!.jsonPrimitive.content
        assertTrue("\"$expected\" not in \"$message\"", expected in message && message.startsWith("host.http: "))
    }

    private fun run(script: String, grant: ScriptGrant = grant(), limits: ScriptLimits = ScriptLimits()): String {
        val runner = ScriptRunner(grant, ScriptHttp(grant, limits, scriptHttpClient()), ScriptHostTools(emptySet(), emptyList()), {}, limits)
        val code = script.trimIndent().replace("@BASE@", base).replace("@KEY@", "\${apiKey}")
        val result = runBlocking { runner.call(TOOL, code, """{"from": "Bern", "to": "Basel"}""", ToolCallContext("s", "r", "q")) }
        assertTrue(result.resultJson, result.success)
        return result.resultJson
    }

    private fun grant(network: Set<String> = setOf("127.0.0.1"), values: Map<String, String> = mapOf("apiKey" to "s3cret-key")) =
        ScriptGrant("com.example.trains", network, setOf("apiKey"), values, emptySet())

    private companion object {
        val TOOL = checkPackage(TestPackages.build(TestPackages.scriptFiles())).manifest.tools.single()
    }
}

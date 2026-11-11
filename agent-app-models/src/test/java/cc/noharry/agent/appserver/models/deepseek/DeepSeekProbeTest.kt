package cc.noharry.agent.appserver.models.deepseek
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.model.webLookup
import cc.noharry.agent.appserver.protocol.BalanceAmountView
import cc.noharry.agent.appserver.protocol.BalanceView
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.server.PluginAdditions
import java.util.Collections
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh")
class DeepSeekProbeTest {
    // DeepSeek's lookups and native updates come from its adapter, which its plugin installs.
    @org.junit.Before
    fun installDeepSeek() = AppServerTesting.running(PluginAdditions(providerAdapters = listOf(DeepSeekAdapter)))

    @org.junit.After
    fun noAdapters() = AppServerTesting.reset()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val seen = Collections.synchronizedList(mutableListOf<Request>())
    private val bodies = Collections.synchronizedList(mutableListOf<String>())

    @Test
    fun deepSeeksRootIsLookedForWithoutTheApiSuffixThenAtTheBaseItself() {
        assertEquals(
            listOf("https://gw.example.com/agent/deepseek", "https://gw.example.com/agent/deepseek/v1"),
            deepSeekRootCandidates(" https://gw.example.com/agent/deepseek/v1/ "),
        )
        assertEquals(listOf("https://relay.example.com", "https://relay.example.com/anthropic"), deepSeekRootCandidates("https://relay.example.com/anthropic"))
        assertEquals(listOf("https://relay.example.com/deepseek"), deepSeekRootCandidates("https://relay.example.com/deepseek"))
        assertEquals(emptyList<String>(), deepSeekRootCandidates("relay.example.com/v1"))
        assertEquals(
            listOf("http://127.0.0.1:18080/user/balance", "http://127.0.0.1:18080/v1/user/balance"),
            deepSeekBalanceCandidates("http://127.0.0.1:18080/v1"),
        )
        assertEquals(listOf("http://127.0.0.1:18080/v1/tools", "http://127.0.0.1:18080/v1/v1/tools"), deepSeekToolsCandidates("http://127.0.0.1:18080/v1"))
    }

    @Test
    fun aRelayMappingItsV1OntoDeepSeekPassesAllThreeThroughItsOwnAddressAndKey() = runBlocking {
        // The PC car console: only /v1/* is forwarded, onto the upstream's root.
        val relay = relay { path, body ->
            when (path) {
                "/v1/user/balance" -> 200 to BALANCE
                "/v1/v1/tools/search" -> 200 to """{"results":[{"title":"DeepSeek","url":"https://www.deepseek.com"}]}"""
                "/v1/messages" -> deepSeeksRefusal(body)
                else -> 404 to "Use the /v1 API prefix"
            }
        }

        val probe = probeDeepSeek(ProviderEndpoint.MESSAGES, "http://127.0.0.1:18080/v1", " local ", "deepseek-v4-flash", relay)

        assertEquals("http://127.0.0.1:18080/v1/user/balance", probe.balance.url)
        assertEquals(
            Finding(FindingCode.MODEL_BALANCE, balance = BalanceView(true, listOf(BalanceAmountView("CNY", "110.00", "100.00", "10.00")))),
            probe.balance.note,
        )
        assertEquals("http://127.0.0.1:18080/v1/v1/tools", probe.tools.url)
        assertEquals("http://127.0.0.1:18080/v1/messages", probe.passthrough?.url)
        assertEquals(deepSeekProbeFingerprint(ProviderEndpoint.MESSAGES, "http://127.0.0.1:18080/v1/", "local"), probe.fingerprint)

        val search = seen.single { it.url.encodedPath == "/v1/v1/tools/search" }
        assertEquals("Bearer local", search.header("Authorization"))
        val messages = seen.filter { it.url.encodedPath == "/v1/messages" }
        assertEquals(2, messages.size)
        assertTrue(messages.all { it.header("x-api-key") == "local" && it.header("anthropic-beta") == "mid-conversation-tool-changes-2026-07-01" })
        val deferOnly = bodies.single { it.contains("connection_check_deferred") && !it.contains("tool_addition") }
        assertFalse(deferOnly, deferOnly.contains("\"name\":\"connection_check\""))
        val loading = bodies.single { it.contains("tool_addition") }
        assertTrue(loading, loading.contains("\"name\":\"connection_check\"") && loading.contains("\"name\":\"connection_check_undeclared\""))
        assertTrue(loading, loading.contains("\"thinking\":{\"type\":\"disabled\"}"))
    }

    @Test
    fun aGatewayThatRebuildsRequestsPassesNothing() = runBlocking {
        // A new-api style gateway: its web page answers any unknown GET, the model answers the dropped update.
        val gateway = relay { path, _ ->
            when {
                path.endsWith("/user/balance") -> 200 to "<!doctype html><title>New API</title>"
                path.endsWith("/messages") -> 200 to """{"type":"message","content":[{"type":"text","text":"Hi"}]}"""
                else -> 404 to """{"error":{"message":"Invalid URL (POST $path)"}}"""
            }
        }

        val probe = probeDeepSeek(ProviderEndpoint.MESSAGES, "https://newapi.example.com/v1", "sk-gw", "deepseek-v4-flash", gateway)

        assertFalse(probe.balance.passed)
        assertEquals(finding(FindingCode.MODEL_PROBE_NOT_BALANCE), probe.balance.note)
        assertFalse(probe.tools.passed)
        val firstTools = probe.tools.note.takeIf { it.isOf(FindingCode.ALTERNATIVES) }?.more?.first() ?: probe.tools.note
        assertEquals(FindingCode.MODEL_HTTP_ERROR.name, firstTools.code)
        assertTrue(firstTools.args.toString(), firstTools.args[1].startsWith("Invalid URL"))
        assertFalse(probe.passthrough!!.passed)
        assertEquals(finding(FindingCode.MODEL_PROBE_DROPPED, "defer_loading"), probe.passthrough!!.note)
    }

    @Test
    fun aRelayKeepingDeferLoadingButDroppingTheUpdateIsNoPassthrough() = runBlocking {
        val halfway = relay { _, body ->
            if (body.contains("tool_addition")) 200 to """{"type":"message","content":[{"type":"text","text":"Hi"}]}""" else deepSeeksRefusal(body)
        }

        val probe = probeDeepSeek(ProviderEndpoint.MESSAGES, "https://gw.example.com/anthropic", "k", "m", halfway)

        assertFalse(probe.passthrough!!.passed)
        assertEquals(finding(FindingCode.MODEL_PROBE_DROPPED, "tool_addition"), probe.passthrough!!.note)
    }

    @Test
    fun onlyDeepSeeksOwnRefusalCountsAsPassingTheUpdateThrough() = runBlocking {
        val strict = relay { path, _ ->
            if (path.endsWith("/messages")) 400 to """{"error":{"message":"unsupported content type tool_addition"}}""" else 404 to ""
        }

        val probe = probeDeepSeek(ProviderEndpoint.MESSAGES, "https://gw.example.com/anthropic", "k", "m", strict)
        val chat = probeDeepSeek(ProviderEndpoint.CHAT_COMPLETIONS, "https://gw.example.com/v1", "k", "m", strict)
        val noModel = probeDeepSeek(ProviderEndpoint.MESSAGES, "https://gw.example.com/anthropic", "k", "", strict)

        assertFalse(probe.passthrough!!.passed)
        assertEquals(finding(FindingCode.MODEL_HTTP_ERROR, 400, "unsupported content type tool_addition"), probe.passthrough!!.note)
        assertNull(chat.passthrough)
        assertEquals(finding(FindingCode.MODEL_PROBE_NO_MODEL), noModel.passthrough!!.note)
        assertEquals(finding(FindingCode.HTTP_STATUS, 404), probe.balance.note)
    }

    @Test
    fun aCheckIsTiedToTheEndpointAddressAndKeyWithoutKeepingTheKey() {
        val base = deepSeekProbeFingerprint(ProviderEndpoint.MESSAGES, "https://gw.example.com/anthropic", "sk-secret")
        assertEquals(base, deepSeekProbeFingerprint(ProviderEndpoint.MESSAGES, " https://gw.example.com/anthropic/ ", " sk-secret "))
        assertNotEquals(base, deepSeekProbeFingerprint(ProviderEndpoint.MESSAGES, "https://gw.example.com/anthropic", "sk-other"))
        assertNotEquals(base, deepSeekProbeFingerprint(ProviderEndpoint.CHAT_COMPLETIONS, "https://gw.example.com/anthropic", "sk-secret"))
        assertFalse(base.contains("secret"))

        val probe = DeepSeekProbe(base, DeepSeekCheck("https://gw.example.com/user/balance", finding(FindingCode.MODEL_PROBE_SEARCH_AVAILABLE)), DeepSeekCheck(null, finding(FindingCode.HTTP_STATUS, 404)), null, 42L)
        val stored = encodeDeepSeekProbe(probe)
        assertFalse(stored.contains("sk-secret"))
        assertEquals(probe, decodeDeepSeekProbe(stored))
    }

    @Test
    fun aPassedCheckUnlocksTheRelayUntilItsAddressOrKeyChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val relay = ProviderProfile("relay", "Relay", ModelProvider.ANTHROPIC, "sk-relay", "deepseek-v4-flash", "https://gw.example.com/anthropic", ProviderEndpoint.MESSAGES)
        val loaded = ProviderConfig.load(context)
        val config = loaded.copy(profiles = loaded.profiles.filterNot { it.isDeepSeek() } + relay)

        assertFalse(DeepSeekAdapter.nativeUpdates(context, config, relay))
        assertNull(config.deepSeekToolsOf(context, relay))
        assertFalse(config.profiles.any { DeepSeekAdapter.hasLookup(context, config, it) })

        DeepSeekProbes.save(
            context,
            "relay",
            DeepSeekProbe(
                deepSeekProbeFingerprint(relay.endpoint, relay.baseUrl, "sk-relay"),
                DeepSeekCheck(null, finding(FindingCode.HTTP_STATUS, 404)),
                DeepSeekCheck("https://gw.example.com/v1/tools", finding(FindingCode.MODEL_PROBE_SEARCH_AVAILABLE)),
                DeepSeekCheck("https://gw.example.com/anthropic/messages", finding(FindingCode.MODEL_PROBE_PASSED_THROUGH)),
                1L,
            ),
        )

        assertTrue(DeepSeekAdapter.nativeUpdates(context, config, relay))
        val tools = config.deepSeekToolsOf(context, relay)
        assertNotNull(tools)
        assertEquals("gw.example.com", tools!!.label)
        assertTrue(config.profiles.any { DeepSeekAdapter.hasLookup(context, config, it) })
        assertNotNull(config.webLookup(context, relay))

        val rekeyed = relay.copy(apiKey = "sk-new")
        assertFalse(DeepSeekAdapter.nativeUpdates(context, config, rekeyed))
        assertNull(config.deepSeekToolsOf(context, rekeyed))
        assertFalse(DeepSeekAdapter.nativeUpdates(context, config, relay.copy(baseUrl = "https://other.example.com/anthropic")))
        // DeepSeek's own host needs no check.
        val official = relay.copy(id = "official", baseUrl = "https://api.deepseek.com/anthropic")
        assertTrue(DeepSeekAdapter.nativeUpdates(context, config, official))
        assertEquals("api.deepseek.com", config.deepSeekToolsOf(context, official)?.label)
    }

    private fun relay(answer: (path: String, body: String) -> Pair<Int, String>): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            seen += request
            val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
            if (body.isNotEmpty()) bodies += body
            val (code, text) = answer(request.url.encodedPath, body)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }).build()

    /** DeepSeek's own refusals of the two check requests, as it answers them. */
    private fun deepSeeksRefusal(body: String): Pair<Int, String> = if (body.contains("tool_addition")) {
        400 to """{"error":{"message":"tool_addition references unknown tool 'connection_check_undeclared'"}}"""
    } else {
        400 to """{"error":{"message":"tools: at least one tool must have defer_loading=false. All tools cannot be deferred. (request_id: x)"}}"""
    }

    private companion object {
        const val BALANCE = """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}]}"""
    }
}

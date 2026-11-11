package cc.noharry.agent.appserver.server

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.FoundException
import cc.noharry.agent.appserver.model.ConnectionKey
import cc.noharry.agent.appserver.model.ModelService
import cc.noharry.agent.appserver.model.ProviderAdapter
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.model.ServiceEndpoint
import cc.noharry.agent.appserver.model.listModels
import cc.noharry.agent.appserver.model.savedKeyMayGo
import cc.noharry.agent.appserver.protocol.BalanceView
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderConnection
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.ProviderSaveParams
import cc.noharry.agent.appserver.protocol.ServiceBalanceParams
import cc.noharry.agent.appserver.protocol.ServiceCheckParams
import cc.noharry.agent.appserver.protocol.ServiceCheckPart
import cc.noharry.agent.appserver.protocol.ServiceCheckView
import cc.noharry.agent.appserver.protocol.ServiceRequests
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.appserver.runtime.ProviderPresets
import cc.noharry.agent.appserver.runtime.catalogKey
import cc.noharry.agent.transport.OkHttpHttpTransport
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A key saved for a profile goes only to the host that saved profile points at, or to an address a check made with the
 * key typed found and the server recorded for it; elsewhere every request about a connection (a balance, a check, the
 * stored check, a model list, saving the connection) sends nothing and asks for the key again. A key typed in the
 * request goes where the request says. The keys are stand-ins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SavedKeyRuleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sent = mutableListOf<Pair<String, String>>()
    private val checked = mutableListOf<String>()

    private val service = ModelService(
        id = "example",
        label = "Example AI",
        endpoints = listOf(ServiceEndpoint(ProviderEndpoint.CHAT_COMPLETIONS, OFFICIAL_BASE)),
        balance = true,
        relayCheck = true,
    )

    /** An adapter that sends the key where a service's would: its balance to the service or a relay, its check to the base URL. */
    private val adapter = object : ProviderAdapter {
        override val services = listOf(service)

        override suspend fun balance(context: Context, service: ModelService, apiKey: ConnectionKey, relayUrl: String?): BalanceView {
            val url = relayUrl ?: OFFICIAL_BALANCE
            sent += url to apiKey.sendTo(url)
            return BalanceView(true)
        }

        override fun checked(context: Context, service: ModelService, profile: ProviderProfile, apiKey: ConnectionKey) =
            ServiceCheckView(hasKey = !apiKey.isBlank)

        override suspend fun check(context: Context, service: ModelService, profile: ProviderProfile, apiKey: ConnectionKey): ServiceCheckView {
            val url = "${profile.baseUrl}/user/balance"
            sent += url to apiKey.sendTo(url)
            checked += profile.baseUrl
            return ServiceCheckView(checked = true, current = true, hasKey = true, balance = ServiceCheckPart(true, finding(FindingCode.MODEL_BALANCE), url))
        }
    }

    @Before
    fun presets() {
        context.getSharedPreferences("app_server_provider_config", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("app_server_key_destinations", Context.MODE_PRIVATE).edit().clear().commit()
        AppServerTesting.install(AppServerConfig(name = "test", presets = ProviderPresets(configText = PROFILES, localOverridesText = LOCAL)))
        AppServerTesting.running(PluginAdditions(providerAdapters = listOf(adapter)))
    }

    @After
    fun reset() = AppServerTesting.reset()

    @Test
    fun aSavedKeyGoesOnlyOverHttpsOrToThisDeviceOrNetworkAndOnlyToItsProfilesHost() {
        assertTrue(savedKeyMayGo("https://API.example.com/v1", "https://api.example.com/user/balance"))
        assertTrue(savedKeyMayGo("http://127.0.0.1:18080/v1", "http://127.0.0.1:18080/v1/messages"))
        assertTrue(savedKeyMayGo("http://192.168.1.20:8080/v1", "http://192.168.1.20:8080/models"))
        assertFalse(savedKeyMayGo("http://relay.example.net/v1", "http://relay.example.net/user/balance"))
        assertFalse(savedKeyMayGo("https://api.example.com/v1", "https://api.example.com:8443/user/balance"))
        assertFalse(savedKeyMayGo("https://api.example.com/v1", "http://api.example.com/user/balance"))
        assertFalse(savedKeyMayGo("https://api.example.com/v1", "https://api.example.com.evil.test/user/balance"))
        assertFalse(savedKeyMayGo("https://api.example.com/v1", "https://user@api.example.com/user/balance"))
    }

    @Test
    fun aBalanceSendsTheSavedKeyOnlyToItsProfilesHost() = runBlocking {
        assertEquals(BalanceView(true), balance(ServiceBalanceParams("example", connection = connection(OFFICIAL))).balance)
        assertEquals(listOf(OFFICIAL_BALANCE to OFFICIAL_KEY), sent)
        // The relay's saved key, asked of the service itself; then the official key at a relay the client names.
        val asked = listOf(
            ServiceBalanceParams("example", connection = connection(RELAY)),
            ServiceBalanceParams("example", connection = connection(OFFICIAL, "https://evil.test/v1"), url = "https://evil.test/user/balance"),
        )
        asked.forEach { params ->
            val failure = balance(params).failure
            assertEquals(FindingCode.MODEL_KEY_TYPE_AGAIN.name, failure?.more?.single()?.code)
        }
        assertEquals(1, sent.size)
        // Typed in the request, a key goes where the request says.
        balance(ServiceBalanceParams("example", apiKey = "sk-typed", connection = connection(OFFICIAL, "https://evil.test/v1"), url = "https://evil.test/user/balance"))
        assertEquals("https://evil.test/user/balance" to "sk-typed", sent.last())
    }

    @Test
    fun aCheckOfAnotherHostWithTheSavedKeyIsNotMadeAndTheStoredCheckSaysSo() = runBlocking {
        val elsewhere = connection(RELAY, "https://evil.test/v1")
        val view = call(ServiceRequests.CHECK, ServiceCheckParams("example", elsewhere))
        assertTrue(view.savedKeyRefused)
        assertEquals(emptyList<String>(), checked)
        assertEquals(emptyList<Pair<String, String>>(), sent)
        assertTrue(call(ServiceRequests.CHECKED, ServiceCheckParams("example", elsewhere)).savedKeyRefused)
        assertFalse(call(ServiceRequests.CHECKED, ServiceCheckParams("example", connection(RELAY))).savedKeyRefused)
        // A connection with no key at all sends nothing either.
        assertFalse(call(ServiceRequests.CHECK, ServiceCheckParams("example", connection("unsaved", "https://new.test/v1"))).hasKey)
        assertEquals(emptyList<String>(), checked)
        // Its own host is checked with the saved key.
        assertFalse(call(ServiceRequests.CHECK, ServiceCheckParams("example", connection(RELAY))).savedKeyRefused)
        assertEquals(listOf(RELAY_BASE), checked)
    }

    @Test
    fun aRelayACheckFoundWithTheKeyTypedTakesThatKeyOnceSavedAndNoOther() = runBlocking {
        val newRelay = "https://new-relay.test/v1"
        // Checked with the official key typed again: where its balance passed is recorded for the profile.
        call(ServiceRequests.CHECK, ServiceCheckParams("example", connection(OFFICIAL, newRelay).copy(apiKey = OFFICIAL_KEY)))
        val relayBalance = ServiceBalanceParams("example", connection = connection(OFFICIAL, newRelay), url = "$newRelay/user/balance")
        assertNull(balance(relayBalance).failure)
        assertEquals("$newRelay/user/balance" to OFFICIAL_KEY, sent.last())
        // Recorded for another key, it does not take the saved one.
        call(ServiceRequests.CHECK, ServiceCheckParams("example", connection(RELAY, newRelay).copy(apiKey = "sk-other")))
        val other = balance(ServiceBalanceParams("example", connection = connection(RELAY, newRelay), url = "$newRelay/user/balance")).failure
        assertEquals(FindingCode.MODEL_KEY_TYPE_AGAIN.name, other?.more?.single()?.code)
    }

    @Test
    fun aModelListSendsTheSavedKeyOnlyToItsProfilesHost() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"m"}]}"""))
        server.start()
        try {
            val local = server.url("/v1").toString().trimEnd('/')
            context.getSharedPreferences("app_server_provider_config", Context.MODE_PRIVATE).edit()
                .putString("profile.relay.base_url", local).commit()
            val config = ProviderConfig.load(context)
            val profile = ProviderProfile(RELAY, RELAY, ModelProvider.OPENAI, "", "", local)
            assertEquals(listOf("m"), listModels(context, OkHttpHttpTransport(), profile, catalogKey(context, config, RELAY, "", local)))
            assertEquals("Bearer $RELAY_KEY", server.takeRequest().getHeader("Authorization"))
            try {
                val elsewhere = profile.copy(baseUrl = "https://evil.test/v1")
                listModels(context, OkHttpHttpTransport(), elsewhere, catalogKey(context, config, RELAY, "", elsewhere.baseUrl))
                fail("the saved key went to another host")
            } catch (refused: FoundException) {
                assertEquals(FindingCode.MODEL_KEY_TYPE_AGAIN.name, refused.finding.code)
            }
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun savingTheConnectionAtAnotherHostKeepsTheSavedKeyOnlyWhenItIsTyped() {
        val backend = RuntimeBackend(context)
        val profile = ProviderConfig.load(context).profileForProvider(ModelProvider.OPENAI)
        fun save(baseUrl: String, key: String = "") = backend.savedKeyMoves(ProviderSaveParams(ModelProvider.OPENAI, key, profile.model, baseUrl, profile.endpoint))
        assertNull(save(profile.baseUrl))
        assertNull(save(profile.baseUrl.substringBeforeLast('/') + "/v2"))
        assertEquals(FindingCode.MODEL_KEY_TYPE_AGAIN.name, save("https://evil.test/v1")?.code)
        assertNull(save("https://evil.test/v1", "sk-typed"))
    }

    private suspend fun balance(params: ServiceBalanceParams) = call(ServiceRequests.BALANCE, params)

    /** [method] answered by the server's own routes, as a client's request is. */
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

    private fun connection(profileId: String, baseUrl: String = if (profileId == OFFICIAL) OFFICIAL_BASE else RELAY_BASE) =
        ProviderConnection(profileId, profileId, ModelProvider.OPENAI, ProviderEndpoint.CHAT_COMPLETIONS, "", "m", baseUrl)

    private companion object {
        const val OFFICIAL = "official"
        const val RELAY = "relay"
        const val OFFICIAL_BASE = "https://api.example.com/v1"
        const val OFFICIAL_BALANCE = "https://api.example.com/user/balance"
        const val RELAY_BASE = "https://relay.example.net/v1"
        const val OFFICIAL_KEY = "sk-official-stand-in"
        const val RELAY_KEY = "sk-relay-stand-in"

        const val PROFILES = """{"defaultProfile":"official","profiles":[
            {"id":"official","provider":"openai","baseUrl":"$OFFICIAL_BASE","model":"m","apiKey":""},
            {"id":"relay","provider":"openai","baseUrl":"$RELAY_BASE","model":"m","apiKey":""}]}"""
        const val LOCAL = "AGENT_RUNTIME_PROVIDER_PROFILE_OFFICIAL_API_KEY=$OFFICIAL_KEY\nAGENT_RUNTIME_PROVIDER_PROFILE_RELAY_API_KEY=$RELAY_KEY"
    }
}

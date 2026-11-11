package cc.noharry.agent.appserver.models.deepseek

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.ConnectionKey
import cc.noharry.agent.appserver.model.ModelService
import cc.noharry.agent.appserver.model.ProviderAdapter
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.protocol.BalanceView
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderConnection
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.ServiceBalanceParams
import cc.noharry.agent.appserver.protocol.ServiceBalanceResult
import cc.noharry.agent.appserver.protocol.ServiceCheckParams
import cc.noharry.agent.appserver.protocol.ServiceRequests
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcErrorCodes
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.ProtocolJson
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.appserver.runtime.ProviderPresets
import cc.noharry.agent.appserver.server.AppServerConfig
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.server.PluginAdditions
import cc.noharry.agent.appserver.server.RouteTable
import cc.noharry.agent.appserver.server.coreModules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DeepSeek's balance and check, asked through the server's service requests ([ServiceRequests]), use the key a run on the
 * same profile uses, a key the build's presets carry only for another profile on DeepSeek's host included
 * ([DeepSeekAdapter.shareKeys]); a request that names no key is refused, and a profile with no key at all says so,
 * sending nothing. The keys are stand-ins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DeepSeekRequestKeysTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sent = mutableListOf<String>()

    /** DeepSeek's adapter, whose balance only notes the key it would send, where DeepSeek's own sends it. */
    private val adapter = object : ProviderAdapter by DeepSeekAdapter {
        override suspend fun balance(context: Context, service: ModelService, apiKey: ConnectionKey, relayUrl: String?): BalanceView {
            sent += apiKey.sendTo(relayUrl ?: "https://api.deepseek.com/user/balance")
            return BalanceView(true)
        }
    }

    @Before
    fun presets() {
        context.getSharedPreferences("app_server_provider_config", Context.MODE_PRIVATE).edit().clear().commit()
        AppServerTesting.install(AppServerConfig(name = "test", presets = ProviderPresets(configText = PROFILES, localOverridesText = LOCAL)))
        AppServerTesting.running(PluginAdditions(providerAdapters = listOf(adapter)))
    }

    @After
    fun reset() = AppServerTesting.reset()

    @Test
    fun aPresetKeySharedFromAnotherProfileOnTheHostIsTheKeyARunAndEveryRequestUse() = runBlocking {
        val config = ProviderConfig.load(context)
        assertEquals(PRESET_KEY, config.effectiveApiKey(config.profiles.single { it.id == ANTHROPIC }))
        assertEquals(PRESET_KEY, config.keyFor(ANTHROPIC, ""))
        // As the settings page asks: the profile's connection, no key typed.
        assertEquals(BalanceView(true), balance(ServiceBalanceParams(DEEPSEEK, connection = connection(ANTHROPIC))).balance)
        assertEquals(listOf(PRESET_KEY), sent)
        assertTrue(call(ServiceRequests.CHECKED, ServiceCheckParams(DEEPSEEK, connection(ANTHROPIC))).hasKey)
        // The profile named as a selection names it, case aside.
        assertEquals(PRESET_KEY, config.keyFor("DeepSeek-Anthropic", null))
    }

    @Test
    fun aKeyTypedWins() = runBlocking {
        balance(ServiceBalanceParams(DEEPSEEK, apiKey = " sk-typed ", connection = connection(ANTHROPIC)))
        balance(ServiceBalanceParams(DEEPSEEK, apiKey = "sk-typed-alone"))
        assertEquals(listOf("sk-typed", "sk-typed-alone"), sent)
    }

    @Test
    fun aRequestThatNamesNoKeyOrAServiceWithoutABalanceIsRefusedAndNothingIsSent() = runBlocking {
        // A profile id beside the key, where the request has no such member: no key, no connection.
        val params = ProtocolJson.decodeFromString(ServiceBalanceParams.serializer(), """{"service":"deepseek","profileId":"$ANTHROPIC","apiKey":""}""")
        listOf(params, ServiceBalanceParams("no-such-service", apiKey = "sk-typed")).forEach { refused ->
            try {
                balance(refused)
                fail("answered ${refused.service} with no key or no balance")
            } catch (expected: JsonRpcException) {
                assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, expected.code)
            }
        }
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun aRelayProfilesSavedKeyIsNotSentToDeepSeekItself() = runBlocking {
        context.getSharedPreferences("app_server_provider_config", Context.MODE_PRIVATE).edit()
            .putString("profile.pc_local.api_key", "sk-relay-stand-in").commit()
        // The relay's own key, asked of DeepSeek's balance: refused, as the person did not type it for DeepSeek.
        val failure = balance(ServiceBalanceParams(DEEPSEEK, connection = connection("pc-local", "https://relay.example.com/v1"))).failure
        assertEquals(FindingCode.MODEL_KEY_TYPE_AGAIN.name, failure?.more?.single()?.code)
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun aProfileWithNoKeyAnywhereSaysSoAndSendsNothing() = runBlocking {
        val failure = balance(ServiceBalanceParams(DEEPSEEK, connection = connection("pc-local", "https://relay.example.com/v1"))).failure
        assertEquals(FindingCode.MODEL_BALANCE_FAILED.name, failure?.code)
        assertEquals(FindingCode.MODEL_MISSING_API_KEY.name, failure?.more?.single()?.code)
        assertEquals(emptyList<String>(), sent)
    }

    private suspend fun balance(params: ServiceBalanceParams): ServiceBalanceResult = call(ServiceRequests.BALANCE, params)

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

    private fun connection(profileId: String, baseUrl: String = "https://api.deepseek.com/anthropic") =
        ProviderConnection(profileId, profileId, ModelProvider.ANTHROPIC, ProviderEndpoint.MESSAGES, "", "deepseek-flash", baseUrl)

    private companion object {
        const val DEEPSEEK = "deepseek"
        const val ANTHROPIC = "deepseek-anthropic"
        const val PRESET_KEY = "sk-preset-stand-in"

        /** As a host's build may carry them: the profiles with blank keys, the key from local.properties for one of them. */
        const val PROFILES = """{"defaultProfile":"deepseek-anthropic","profiles":[
            {"id":"pc-local","provider":"anthropic","baseUrl":"https://relay.example.com/v1","model":"deepseek-flash","apiKey":""},
            {"id":"deepseek-api","provider":"openai","baseUrl":"https://api.deepseek.com/v1","model":"deepseek-flash","apiKey":""},
            {"id":"deepseek-anthropic","provider":"anthropic","baseUrl":"https://api.deepseek.com/anthropic","model":"deepseek-flash","apiKey":""}]}"""
        const val LOCAL = "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_API_API_KEY=$PRESET_KEY"
    }
}

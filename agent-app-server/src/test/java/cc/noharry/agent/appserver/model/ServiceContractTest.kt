package cc.noharry.agent.appserver.model

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.ModelFacts
import cc.noharry.agent.appserver.protocol.ModelPrice
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.server.AppServerConfig
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.server.PluginAdditions
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelClientException
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.spi.reliability.RuntimeErrorCode
import cc.noharry.agent.transport.OkHttpHttpTransport
import java.net.SocketTimeoutException
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the server takes from a model service's adapter ([ProviderAdapter]), with a made-up service: which profiles are on
 * it, the defaults a profile without a base URL takes, its models' facts under the host's own, its model list, and how
 * its failures read over the wire protocols' own reading.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ServiceContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val service = ModelService(
        id = "example",
        label = "Example AI",
        endpoints = listOf(
            ServiceEndpoint(ProviderEndpoint.CHAT_COMPLETIONS, "https://api.example.com/v1"),
            ServiceEndpoint(ProviderEndpoint.MESSAGES, "https://api.example.com/messages-api"),
        ),
        hosts = setOf(".tenant.example.net"),
        models = listOf(
            ServiceModel("example-large", ModelFacts(contextWindow = 1_000_000, imageInput = false, maxOutputTokens = 64_000), ModelPrice(1.0, 4.0, source = "example")),
            ServiceModel("example-small", ModelFacts(contextWindow = 200_000), aliases = setOf("example-small-latest")),
        ),
        errors = listOf(ErrorRule(ModelFailureKind.QUOTA, status = 429, pattern = "out_of_credit"), ErrorRule(ModelFailureKind.RATE_LIMIT, pattern = "insufficient_quota")),
        defaultModel = "example-small",
    )
    private val adapter = object : ProviderAdapter {
        override val services = listOf(service)
    }

    @After
    fun reset() = AppServerTesting.reset()

    @Test
    fun aProfileIsOnTheServiceByItsHostAndOneWithoutABaseUrlTakesTheServicesDefaults() {
        AppServerTesting.running(PluginAdditions(providerAdapters = listOf(adapter)))
        assertEquals("example", serviceAt("https://api.example.com/v1")?.second?.id)
        assertEquals("example", serviceAt("https://acme.tenant.example.net/v1")?.second?.id)
        assertNull(serviceAt("https://api.example.com.evil.test/v1"))
        assertNull(serviceAt("https://tenant.example.net.evil.test/v1"))

        val bare = ProviderProfile("example", "Example", ModelProvider.ANTHROPIC, "k", "", "", ProviderEndpoint.MESSAGES)
        val filled = listOf(bare, bare.copy(id = "other")).withServiceDefaults()
        assertEquals("https://api.example.com/messages-api", filled[0].baseUrl)
        // The service's default model, not the first of its list.
        assertEquals("example-small", filled[0].model)
        assertEquals("", filled[1].baseUrl)
    }

    @Test
    fun anEndpointNotGivenIsTheServicesDefaultAndItsDefaultModelGoesOnlyThere() {
        val twoWays = ModelService(
            id = "two",
            label = "Two",
            endpoints = listOf(ServiceEndpoint(ProviderEndpoint.RESPONSES, "https://api.two.test/v1"), ServiceEndpoint(ProviderEndpoint.CHAT_COMPLETIONS, "https://api.two.test/v1")),
            models = listOf(ServiceModel("two-large", ModelFacts(toolCalls = true)), ServiceModel("two-small", ModelFacts(toolCalls = true))),
            defaultModel = "two-small",
        )
        AppServerTesting.running(PluginAdditions(providerAdapters = listOf(object : ProviderAdapter { override val services = listOf(twoWays) })))
        val notGiven = ProviderProfile("two", "Two", ModelProvider.OPENAI, "k", "", "", ProviderEndpoint.CHAT_COMPLETIONS, searchCapability = "openai-chat-options", endpointGiven = false)
        val (defaulted, given, named) = listOf(notGiven, notGiven.copy(endpointGiven = true), notGiven.copy(model = "two-large", endpointGiven = false)).map {
            listOf(it).withServiceDefaults().single()
        }
        assertEquals(ProviderEndpoint.RESPONSES, defaulted.endpoint)
        assertEquals("two-small", defaulted.model)
        assertEquals("openai-responses-tool", defaulted.searchCapability)
        assertEquals(ProviderEndpoint.CHAT_COMPLETIONS, given.endpoint)
        assertEquals("", given.model)
        assertEquals("two-large", named.model)
    }

    @Test
    fun aPriceIsTheListOfTheProfilesRegionOnTheDayAsked() {
        val day = LocalDate.of(2026, 11, 11)
        val regional = ModelService(
            id = "regional",
            label = "Regional",
            endpoints = listOf(ServiceEndpoint(ProviderEndpoint.CHAT_COMPLETIONS, "https://api.regional.cn/v1", "cn"), ServiceEndpoint(ProviderEndpoint.CHAT_COMPLETIONS, "https://api.regional.io/v1", "global")),
            hosts = setOf(".maas.regional.cn", ".maas.regional.io"),
            hostRegions = mapOf(".beijing.maas.regional.cn" to "cn"),
            models = listOf(
                ServiceModel(
                    "r-1",
                    price = ModelPrice(8.0, 28.0, currency = "CNY", region = "cn"),
                    prices = listOf(
                        ModelPrice(1.0, 4.0, region = "global"),
                        ModelPrice(2.0, 8.0, region = "global", effectiveFrom = "2026-12-01"),
                    ),
                ),
                ServiceModel("r-2", price = ModelPrice(3.0, 9.0, currency = "CNY", region = "cn")),
            ),
        )
        AppServerTesting.running(PluginAdditions(providerAdapters = listOf(object : ProviderAdapter { override val services = listOf(regional) })))
        assertEquals("CNY", modelPrice("r-1", "https://api.regional.cn/v1", day)?.currency)
        assertEquals(8.0, modelPrice("r-1", "https://ws1.beijing.maas.regional.cn/v1", day)!!.input, 0.0)
        assertEquals(1.0, modelPrice("r-1", "https://api.regional.io/v1", day)!!.input, 0.0)
        // An announced change applies from its day on.
        assertEquals(1.0, modelPrice("r-1", "https://api.regional.io/v1", LocalDate.of(2026, 11, 30))!!.input, 0.0)
        assertEquals(2.0, modelPrice("r-1", "https://api.regional.io/v1", LocalDate.of(2026, 12, 1))!!.input, 0.0)
        // The international endpoint with no list of its own for the model, or a host of a region the data does not know: no price.
        assertNull(modelPrice("r-2", "https://api.regional.io/v1", day))
        assertNull(modelPrice("r-1", "https://ws1.frankfurt.maas.regional.io/v1", day))
        // By its name alone (a relay), from the list of the service's first region.
        assertEquals(8.0, modelPrice("relay/r-1", "https://relay.test/v1", day)!!.input, 0.0)
    }

    @Test
    fun theHostsFactsWinFieldByFieldAndARelayFindsTheModelByName() {
        AppServerTesting.running(PluginAdditions(providerAdapters = listOf(adapter)))
        assertEquals(ModelFacts(contextWindow = 1_000_000, imageInput = false, maxOutputTokens = 64_000), modelFacts("https://api.example.com/v1", "example-large"))
        assertEquals(200_000L, modelFacts("https://api.example.com/v1", "example-small-latest").contextWindow)
        assertEquals(1_000_000L, modelFacts("https://relay.test/v1", "relay/example-large").contextWindow)
        assertEquals(ModelFacts(), modelFacts("https://api.example.com/v1", "unknown"))

        AppHost.install(modelFacts = mapOf("example-large" to ModelFacts(contextWindow = 500_000)))
        val facts = modelFacts("https://api.example.com/v1", "example-large")
        assertEquals(500_000L, facts.contextWindow)
        assertEquals(false, facts.imageInput)
        assertEquals(500_000L, defaultContextWindow("https://api.example.com/v1", "example-large"))

        // The person's own choice wins over both.
        val profile = ProviderEditState("example-large", baseUrl = "https://api.example.com/v1", profileId = "facts-test")
        assertFalse(loadModelImageInput(context, profile))
        saveModelImageInput(context, profile, true)
        assertTrue(loadModelImageInput(context, profile))
        saveContextWindow(context, profile, 300_000)
        assertEquals(300_000L, loadContextWindow(context, profile))
        AppHost.install(modelFacts = emptyMap())
    }

    @Test
    fun theHostsPriceWinsOverTheServicesAndARelayIsPricedByTheModelsName() {
        AppServerTesting.running(PluginAdditions(providerAdapters = listOf(adapter)))
        assertEquals(1.0, modelPrice("relay/example-large")!!.input, 0.0)
        AppHost.install(modelPrices = mapOf("example-large" to ModelPrice(2.0, 8.0)))
        assertEquals(2.0, modelPrice("example-large")!!.input, 0.0)
        AppHost.install(modelPrices = emptyMap())
        assertNull(modelPrice("unknown"))
    }

    @Test
    fun aFailureReadsAsTheServiceSaysThenAsTheWireProtocolsSay() = runBlocking {
        AppServerTesting.running(PluginAdditions(providerAdapters = listOf(adapter)))
        val at = "https://api.example.com/v1"
        assertEquals(RuntimeErrorCode.PROVIDER_QUOTA_EXCEEDED, failing(429, """{"error":{"code":"out_of_credit"}}""").classifiedAt(at).code())
        // The service's rule over the wire protocols' reading of the same code.
        assertEquals(RuntimeErrorCode.PROVIDER_RATE_LIMITED, failing(429, """{"error":{"code":"insufficient_quota"}}""").classifiedAt(at).code())
        assertEquals(RuntimeErrorCode.PROVIDER_QUOTA_EXCEEDED, failing(429, """{"error":{"code":"insufficient_quota"}}""").classifiedAt("https://other.test/v1").code())
        // On a host no adapter knows (a relay that may front a per-minute limit), a 429 is tried again as the runtime decided.
        val relayed = runCatching { failing(429, """{"error":{"code":"insufficient_quota"}}""", recoverable = true).classifiedAt("https://other.test/v1").complete(REQUEST).toList() }
            .exceptionOrNull() as ModelClientException
        assertEquals(RuntimeErrorCode.PROVIDER_QUOTA_EXCEEDED, relayed.code)
        assertTrue(relayed.recoverable)
        val atService = runCatching { failing(429, """{"error":{"code":"out_of_credit"}}""", recoverable = true).classifiedAt(at).complete(REQUEST).toList() }
            .exceptionOrNull() as ModelClientException
        assertFalse(atService.recoverable)
        assertEquals(RuntimeErrorCode.PROVIDER_CONTEXT_WINDOW_EXCEEDED, failing(400, """{"error":{"code":"context_length_exceeded"}}""").classifiedAt(at).code())
        assertEquals(RuntimeErrorCode.PROVIDER_QUOTA_EXCEEDED, failing(402, "").classifiedAt(at).code())
        assertEquals(RuntimeErrorCode.PROVIDER_TIMEOUT, failing(504, "").classifiedAt(at).code())
        assertEquals(RuntimeErrorCode.PROVIDER_SERVER_ERROR, failing(529, """{"type":"error","error":{"type":"overloaded_error"}}""").classifiedAt(at).code())
        assertEquals(RuntimeErrorCode.PROVIDER_HTTP_ERROR, failing(400, """{"error":"bad"}""").classifiedAt(at).code())
        val timedOut = ModelClientException(RuntimeErrorCode.PROVIDER_TRANSPORT_FAILED, true, "openai provider transport failed.", SocketTimeoutException("read"))
        assertEquals(RuntimeErrorCode.PROVIDER_TIMEOUT, throwing(timedOut).classifiedAt(at).code())
        val quota = runCatching { failing(402, "").classifiedAt(at).complete(REQUEST).toList() }.exceptionOrNull() as ModelClientException
        assertFalse(quota.recoverable)
    }

    @Test
    fun onlyTheEffortsTheVariantMenuCanSendAreShownAndAHostPriceNeedsACurrencyTheBookConverts() {
        val efforts = ModelFacts(reasoningEfforts = listOf("low", "medium", "high", "xhigh", "max"))
        assertEquals(listOf("low", "medium", "high", "max"), efforts.selectableEfforts(ModelProvider.ANTHROPIC, "claude-sonnet-5-5").reasoningEfforts)
        assertEquals(emptyList<String>(), efforts.selectableEfforts(ModelProvider.OPENAI, "a-model-the-runtime-does-not-know").reasoningEfforts)
        assertThrows(IllegalArgumentException::class.java) {
            AppServerConfig(name = "test", modelPrices = mapOf("m" to ModelPrice(1.0, 2.0, currency = "EUR")))
        }
        AppServerConfig(name = "test", modelPrices = mapOf("m" to ModelPrice(1.0, 2.0, currency = "CNY")))
    }

    @Test
    fun theModelListIsAtTheServicesListWithoutItsPrefixOrItsOwnWhenItListsNone() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"models/example-b"},{"id":"models/example-a"}]}"""))
        server.start()
        try {
            val base = server.url("/v1").toString()
            val listed = ModelService("listed", "Listed", listOf(ServiceEndpoint(ProviderEndpoint.MESSAGES, base)), modelsUrl = server.url("/catalog").toString(), listedIdPrefix = "models/")
            val unlisted = ModelService("unlisted", "Unlisted", listOf(ServiceEndpoint(ProviderEndpoint.CHAT_COMPLETIONS, "https://unlisted.test/v1")), models = listOf(ServiceModel("only")), listsModels = false)
            AppServerTesting.running(PluginAdditions(providerAdapters = listOf(object : ProviderAdapter { override val services = listOf(listed, unlisted) })))
            val profile = ProviderProfile("p", "P", ModelProvider.ANTHROPIC, "sk-test", "", base, ProviderEndpoint.MESSAGES)

            assertEquals(listOf("example-a", "example-b"), listModels(context, OkHttpHttpTransport(), profile, ConnectionKey.typed("sk-test")))
            val asked = server.takeRequest()
            assertEquals("/catalog", asked.path)
            // A service's own list is OpenAI-compatible, asked with a bearer key wherever the profile's endpoint is.
            assertEquals("Bearer sk-test", asked.getHeader("Authorization"))
            assertEquals(listOf("only"), listModels(context, OkHttpHttpTransport(), profile.copy(baseUrl = "https://unlisted.test/v1"), ConnectionKey.typed("k")))
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    private fun failing(status: Int, body: String, recoverable: Boolean = false): ModelClient =
        throwing(ModelClientException(RuntimeErrorCode.PROVIDER_HTTP_ERROR, recoverable, "openai provider HTTP $status.${if (body.isEmpty()) "" else " Response: $body"}"))

    private fun throwing(error: ModelClientException) = object : ModelClient {
        override fun complete(request: ModelRequest): Flow<ModelEvent> = flow { throw error }
    }

    private suspend fun ModelClient.code(): RuntimeErrorCode =
        try {
            complete(REQUEST).toList()
            fail("the call did not fail")
            error("unreachable")
        } catch (failed: ModelClientException) {
            failed.code
        }

    private companion object {
        val REQUEST = ModelRequest(messages = emptyList())
    }
}

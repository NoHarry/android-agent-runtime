package cc.noharry.agent.appserver.models

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.ModelFailureKind
import cc.noharry.agent.appserver.model.ModelService
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.model.ServiceEndpoint
import cc.noharry.agent.appserver.model.ServiceModel
import cc.noharry.agent.appserver.model.modelFacts
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.server.PluginAdditions
import cc.noharry.agent.spi.model.ModelClientException
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.spi.reliability.RuntimeErrorCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Each service as the server meets it, against a server on this machine that answers as the service's pages say it
 * does: its model list (where it lists one, with its prefix, at its own address, with the wire's key header), its
 * models' facts, and its failures, each sent through the wire protocol's own client and read as the stable code the
 * service's rules or the wire give it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ServicesMockTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer().apply { start() }

    @After
    fun stop() {
        server.shutdown()
        AppServerTesting.reset()
    }

    @Test
    fun everyServicesModelListIsReadAsItsPagesSay() = runBlocking {
        ServiceCatalog.services.forEach { original ->
            val service = original.onThisMachine()
            install(service)
            val endpoint = service.endpoints.first()
            val profile = ProviderProfile("p", "P", endpoint.endpoint.provider, "sk-test", "", endpoint.baseUrl, endpoint.endpoint)
            val before = server.requestCount
            if (!service.listsModels) {
                assertEquals(service.id, service.models.map { it.id }, AppServerTesting.models(context, profile, "sk-test"))
                assertEquals(service.id, before, server.requestCount)
                return@forEach
            }
            server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"${service.listedIdPrefix}zeta"},{"id":"${service.listedIdPrefix}alpha"}]}"""))
            assertEquals(service.id, listOf("alpha", "zeta"), withTimeout(CALL_MILLIS) { AppServerTesting.models(context, profile, "sk-test") })
            val asked = checkNotNull(server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)) { service.id }
            val path = service.modelsUrl?.let { java.net.URI(it).path } ?: (java.net.URI(endpoint.baseUrl).path + "/models")
            assertEquals(service.id, path, asked.requestUrl?.encodedPath)
            if (endpoint.endpoint == ProviderEndpoint.MESSAGES && service.modelsUrl == null) {
                assertEquals(service.id, "sk-test", asked.getHeader("x-api-key"))
                assertEquals(service.id, "2023-06-01", asked.getHeader("anthropic-version"))
            } else {
                assertEquals(service.id, "Bearer sk-test", asked.getHeader("Authorization"))
            }
        }
    }

    @Test
    fun everyListedModelHasItsFactsAndAnyOtherTheDefaults() {
        ServiceCatalog.services.forEach { original ->
            val service = original.onThisMachine()
            install(service)
            val base = service.endpoints.first().baseUrl
            service.models.forEach { model ->
                assertEquals("${service.id} ${model.id}", model.facts, modelFacts(base, model.id))
                model.alsoNamed().forEach { alias -> assertEquals(alias, model.facts, modelFacts(base, alias)) }
            }
            assertEquals(service.id, cc.noharry.agent.appserver.protocol.ModelFacts(), modelFacts(base, "no-such-model"))
        }
    }

    @Test
    fun everyServicesFailuresReadAsItsPagesSay() = runBlocking {
        ServiceCatalog.services.forEach { original ->
            val service = original.onThisMachine()
            install(service)
            val endpoint = service.endpoints.first()
            val profile = ProviderProfile("p", "P", endpoint.endpoint.provider, "sk-test", "m", endpoint.baseUrl, endpoint.endpoint)
            val config = ProviderConfig.load(context).let { it.copy(profiles = it.profiles + profile) }
            FAILURES.getValue(service.id).forEach { (status, body, kind) ->
                // OkHttp asks once more itself after a 408.
                val sent = if (status == HTTP_REQUEST_TIMEOUT) 2 else 1
                repeat(sent) { server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)) }
                val failure = runCatching {
                    withTimeout(CALL_MILLIS) { config.client(profile, stream = false).complete(REQUEST).toList() }
                }.exceptionOrNull()
                assertTrue("${service.id} $status: $failure", failure is ModelClientException)
                assertEquals("${service.id} $status $body", kind?.code ?: RuntimeErrorCode.PROVIDER_HTTP_ERROR, (failure as ModelClientException).code)
                repeat(sent) { assertTrue(service.id, server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) != null) }
            }
        }
    }

    @Test
    fun everyErrorRuleOfTheDataHasAFailureThatShowsIt() {
        ServiceCatalog.services.forEach { service ->
            service.errors.forEach { rule ->
                val shown = FAILURES.getValue(service.id).any { (status, body, kind) ->
                    kind == rule.kind && rule.matches(cc.noharry.agent.appserver.model.ModelFailure(status, body, RuntimeErrorCode.PROVIDER_HTTP_ERROR))
                }
                assertTrue("${service.id}: no failure shows ${rule.kind} ${rule.status} ${rule.pattern}", shown)
            }
        }
    }

    private fun install(service: ModelService) = AppServerTesting.running(PluginAdditions(providerAdapters = listOf(ServiceAdapter(service))))

    /** [this] service on this machine's server: its endpoints and list there, under the same paths; everything else as it is. */
    private fun ModelService.onThisMachine(): ModelService {
        val local = server.url("/").toString().trimEnd('/')
        fun rebased(url: String) = local + java.net.URI(url).rawPath
        return ModelService(
            id = id, label = label, endpoints = endpoints.map { ServiceEndpoint(it.endpoint, rebased(it.baseUrl), it.region) },
            keyHint = keyHint, models = models, modelsUrl = modelsUrl?.let(::rebased), listsModels = listsModels,
            listedIdPrefix = listedIdPrefix, usageFields = usageFields, errors = errors, retry = retry, balance = balance,
            relayCheck = relayCheck, source = source, asOf = asOf,
        )
    }

    private fun ServiceModel.alsoNamed(): Set<String> = aliases

    private companion object {
        val REQUEST = ModelRequest(messages = listOf(ModelMessage(role = "user", content = "hi")))
        const val CALL_MILLIS = 15_000L
        const val HTTP_REQUEST_TIMEOUT = 408

        private fun f(status: Int, body: String, kind: ModelFailureKind?) = Triple(status, body, kind)

        /**
         * Each service's failures as its error pages describe them (the status, and the body's code or words), with what
         * they read as: null for the runtime's own reading of an HTTP failure.
         */
        val FAILURES: Map<String, List<Triple<Int, String, ModelFailureKind?>>> = mapOf(
            "deepseek" to listOf(
                f(402, """{"error":{"message":"Insufficient Balance","type":"unknown_error"}}""", ModelFailureKind.QUOTA),
                f(401, """{"error":{"message":"Authentication Fails, Your api key is invalid"}}""", ModelFailureKind.AUTH),
                f(429, """{"error":{"message":"Rate Limit Reached"}}""", ModelFailureKind.RATE_LIMIT),
                f(503, """{"error":{"message":"Server Overloaded"}}""", ModelFailureKind.SERVER),
            ),
            "openai" to listOf(
                f(429, """{"error":{"type":"insufficient_quota","code":"credit_balance_exhausted","message":"Credit balance exhausted"}}""", ModelFailureKind.QUOTA),
                f(429, """{"error":{"type":"insufficient_quota","code":"project_spend_limit_exceeded"}}""", ModelFailureKind.QUOTA),
                f(429, """{"error":{"type":"rate_limit_error","code":"slow_down"}}""", ModelFailureKind.RATE_LIMIT),
                f(503, """{"error":{"type":"service_unavailable_error","code":"server_is_overloaded"}}""", ModelFailureKind.SERVER),
            ),
            "anthropic" to listOf(
                f(400, """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 1000001 tokens > 1000000 maximum"}}""", ModelFailureKind.CONTEXT_WINDOW),
                f(402, """{"type":"error","error":{"type":"billing_error","message":"billing"}}""", ModelFailureKind.QUOTA),
                f(529, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""", ModelFailureKind.SERVER),
                f(500, """{"type":"error","error":{"type":"timeout_error","message":"The request timed out while processing."}}""", ModelFailureKind.TIMEOUT),
                f(400, """{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens: too large"}}""", null),
            ),
            "gemini" to listOf(
                f(429, """{"error":{"code":"quota_exceeded","message":"You have exceeded your daily quota."}}""", ModelFailureKind.QUOTA),
                f(400, """{"error":{"code":400,"message":"Gemini API free tier is not available in your country.","status":"FAILED_PRECONDITION"}}""", ModelFailureKind.QUOTA),
                f(400, """{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""", ModelFailureKind.AUTH),
                f(500, """{"error":{"code":500,"message":"Your input context is too long.","status":"INTERNAL"}}""", ModelFailureKind.CONTEXT_WINDOW),
                f(504, """{"error":{"code":"deadline_exceeded","message":"The request didn't finish within the deadline."}}""", ModelFailureKind.TIMEOUT),
                f(429, """{"error":{"code":"rate_limit_exceeded","message":"per-minute limit"}}""", ModelFailureKind.RATE_LIMIT),
            ),
            "xai" to listOf(
                f(401, """{"code":"unauthorized","error":"No authorization header"}""", ModelFailureKind.AUTH),
                f(429, """{"code":"rate-limited","error":"Too Many Requests"}""", ModelFailureKind.RATE_LIMIT),
            ),
            "openrouter" to listOf(
                f(402, """{"error":{"code":402,"message":"Insufficient credits"}}""", ModelFailureKind.QUOTA),
                f(403, """{"error":{"code":403,"message":"Key limit exceeded","metadata":{"limit_source":"openrouter_key_limit"}}}""", ModelFailureKind.QUOTA),
                f(400, """{"error":{"code":400,"message":"too long","metadata":{"error_type":"context_length_exceeded"}}}""", ModelFailureKind.CONTEXT_WINDOW),
                f(408, """{"error":{"code":408,"message":"Your request timed out"}}""", ModelFailureKind.TIMEOUT),
            ),
            "groq" to listOf(
                f(400, """{"error":{"message":"Spend limit reached","type":"invalid_request_error","code":"blocked_api_access"}}""", ModelFailureKind.QUOTA),
                f(498, """{"error":{"message":"Flex Tier Capacity Exceeded"}}""", ModelFailureKind.SERVER),
                f(429, """{"error":{"message":"Too Many Requests","type":"rate_limit_exceeded"}}""", ModelFailureKind.RATE_LIMIT),
            ),
            "moonshot" to listOf(
                f(429, """{"error":{"type":"exceeded_current_quota_error","message":"Account balance is insufficient"}}""", ModelFailureKind.QUOTA),
                f(429, """{"error":{"type":"engine_overloaded_error","message":"The engine is currently overloaded"}}""", ModelFailureKind.SERVER),
                f(429, """{"error":{"type":"rate_limit_reached_error","message":"concurrency limit reached"}}""", ModelFailureKind.RATE_LIMIT),
                f(400, """{"error":{"type":"invalid_request_error","message":"Input token length too long"}}""", ModelFailureKind.CONTEXT_WINDOW),
            ),
            "zhipu" to listOf(
                f(429, """{"error":{"code":"1113","message":"Insufficient balance or no resource package. Please recharge."}}""", ModelFailureKind.QUOTA),
                f(429, """{"error":{"code":"1305","message":"The service may be temporarily overloaded"}}""", ModelFailureKind.SERVER),
                f(429, """{"error":{"code":"1302","message":"Rate limit reached for requests"}}""", ModelFailureKind.RATE_LIMIT),
                f(400, """{"error":{"code":"1261","message":"Prompt too long"}}""", ModelFailureKind.CONTEXT_WINDOW),
            ),
            "dashscope" to listOf(
                f(400, """{"error":{"code":"Arrearage","message":"Access denied, please make sure your account is in good standing."}}""", ModelFailureKind.QUOTA),
                f(429, """{"error":{"code":"Throttling.AllocationQuota","message":"Free allocated quota exceeded."}}""", ModelFailureKind.QUOTA),
                f(429, """{"error":{"code":"insufficient_quota","message":"You exceeded your current quota"}}""", ModelFailureKind.RATE_LIMIT),
                f(400, """{"error":{"code":"InvalidParameter","message":"Range of input length should be [1, 991808]"}}""", ModelFailureKind.CONTEXT_WINDOW),
                f(500, """{"error":{"code":"RequestTimeOut","message":"Request timed out, please try again later."}}""", ModelFailureKind.TIMEOUT),
            ),
            "volcengine" to listOf(
                f(403, """{"error":{"code":"AccountOverdueError","message":"The request failed because your account has an overdue balance."}}""", ModelFailureKind.QUOTA),
                f(429, """{"error":{"code":"QuotaExceeded","message":"Your account has exhausted its free trial quota"}}""", ModelFailureKind.QUOTA),
                f(429, """{"error":{"code":"ServerOverloaded","message":"server overload"}}""", ModelFailureKind.SERVER),
                f(429, """{"error":{"code":"ModelAccountTpmRateLimitExceeded","message":"TPM limit"}}""", ModelFailureKind.RATE_LIMIT),
                f(400, """{"error":{"code":"OutofContextError","message":"Total tokens of image and text exceed max message tokens."}}""", ModelFailureKind.CONTEXT_WINDOW),
                f(408, """{"error":{"code":"RequestTimeout","message":"Request to service is timeout"}}""", ModelFailureKind.TIMEOUT),
            ),
            "minimax" to listOf(
                f(400, """{"base_resp":{"status_code":1008,"status_msg":"insufficient balance"}}""", ModelFailureKind.QUOTA),
                f(400, """{"base_resp":{"status_code":1002,"status_msg":"rate limit"}}""", ModelFailureKind.RATE_LIMIT),
                f(400, """{"base_resp":{"status_code": 2049,"status_msg":"invalid API Key"}}""", ModelFailureKind.AUTH),
                f(400, """{"base_resp":{"status_code":1001,"status_msg":"request timeout"}}""", ModelFailureKind.TIMEOUT),
                f(400, """{"base_resp":{"status_code":2064,"status_msg":"high load"}}""", ModelFailureKind.SERVER),
                f(400, """{"base_resp":{"status_code":10080,"status_msg":"other"}}""", null),
            ),
            "siliconflow" to listOf(
                f(402, """{"code":30001,"message":"Account balance is insufficient"}""", ModelFailureKind.QUOTA),
                f(503, """{"code":50505,"message":"Model service overloaded. Please try again later."}""", ModelFailureKind.SERVER),
                f(504, """{"message":"Timeout"}""", ModelFailureKind.TIMEOUT),
            ),
        )
    }
}

package cc.noharry.agent.model

import cc.noharry.agent.kernel.api.model.ModelClientException

import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import cc.noharry.agent.transport.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelMessage

class ModelClientFailureContractTest {
    @Test
    fun openAiNonStreamingClassifiesHttpFailures() {
        assertProviderCode(RuntimeErrorCode.PROVIDER_AUTH_FAILED, recoverable = false) { openAi(responseStatus = 401) }
        assertProviderCode(RuntimeErrorCode.PROVIDER_AUTH_FAILED, recoverable = false) { openAi(responseStatus = 403) }
        assertProviderCode(RuntimeErrorCode.PROVIDER_RATE_LIMITED) { openAi(responseStatus = 429) }
        assertProviderCode(RuntimeErrorCode.PROVIDER_SERVER_ERROR) { openAi(responseStatus = 500) }
        assertProviderCode(RuntimeErrorCode.PROVIDER_HTTP_ERROR, recoverable = false) { openAi(responseStatus = 418) }
    }

    @Test
    fun anthropicNonStreamingClassifiesHttpFailures() {
        assertProviderCode(RuntimeErrorCode.PROVIDER_AUTH_FAILED, recoverable = false) {
            anthropic(responseStatus = 401)
        }
        assertProviderCode(RuntimeErrorCode.PROVIDER_RATE_LIMITED) { anthropic(responseStatus = 429) }
        assertProviderCode(RuntimeErrorCode.PROVIDER_SERVER_ERROR) { anthropic(responseStatus = 503) }
        assertProviderCode(RuntimeErrorCode.PROVIDER_HTTP_ERROR, recoverable = false) { anthropic(responseStatus = 400) }
    }

    @Test
    fun providerClientsClassifyTransportFailures() {
        assertProviderCode(RuntimeErrorCode.PROVIDER_TRANSPORT_FAILED) {
            openAi(transportError = IllegalStateException("socket secret=$API_KEY"))
        }
        assertProviderCode(RuntimeErrorCode.PROVIDER_TRANSPORT_FAILED) {
            openAi(transportFailure = IOException("timeout secret=$API_KEY"))
        }
        assertProviderCode(RuntimeErrorCode.PROVIDER_TRANSPORT_FAILED) {
            openAi(stream = true, streamError = IllegalStateException("socket secret=$API_KEY"))
        }
        assertProviderCode(RuntimeErrorCode.PROVIDER_TRANSPORT_FAILED) {
            openAi(stream = true, streamFailure = IOException("stream timeout secret=$API_KEY"))
        }
    }

    @Test
    fun providerClientsClassifyMissingCredentialsAsNonRecoverableAuthFailures() {
        assertProviderCode(RuntimeErrorCode.PROVIDER_AUTH_FAILED, recoverable = false) {
            openAi(apiKeyProvider = { "" })
        }
        assertProviderCode(RuntimeErrorCode.PROVIDER_AUTH_FAILED, recoverable = false) {
            anthropic(apiKeyProvider = { throw IllegalArgumentException("missing secret=$API_KEY") })
        }
    }

    @Test
    fun providerClientsPropagateCancellation() {
        assertCancellation {
            openAi(transportError = CancellationException("cancelled"))
        }
        assertCancellation {
            openAi(stream = true, streamError = CancellationException("stream cancelled"))
        }
    }

    @Test
    fun providerClientsClassifyMalformedResponses() {
        assertProviderCode(RuntimeErrorCode.PROVIDER_RESPONSE_MALFORMED) { openAi(body = "{not-json") }
        assertProviderCode(RuntimeErrorCode.PROVIDER_RESPONSE_MALFORMED) {
            anthropic(stream = true, streamText = "data: {not-json}\n\n")
        }
    }

    @Test
    fun providerClientsClassifyStreamingHttpFailures() {
        assertProviderCode(RuntimeErrorCode.PROVIDER_RATE_LIMITED) { openAi(stream = true, streamStatus = 429) }
        assertProviderCode(RuntimeErrorCode.PROVIDER_SERVER_ERROR) { anthropic(stream = true, streamStatus = 503) }
        assertProviderCode(RuntimeErrorCode.PROVIDER_HTTP_ERROR, recoverable = false) {
            openAi(stream = true, streamStatus = 418)
        }
        assertProviderCode(RuntimeErrorCode.PROVIDER_AUTH_FAILED, recoverable = false) {
            anthropic(stream = true, streamStatus = 401)
        }
    }

    @Test
    fun providerHttpFailureIncludesRedactedNonStreamingBodyPreview() {
        val error = captureProviderError {
            openAi(responseStatus = 400, body = """{"error":{"message":"bad token sk-secret12345"}}""")
        }

        assertEquals(RuntimeErrorCode.PROVIDER_HTTP_ERROR, error.code)
        assertTrue(error.message.contains("bad token"))
        assertFalse(error.message.contains("sk-secret12345"))
    }

    @Test
    fun providerHttpFailureIncludesRedactedStreamingBodyPreview() {
        val error = captureProviderError {
            openAi(
                stream = true,
                streamStatus = 400,
                streamText = """{"error":{"message":"invalid request","api_key":"$API_KEY"}}""",
            )
        }

        assertEquals(RuntimeErrorCode.PROVIDER_HTTP_ERROR, error.code)
        assertTrue(error.message.contains("invalid request"))
        assertFalse(error.message.contains(API_KEY))
    }
}

private fun assertProviderCode(
    expected: RuntimeErrorCode,
    recoverable: Boolean = true,
    createClient: () -> ModelClient,
) {
    val error = try {
        runBlocking { createClient().complete(modelRequest()).toList() }
        null
    } catch (error: ModelClientException) {
        error
    }

    requireNotNull(error)
    assertEquals(expected, error.code)
    assertEquals(recoverable, error.recoverable)
    assertFalse(error.message.orEmpty().contains(API_KEY))
}

private fun captureProviderError(createClient: () -> ModelClient): ModelClientException {
    val error = try {
        runBlocking { createClient().complete(modelRequest()).toList() }
        null
    } catch (error: ModelClientException) {
        error
    }
    return requireNotNull(error)
}

private fun assertCancellation(createClient: () -> ModelClient) {
    val error = try {
        runBlocking { createClient().complete(modelRequest()).toList() }
        null
    } catch (error: CancellationException) {
        error
    }

    requireNotNull(error)
}

private fun openAi(
    responseStatus: Int = 200,
    body: String = """{"choices":[{"message":{}}]}""",
    stream: Boolean = false,
    streamText: String = "",
    streamStatus: Int = 200,
    transportError: RuntimeException? = null,
    transportFailure: IOException? = null,
    streamError: RuntimeException? = null,
    streamFailure: IOException? = null,
    apiKeyProvider: () -> String = ::apiKey,
): ModelClient = OpenAiModelClient(
    apiKeyProvider = apiKeyProvider,
    model = MODEL,
    baseUrl = BASE_URL,
    stream = stream,
    transport = FailureTransport(
        responseStatus,
        body,
        streamText,
        streamStatus,
        transportError,
        transportFailure,
        streamError,
        streamFailure,
    ),
)

private fun anthropic(
    responseStatus: Int = 200,
    body: String = """{"content":[]}""",
    stream: Boolean = false,
    streamText: String = "",
    streamStatus: Int = 200,
    apiKeyProvider: () -> String = ::apiKey,
): ModelClient = AnthropicModelClient(
    apiKeyProvider = apiKeyProvider,
    model = MODEL,
    baseUrl = BASE_URL,
    stream = stream,
    transport = FailureTransport(responseStatus, body, streamText, streamStatus),
)

private class FailureTransport(
    private val responseStatus: Int,
    private val body: String,
    private val streamText: String,
    private val streamStatus: Int = 200,
    private val transportError: RuntimeException? = null,
    private val transportFailure: IOException? = null,
    private val streamError: RuntimeException? = null,
    private val streamFailure: IOException? = null,
) : HttpTransport {
    override fun stream(request: HttpRequest): Flow<HttpEvent> = flow {
        streamError?.let { throw it }
        streamFailure?.let { throw it }
        emit(HttpEvent.Response(streamStatus))
        emit(HttpEvent.Data(streamText.toByteArray(Charsets.UTF_8)))
        emit(HttpEvent.Done)
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        transportError?.let { throw it }
        transportFailure?.let { throw it }
        return HttpResponse(responseStatus, body = body.toByteArray(Charsets.UTF_8))
    }
}

private fun modelRequest(): ModelRequest =
    ModelRequest(messages = listOf(ModelMessage("user", "make a plan")))

private fun apiKey(): String = API_KEY

private const val API_KEY = "unit-test-key"
private const val MODEL = "provider-test-model"
private const val BASE_URL = "https://provider.test/v1"

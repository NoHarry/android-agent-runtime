package cc.noharry.agent.model

import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import cc.noharry.agent.transport.HttpTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelMessage

class ModelClientSystemPromptTest {
    @Test
    fun openAiSerializesSystemPromptAsFirstMessage() = runBlocking {
        val transport = SystemPromptTransport("""{"choices":[{"message":{}}]}""")

        OpenAiModelClient(::apiKey, MODEL, BASE_URL, transport = transport)
            .complete(modelRequest().copy(system = "protocol\nhost append"))
            .toList()

        val messages = transport.executeRequests.single().bodyJson()["messages"]?.jsonArray.orEmpty()

        assertEquals("system", messages[0].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertTrue(messages[0].jsonObject["content"]?.jsonPrimitive?.contentOrNull.orEmpty().contains("protocol"))
        assertEquals("user", messages[1].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun anthropicSerializesSystemPromptAsRequestParameter() = runBlocking {
        val transport = SystemPromptTransport("""{"content":[]}""")

        AnthropicModelClient(::apiKey, MODEL, BASE_URL, transport = transport)
            .complete(modelRequest().copy(system = "protocol\nhost append"))
            .toList()

        val body = transport.executeRequests.single().bodyJson()

        assertEquals("protocol\nhost append", body["system"]?.jsonPrimitive?.contentOrNull)
        assertEquals("user", body["messages"]?.jsonArray?.single()?.jsonObject?.get("role")?.jsonPrimitive?.contentOrNull)
    }
}

private class SystemPromptTransport(responseBody: String) : HttpTransport {
    private val executeResponse = HttpResponse(200, body = responseBody.toByteArray(Charsets.UTF_8))
    val executeRequests = mutableListOf<HttpRequest>()

    override fun stream(request: HttpRequest): Flow<HttpEvent> = flowOf(HttpEvent.Done)

    override suspend fun execute(request: HttpRequest): HttpResponse {
        executeRequests += request
        return executeResponse
    }
}

private fun modelRequest(): ModelRequest =
    ModelRequest(messages = listOf(ModelMessage(role = "user", content = "make a plan")))

private fun HttpRequest.bodyText(): String =
    requireNotNull(body).toString(Charsets.UTF_8)

private fun HttpRequest.bodyJson() =
    Json.parseToJsonElement(bodyText()).jsonObject

private fun apiKey(): String = API_KEY

private const val API_KEY = "unit-test-key"
private const val MODEL = "provider-test-model"
private const val BASE_URL = "https://provider.test/v1"

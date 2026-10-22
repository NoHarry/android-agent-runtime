package cc.noharry.agent.model

import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import cc.noharry.agent.transport.HttpTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelImage
import cc.noharry.agent.kernel.api.model.ModelTool
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelResponseMetadata

internal class FakeHttpTransport(
    private val executeResponse: HttpResponse = providerResponse("{}"),
    private val streamText: String = "",
) : HttpTransport {
    val executeRequests = mutableListOf<HttpRequest>()
    val streamRequests = mutableListOf<HttpRequest>()

    override fun stream(request: HttpRequest): Flow<HttpEvent> {
        streamRequests += request
        return flowOf(
            HttpEvent.Response(200),
            HttpEvent.Data(streamText.toByteArray(Charsets.UTF_8)),
            HttpEvent.Done,
        )
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        executeRequests += request
        return executeResponse
    }
}

internal fun providerResponse(body: String): HttpResponse =
    HttpResponse(statusCode = 200, body = body.toByteArray(Charsets.UTF_8))

internal fun contractModelRequest(): ModelRequest =
    ModelRequest(
        messages = listOf(ModelMessage(role = "user", content = "make a plan")),
        tools = listOf(ModelTool("demo_tool", """{"type":"object","properties":{"type":{"type":"string"}}}""")),
    )

internal fun contractToolLoopRequest(): ModelRequest =
    ModelRequest(
        messages = listOf(
            ModelMessage(role = "user", content = "make a plan"),
            ModelMessage(
                role = "assistant",
                content = "",
                toolCallId = "call-1",
                toolName = "demo_tool",
                toolArgumentsJson = """{"type":"plan"}""",
            ),
            ModelMessage(
                role = "tool",
                content = """{"ok":true}""",
                toolCallId = "call-1",
                toolName = "demo_tool",
            ),
        ),
        tools = listOf(ModelTool("demo_tool", "{}")),
    )

internal fun contractMultimodalRequest(): ModelRequest =
    ModelRequest(
        messages = listOf(
            ModelMessage(
                role = "user",
                content = "inspect image",
                images = listOf(
                    ModelImage(
                        bytes = byteArrayOf(1, 2, 3, 4),
                        mediaType = "image/png",
                        sourcePath = "/screenshots/one.png",
                        detail = "high",
                    ),
                ),
            ),
        ),
        tools = listOf(ModelTool("demo_tool", "{}")),
    )

internal fun eventSummary(events: List<ModelEvent>): List<String> =
    events.map { event ->
        when (event) {
            is ModelEvent.ThinkingDelta -> event.text
            is ModelEvent.TextDelta -> event.text
            is ModelEvent.ToolCall -> event.name
            is ModelEvent.UsageDelta -> "usage"
            is ModelEvent.SearchActivity -> "search"
            is ModelResponseMetadata -> "metadata"
            ModelEvent.Completed -> "Completed"
        }
    }

internal fun assertRequestBody(body: JsonObject, stream: Pair<String, Boolean>) {
    assertEquals(CONTRACT_MODEL, body["model"]?.jsonPrimitive?.contentOrNull)
    assertEquals(stream.second, body[stream.first]?.jsonPrimitive?.boolean)
    assertEquals("user", requestInputRole(body))
    assertEquals("demo_tool", toolName(body))
}

private fun requestInputRole(body: JsonObject): String? =
    body["messages"]?.jsonArray?.singleOrNull()?.jsonObject?.get("role")?.jsonPrimitive?.contentOrNull
        ?: body["input"]?.jsonArray?.singleOrNull()?.jsonObject?.get("role")?.jsonPrimitive?.contentOrNull

private fun toolName(body: JsonObject): String {
    val tool = body["tools"]?.jsonArray?.single()?.jsonObject ?: return ""
    val openAiName = tool["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
    return openAiName ?: tool["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
}

internal fun HttpRequest.contractBodyText(): String =
    requireNotNull(body).toString(Charsets.UTF_8)

internal fun HttpRequest.contractBodyJson(): JsonObject =
    Json.parseToJsonElement(contractBodyText()) as JsonObject

internal fun contractApiKey(): String = CONTRACT_API_KEY

internal const val CONTRACT_API_KEY = "unit-test-key"
internal const val CONTRACT_MODEL = "provider-test-model"
internal const val CONTRACT_BASE_URL = "https://provider.test/v1"
internal val OPENAI_RESPONSES_STREAM_TEXT = """
    data: {"type":"response.output_item.added","item":{"type":"function_call","id":"fc-1","call_id":"call-1","name":"demo_tool","arguments":""}}

    data: {"type":"response.function_call_arguments.delta","item_id":"fc-1","delta":"{\"type\":\""}

    data: {"type":"response.output_text.delta","delta":"visible"}

    data: {"type":"response.function_call_arguments.done","item_id":"fc-1","arguments":"{\"type\":\"plan\"}"}

    data: [DONE]
""".trimIndent()
internal val OPENAI_RESPONSES_RESPONSE_BODY = """
    {
      "output":[
        {"type":"message","content":[{"type":"output_text","text":"visible text"}]},
        {"type":"function_call","call_id":"call-1","name":"demo_tool","arguments":"{\"type\":\"plan\"}"}
      ]
    }
""".trimIndent()

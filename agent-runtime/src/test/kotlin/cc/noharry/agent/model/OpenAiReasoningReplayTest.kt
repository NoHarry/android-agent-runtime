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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelRef
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelTool

class OpenAiReasoningReplayTest {
    @Test
    fun openAiSerializesReasoningContentForDeepSeekToolLoopFollowUpMessages() = runBlocking {
        val transport = ReasoningReplayTransport()

        // DeepSeek default variant is "none" (thinking off). Explicitly enable thinking
        // so tool-loop follow-ups replay reasoning_content.
        OpenAiModelClient(::apiKey, "deepseek-v4-pro", BASE_URL, transport = transport)
            .complete(toolLoopRequest(thinkingEnabled = true))
            .toList()

        val messages = transport.executeRequests.single().bodyJson()["messages"]?.jsonArray.orEmpty()
        val assistant = messages[1].jsonObject

        assertEquals("assistant", assistant["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("", assistant["content"]?.jsonPrimitive?.contentOrNull)
        assertEquals(REASONING, assistant["reasoning_content"]?.jsonPrimitive?.contentOrNull)
        assertTrue(assistant["tool_calls"]?.jsonArray?.isNotEmpty() == true)
    }

    @Test
    fun openAiDoesNotSerializeReasoningContentForGenericModelNames() = runBlocking {
        val transport = ReasoningReplayTransport()

        OpenAiModelClient(::apiKey, "gpt-generic-thinking", BASE_URL, transport = transport)
            .complete(toolLoopRequest())
            .toList()

        val messages = transport.executeRequests.single().bodyJson()["messages"]?.jsonArray.orEmpty()
        val assistant = messages[1].jsonObject

        assertFalse("reasoning_content" in assistant)
    }

    @Test
    fun openAiUsesMinimalReasoningPlaceholderForBlankDeepSeekToolLoopMessages() = runBlocking {
        val transport = ReasoningReplayTransport()

        OpenAiModelClient(::apiKey, "deepseek-v4-pro", BASE_URL, transport = transport)
            .complete(dedupedToolLoopRequest(thinkingEnabled = true))
            .toList()

        val messages = transport.executeRequests.single().bodyJson()["messages"]?.jsonArray.orEmpty()
        val firstAssistant = messages[1].jsonObject
        val secondAssistant = messages[3].jsonObject

        assertEquals(REASONING, firstAssistant["reasoning_content"]?.jsonPrimitive?.contentOrNull)
        assertEquals(".", secondAssistant["reasoning_content"]?.jsonPrimitive?.contentOrNull)
        assertEquals("", secondAssistant["content"]?.jsonPrimitive?.contentOrNull)
        assertTrue(secondAssistant["tool_calls"]?.jsonArray?.isNotEmpty() == true)
    }

    @Test
    fun openAiDoesNotSerializeReasoningContentWhenDeepSeekThinkingDisabled() = runBlocking {
        val transport = ReasoningReplayTransport()

        OpenAiModelClient(::apiKey, "deepseek-v4-pro", BASE_URL, transport = transport)
            .complete(
                toolLoopRequest().copy(
                    modelRef = ModelRef("openai", "deepseek-v4-pro", "none"),
                ),
            )
            .toList()

        val body = transport.executeRequests.single().bodyJson()
        val thinking = body["thinking"]?.jsonObject
        val messages = body["messages"]?.jsonArray.orEmpty()
        val assistant = messages[1].jsonObject

        assertEquals("disabled", thinking?.get("type")?.jsonPrimitive?.contentOrNull)
        assertFalse("reasoning_content" in assistant)
    }
}

private class ReasoningReplayTransport : HttpTransport {
    val executeRequests = mutableListOf<HttpRequest>()

    override fun stream(request: HttpRequest): Flow<HttpEvent> =
        flowOf(HttpEvent.Done)

    override suspend fun execute(request: HttpRequest): HttpResponse {
        executeRequests += request
        return HttpResponse(200, body = """{"choices":[{"message":{}}]}""".toByteArray(Charsets.UTF_8))
    }
}

private fun toolLoopRequest(thinkingEnabled: Boolean = false): ModelRequest =
    ModelRequest(
        messages = listOf(
            ModelMessage(role = "user", content = "make a plan"),
            ModelMessage(
                role = "assistant",
                content = REASONING,
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
        modelRef = if (thinkingEnabled) {
            ModelRef("openai", "deepseek-v4-pro", "high")
        } else {
            null
        },
    )

private fun dedupedToolLoopRequest(thinkingEnabled: Boolean = false): ModelRequest =
    ModelRequest(
        messages = listOf(
            ModelMessage(role = "user", content = "make a plan"),
            ModelMessage(
                role = "assistant",
                content = REASONING,
                toolCallId = "call-1",
                toolName = "first_tool",
                toolArgumentsJson = "{}",
            ),
            ModelMessage(
                role = "tool",
                content = """{"ok":true}""",
                toolCallId = "call-1",
                toolName = "first_tool",
            ),
            ModelMessage(
                role = "assistant",
                content = "",
                toolCallId = "call-2",
                toolName = "second_tool",
                toolArgumentsJson = "{}",
            ),
            ModelMessage(
                role = "tool",
                content = """{"ok":true}""",
                toolCallId = "call-2",
                toolName = "second_tool",
            ),
        ),
        tools = listOf(
            ModelTool("first_tool", "{}"),
            ModelTool("second_tool", "{}"),
        ),
        modelRef = if (thinkingEnabled) {
            ModelRef("openai", "deepseek-v4-pro", "high")
        } else {
            null
        },
    )

private fun HttpRequest.bodyJson(): JsonObject =
    Json.parseToJsonElement(requireNotNull(body).toString(Charsets.UTF_8)) as JsonObject

private fun apiKey(): String = API_KEY

private const val API_KEY = "unit-test-key"
private const val BASE_URL = "https://provider.test/v1"
private const val REASONING = "Need to inspect the tool catalog."

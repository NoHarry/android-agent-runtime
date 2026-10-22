package cc.noharry.agent.model

import cc.noharry.agent.kernel.api.model.ModelImage
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelTool
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tool screenshots reach each provider in the shape the deepseek-flash probe accepted. */
class ToolResultImageSerializationTest {
    @Test
    fun anthropicPutsToolImagesInsideToolResult() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse("""{"content":[]}"""))

        AnthropicModelClient(::contractApiKey, CONTRACT_MODEL, CONTRACT_BASE_URL, transport = transport)
            .complete(toolImageRequest())
            .toList()

        val messages = transport.executeRequests.single().contractBodyJson()["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        val toolResult = messages[2].jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_result", toolResult.text("type"))
        assertEquals("call-1", toolResult.text("tool_use_id"))
        val blocks = toolResult["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "image"), blocks.map { it.text("type") })
        assertEquals(TOOL_RESULT_TEXT, blocks[0].text("text"))
        val source = blocks[1]["source"]!!.jsonObject
        assertEquals("base64", source.text("type"))
        assertEquals("image/jpeg", source.text("media_type"))
        assertEquals("AQIDBA==", source.text("data"))
    }

    @Test
    fun openAiChatKeepsToolTextAndFollowsWithUserImages() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse("""{"choices":[{"message":{}}]}"""))

        OpenAiModelClient(::contractApiKey, CONTRACT_MODEL, CONTRACT_BASE_URL, transport = transport)
            .complete(toolImageRequest())
            .toList()

        val messages = transport.executeRequests.single().contractBodyJson()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "tool", "user"), messages.map { it.text("role") })
        assertEquals(TOOL_RESULT_TEXT, messages[2].text("content"))
        val parts = messages[3]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("Attached image(s) from tool result call-1:", parts[0].text("text"))
        assertEquals("image_url", parts[1].text("type"))
        assertEquals("data:image/jpeg;base64,AQIDBA==", parts[1]["image_url"]!!.jsonObject.text("url"))
    }

    @Test
    fun openAiResponsesFollowsFunctionOutputWithInputImage() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse(OPENAI_RESPONSES_RESPONSE_BODY))

        OpenAiModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            endpoint = "responses",
            transport = transport,
        ).complete(toolImageRequest()).toList()

        val input = transport.executeRequests.single().contractBodyJson()["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals("function_call_output", input[2].text("type"))
        assertTrue(input[2].toString().contains(TOOL_RESULT_TEXT.replace("\"", "\\\"")))
        val follow = input[3]
        assertEquals("user", follow.text("role"))
        val parts = follow["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("input_image", parts[1].text("type"))
        assertEquals("data:image/jpeg;base64,AQIDBA==", parts[1].text("image_url"))
    }

    @Test
    fun toolResultWithoutImagesKeepsThePlainShape() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse("""{"choices":[{"message":{}}]}"""))

        OpenAiModelClient(::contractApiKey, CONTRACT_MODEL, CONTRACT_BASE_URL, transport = transport)
            .complete(contractToolLoopRequest())
            .toList()

        val roles = transport.executeRequests.single().contractBodyJson()["messages"]!!.jsonArray
            .map { it.jsonObject.text("role") }
        assertEquals(listOf("user", "assistant", "tool"), roles)
    }
}

private fun toolImageRequest(): ModelRequest =
    ModelRequest(
        messages = listOf(
            ModelMessage(role = "user", content = "tap next"),
            ModelMessage(
                role = "assistant",
                content = "",
                toolCallId = "call-1",
                toolName = "android_observe",
                toolArgumentsJson = "{}",
            ),
            ModelMessage(
                role = "tool",
                content = TOOL_RESULT_TEXT,
                toolCallId = "call-1",
                toolName = "android_observe",
                images = listOf(ModelImage(byteArrayOf(1, 2, 3, 4), "image/jpeg", "android-use/obs_1.jpg")),
            ),
        ),
        tools = listOf(ModelTool("android_observe", "{}")),
    )

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private const val TOOL_RESULT_TEXT = """{"observationId":"obs_1"}"""

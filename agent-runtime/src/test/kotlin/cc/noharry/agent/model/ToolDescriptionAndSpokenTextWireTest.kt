@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.model

import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A backend tool's description goes with its definition, and what the model said alongside a call goes back to it. */
class ToolDescriptionAndSpokenTextWireTest {
    private val request = ModelRequest(
        messages = listOf(
            ModelMessage(role = "user", content = "我有点冷"),
            ModelMessage(
                role = "assistant", content = "", toolCallId = "call_1", toolName = "mcp__car__set_climate",
                toolArgumentsJson = """{"temperature":28}""", assistantText = "帮你调高一点。",
            ),
            ModelMessage(role = "tool", content = """{"ok":true}""", toolCallId = "call_1", toolName = "mcp__car__set_climate"),
        ),
        tools = listOf(ModelTool("mcp__car__set_climate", """{"type":"object"}""", description = "设置空调温度。"), ModelTool("finish", "{}")),
    )

    @Test fun chatCompletionsSendsTheDescriptionAndTheSpokenTextAsContent() {
        val body = openAiRequest(CONTRACT_API_KEY, CONTRACT_BASE_URL, "gpt-4o-mini", request).contractBodyJson()
        val tools = (body.getValue("tools") as JsonArray).map { (it as JsonObject).getValue("function") as JsonObject }
        assertEquals("设置空调温度。", tools[0].getValue("description").jsonPrimitive.content)
        assertNull(tools[1]["description"])
        val call = (body.getValue("messages") as JsonArray)[1] as JsonObject
        assertEquals("帮你调高一点。", call.getValue("content").jsonPrimitive.content)
    }

    @Test fun anthropicSendsTheDescriptionAndATextBlockBeforeTheToolUse() {
        val body = anthropicRequest(CONTRACT_API_KEY, CONTRACT_BASE_URL, "claude-sonnet", request).contractBodyJson()
        val tool = (body.getValue("tools") as JsonArray)[0] as JsonObject
        assertEquals("设置空调温度。", tool.getValue("description").jsonPrimitive.content)
        val blocks = (((body.getValue("messages") as JsonArray)[1] as JsonObject).getValue("content") as JsonArray).map { it as JsonObject }
        assertEquals(listOf("text", "tool_use"), blocks.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("帮你调高一点。", blocks[0].getValue("text").jsonPrimitive.content)
    }
}

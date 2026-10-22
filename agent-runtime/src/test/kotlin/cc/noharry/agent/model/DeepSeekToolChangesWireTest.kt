@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.model

import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekToolChangesWireTest {
    private val request = ModelRequest(
        messages = listOf(
            ModelMessage(role = "user", content = "把座椅加热开到2档"),
            ModelMessage(role = "system", content = "From the app: context"),
            ModelMessage(role = "system", content = "", toolAdditions = listOf("mcp__car__set_seat_heat")),
        ),
        tools = listOf(ModelTool("finish", "{}"), ModelTool("mcp__car__set_seat_heat", "{}", deferred = true)),
    )

    @Test fun anthropicWireDeclaresDeferredToolsAndLoadsThemInOneSystemUpdate() {
        val http = anthropicRequest(CONTRACT_API_KEY, CONTRACT_BASE_URL, "deepseek-flash", request)
        val body = http.contractBodyJson()
        val tools = body.getValue("tools") as JsonArray
        assertNull((tools[0] as JsonObject)["defer_loading"])
        assertEquals("true", (tools[1] as JsonObject).getValue("defer_loading").jsonPrimitive.content)
        // The context and the tool load go out as one system message after the user turn.
        val messages = body.getValue("messages") as JsonArray
        assertEquals(listOf("user", "system"), messages.map { (it as JsonObject).getValue("role").jsonPrimitive.content })
        val update = ((messages[1] as JsonObject).getValue("content") as JsonArray).map { it as JsonObject }
        assertEquals("From the app: context", update[0].getValue("text").jsonPrimitive.content)
        assertEquals("tool_addition", update[1].getValue("type").jsonPrimitive.content)
        assertEquals("mcp__car__set_seat_heat", ((update[1].getValue("tool") as JsonObject).getValue("name")).jsonPrimitive.content)
        assertEquals("mid-conversation-tool-changes-2026-07-01", http.headers["anthropic-beta"])
    }

    @Test fun aLoadNamingAToolTheRequestDoesNotDeclareIsLeftOut() {
        val undeclared = request.copy(tools = request.tools.take(1))
        val messages = anthropicRequest(CONTRACT_API_KEY, CONTRACT_BASE_URL, "deepseek-flash", undeclared)
            .contractBodyJson().getValue("messages") as JsonArray
        // Only the context text is left, sent as a plain system update.
        assertEquals("From the app: context", (messages[1] as JsonObject).getValue("content").jsonPrimitive.content)
        // A system message that only loaded that tool disappears.
        val loadOnly = request.copy(messages = request.messages - request.messages[1], tools = request.tools.take(1))
        val body = anthropicRequest(CONTRACT_API_KEY, CONTRACT_BASE_URL, "deepseek-flash", loadOnly).contractBodyJson()
        assertEquals(1, (body.getValue("messages") as JsonArray).size)
    }

    @Test fun wiresWithoutToolChangesDropTheLoadAndSendTheToolAsUsual() {
        val body = openAiRequest(CONTRACT_API_KEY, CONTRACT_BASE_URL, "deepseek-flash", request).contractBodyJson()
        val messages = body.getValue("messages") as JsonArray
        assertFalse(messages.any { (it as JsonObject)["content"]?.jsonPrimitive?.content == "" })
        assertTrue(body.toString().contains("mcp__car__set_seat_heat"))
        assertFalse(body.toString().contains("defer_loading"))
        assertNull(anthropicRequest(CONTRACT_API_KEY, CONTRACT_BASE_URL, "deepseek-flash", request.copy(
            messages = request.messages.take(1), tools = request.tools.take(1),
        )).headers["anthropic-beta"])
    }
}

@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.model.ModelMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredToolsTest {
    private val activated = listOf("mcp__car__set_temp", "mcp__car__seat_heat", "mcp__ui__show_card").map { name ->
        ToolActivationRecord(toolName = name, schemaJson = """{"type":"object"}""", metadata = ToolActivationMetadata(), description = name)
    }

    @Test fun onlyToolsWhoseSchemaTheConversationDoesNotShowAreAnnounced() {
        val ready = ModelMessage(role = "user", content = "$DEFERRED_TOOLS_HEADER\n- mcp__ui__show_card: card\n  schema: {}")
        val search = ModelMessage(
            role = "tool",
            content = """{"ok":true,"matches":[{"name":"mcp__car__set_temp","originalName":"set_temp","description":"x","score":1.0,"via":"call_tool","schema":{}}]}""",
            toolCallId = "c1",
        )

        assertEquals(setOf("mcp__ui__show_card", "mcp__car__set_temp"), listOf(ready, search).toolsWithSchemaShown())
        val announced = deferredToolsMessage(activated, listOf(ready, search))!!.content
        assertTrue(announced.contains("- mcp__car__seat_heat: "))
        assertTrue(!announced.contains("set_temp") && !announced.contains("show_card"))
    }

    @Test fun afterCompactionFoldedTheShowingMessagesAwayTheToolsAreAnnouncedAgain() {
        val checkpoint = ModelMessage(role = "user", content = "$CHECKPOINT_PREAMBLE\n\n<compacted-summary>\n- used mcp__car__set_temp\n</compacted-summary>")

        assertEquals(3, deferredToolsMessage(activated, listOf(checkpoint))!!.content.lines().count { it.startsWith("- ") })
        assertNull(deferredToolsMessage(emptyList(), listOf(checkpoint)))
    }
}

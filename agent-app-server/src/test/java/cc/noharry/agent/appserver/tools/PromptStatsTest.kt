package cc.noharry.agent.appserver.tools

import cc.noharry.agent.appserver.protocol.FindingCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The size and tool groups of the last prompt sent, measured from the model request's body. */
class PromptStatsTest {
    @Test
    fun theLastPromptSentIsMeasuredFromTheRequestBody() {
        val system = "Model-driven harness protocol v1.\n- rule\n\n" +
            "Tool domains (use search_tools to discover specific tools by keyword):\n- device (320 tools): 打开地图\n- other (7 tools): 看屏幕\n\n" +
            "Context paths (read_file to access):\n- /AGENTS.md — rules\n\nHost instructions:\nYou are Notes."
        val anthropic = """{"model":"m","system":${kotlinx.serialization.json.JsonPrimitive(system)},"messages":[]}"""

        LastPrompt.record(anthropic)
        val stats = LastPrompt.stats!!

        assertEquals(system.substringBefore("\n\nHost instructions:").toByteArray().size, stats.staticBytes)
        assertEquals(2, stats.toolGroups)
        assertFalse(stats.shortened)
        assertEquals(FindingCode.TOOLS_PROMPT_STATS.name, stats.finding().code)
    }

    @Test
    fun openAiSystemMessagesAndShortenedIndexesAreRead() {
        val system = "Model-driven harness protocol v1.\n\nTool domains (use search_tools to discover specific tools by keyword):\n" +
            "- carcontrol (169 tools)\n- cmd (49 tools)\n- and 6 more domains (60 tools)"
        val body = """{"messages":[{"role":"system","content":${kotlinx.serialization.json.JsonPrimitive(system)}},{"role":"user","content":"hi"}]}"""

        val stats = PromptStats.of(systemPromptOf(body)!!)!!

        assertEquals(2, stats.toolGroups)
        assertTrue(stats.shortened)
        assertEquals(FindingCode.TOOLS_PROMPT_STATS_SHORTENED.name, stats.finding().code)
        assertNull("a request that is not a run (a summary, a model list) is not a run's prompt", PromptStats.of("You read logs."))
        assertNull(systemPromptOf("not json"))
    }
}

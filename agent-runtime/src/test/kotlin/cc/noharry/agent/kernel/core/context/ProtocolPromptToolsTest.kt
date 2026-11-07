package cc.noharry.agent.kernel.core.context

import cc.noharry.agent.runtime.ProtocolRules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The protocol prompt explains only the native tools the runtime offers. */
class ProtocolPromptToolsTest {
    @Test fun byDefaultWebFetchDoesNotChooseASearchProvider() {
        val text = BootstrapPromptProvider.build().text
        assertTrue(text.contains("web_fetch returns extracted page text"))
        assertFalse(text.contains("bing.com"))
    }

    @Test fun withoutWebFetchTheWebIsNotMentioned() {
        val text = BootstrapPromptProvider.build(tools = ProtocolTools(webFetch = false)).text
        assertFalse(text.contains("web_fetch"))
        assertFalse(text.contains("bing.com"))
    }

    @Test fun withWebSearchTheModelSearchesWithItNotThroughBing() {
        val text = BootstrapPromptProvider.build(tools = ProtocolTools(webSearch = true)).text
        assertTrue(text.contains("Search the web with web_search"))
        assertFalse(text.contains("bing.com"))
    }

    @Test fun disabledWritesAndTodoAreNotExplained() {
        val text = BootstrapPromptProvider.build(
            tools = ProtocolTools(disabled = setOf("write_file", "edit_file", "update_todo")),
        ).text
        assertFalse(text.contains("Workspace writes"))
        assertFalse(text.contains("update_todo"))
        assertTrue(text.contains("finish(response)"))
    }

    @Test
    fun theDefaultProtocolSectionStaysWordForWordAndAHostsRulesReplaceIt() {
        // The prompt is cached while it stays the same: these words change only on purpose.
        val expected = listOf(
            "Model-driven harness protocol v1.",
            "- Use the provided tools directly and keep backend tools as the primary work when they are present.",
            "- Search with search_tools(query) to discover backend tools by keyword. Call matches marked callable directly; a match that comes with its schema is called through call_tool(name, arguments). Neither needs a schema.json read.",
            "- Issue independent tool calls together in the same round.",
            "- For multi-step tasks, maintain update_todo and emit it in the same round as the related backend or workspace calls, including the first useful business call.",
            "- Prefer finish(response) as the explicit completion signal and call it exactly once when the useful result is ready. A finish with outcome success closes the remaining checklist items, so do not spend a round only to mark todos completed.",
            "- Read skill or context files only when the compact catalog is insufficient for the current task.",
            "- Workspace writes are limited to /scratch and /memory through write_file or edit_file.",
            "- web_fetch returns extracted page text for HTML. Do not refetch the same URL with a larger max_bytes.",
            "- Read only the context you need for the current task; do not enumerate everything.",
        ).joinToString("\n")
        assertEquals(expected, BootstrapPromptProvider.build().text)
        val own = BootstrapPromptProvider.build(protocolRules = ProtocolRules { listOf("Use the tools.") }).text
        assertEquals("Use the tools.", own)
        // No lines leave the section out rather than opening the prompt with a blank line.
        assertEquals("Host instructions:\nBe brief.", BootstrapPromptProvider.build("Be brief.", protocolRules = ProtocolRules { emptyList() }).text)
    }
}

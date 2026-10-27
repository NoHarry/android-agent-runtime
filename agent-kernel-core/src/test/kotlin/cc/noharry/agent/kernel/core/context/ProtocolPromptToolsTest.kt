package cc.noharry.agent.kernel.core.context

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The protocol prompt explains only the native tools the runtime offers. */
class ProtocolPromptToolsTest {
    @Test fun byDefaultWebFetchAndBingSearchAreExplained() {
        val text = BootstrapPromptProvider.build().text
        assertTrue(text.contains("web_fetch returns extracted page text"))
        assertTrue(text.contains("bing.com"))
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
}

package cc.noharry.agent.appserver.protocol.view

import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PagesTest {
    @Test
    fun aDoneDrawingNamesItsPageAndTitle() {
        val result = """{"ok":true,"page":"drawn-pages/c2Vzcw/page-mh3k-abcd.html","title":"三款对比","message":"已显示"}"""
        assertEquals(ShownPage("drawn-pages/c2Vzcw/page-mh3k-abcd.html", "三款对比"), call("mcp__pages__show_html", result).shownPage())
        assertEquals("drawn-pages/c2Vzcw/page-mh3k-abcd.html", call("show_html", "ok=true\nresult=$result").shownPage()?.reference)
    }

    @Test
    fun otherCallsAndReferencesOutsideThePagesShowNoPage() {
        assertNull(call("mcp__pages__show_html", """{"ok":false,"code":"PAGE_INVALID"}""").shownPage())
        assertNull(call("mcp__pages__show_html", """{"ok":true,"page":"drawn-pages/../secrets.html"}""").shownPage())
        assertNull(call("show_card", """{"ok":true,"page":"drawn-pages/a/b.html"}""").shownPage())
        assertNull(call("show_html", """{"ok":true,"page":"drawn-pages/a/b.html"}""", ToolCallStatus.RUNNING).shownPage())
    }

    private fun call(name: String, result: String, status: ToolCallStatus = ToolCallStatus.DONE) =
        ConversationItem.ToolCallItem(1, toolName = name, argumentsJson = null, resultJson = result, status = status, startedAt = 0L)
}

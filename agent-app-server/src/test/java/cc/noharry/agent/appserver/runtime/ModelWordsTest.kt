package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.appserver.mcp.ChineseMcpMessages
import cc.noharry.agent.appserver.model.appSystemPrompt
import cc.noharry.agent.mcp.McpClientMessages
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelWordsTest {
    private val monday = ZonedDateTime.of(2026, 10, 19, 11, 35, 0, 0, ZoneId.of("Asia/Shanghai"))

    @Test
    fun aPersonaWithoutALanguageLeavesTheReplyInTheUsersLanguageAndSaysNothingInChinese() {
        val words = ModelWords.forLanguage(null)
        val rules = appSystemPrompt("", words = words)

        assertTrue(rules.contains("a concise report of its result in the user's language"))
        assertTrue(rules.contains("short reply (yes, no, go on, that one)"))
        assertFalse(rules.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
        assertEquals("", words.thinkLine)
        assertEquals("Now: 2026-10-19 Mon 11:35", words.clock(monday))
        // A device whose locale writes other digits still gives the model these.
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
            assertEquals("Now: 2026-10-19 Mon 11:35", words.clock(monday))
        } finally {
            Locale.setDefault(before)
        }
        assertTrue(words.lateAnswer("Where to?", "home").startsWith("About your earlier question"))
        assertSame(McpClientMessages.Default, words.mcp)
        val prompt = runtimePrompt("", emptyList(), emptyList(), words = words)
        assertFalse(prompt.runContext.contains(THINK_IN_CHINESE))
    }

    @Test
    fun aPersonaInAnotherLanguageIsAnsweredAndThoughtInIt() {
        val words = ModelWords.forLanguage("de")

        assertTrue(appSystemPrompt("", words = words).contains("in German"))
        assertEquals("Think through this turn in German.", words.thinkLine)
        assertTrue(runtimePrompt("", emptyList(), emptyList(), words = words).runContext.endsWith(words.thinkLine))
    }

    @Test
    fun aChinesePersonaGetsTheChineseWords() {
        for (tag in listOf("zh-CN", "zh", "zh-Hant-TW", "zh_CN")) assertSame(ModelWords.CHINESE, ModelWords.forLanguage(tag))
        val words = ModelWords.CHINESE

        assertEquals("现在：2026-10-19 周一 11:35", words.clock(monday))
        assertEquals(THINK_IN_CHINESE, words.thinkLine)
        assertEquals("关于你之前的问题「去哪？」，我的回答是：回家", words.lateAnswer("去哪？", "回家"))
        assertSame(ChineseMcpMessages, words.mcp)
        val rules = appSystemPrompt("", words = words)
        assertTrue(rules.contains("Then give only a concise Chinese report of its result;") && rules.contains("response is concise Chinese, with"))
    }

    // The plugins' cues are checked in their own modules (MemoryWordsTest, PlanWordsTest).

}

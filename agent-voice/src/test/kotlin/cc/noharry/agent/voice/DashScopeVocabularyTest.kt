package cc.noharry.agent.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashScopeVocabularyTest {
    @Test
    fun theRunTaskNamesTheHotwordListOnlyWhenThereIsOne() {
        fun parameters(vocabularyId: String?) = Json.parseToJsonElement(
            dashScopeRunTask("t", "qwen-audio-3.0-asr-flash-streaming", emptyList(), vocabularyId),
        ).jsonObject["payload"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals("vocab-agentvoice-1", parameters("vocab-agentvoice-1")["vocabulary_id"]!!.jsonPrimitive.content)
        assertFalse("vocabulary_id" in parameters(null))
        assertFalse("vocabulary_id" in parameters(" "))
    }

    @Test
    fun wordsBecomeWeightedEntriesInTheirLanguage() {
        val entries = vocabularyEntries(listOf("助手", " 你好助手 ", "助手", "", "CarPlay"))
        assertEquals(3, entries.size)
        val first = entries[0].jsonObject
        assertEquals("助手", first["text"]!!.jsonPrimitive.content)
        assertEquals("zh", first["lang"]!!.jsonPrimitive.content)
        assertEquals("4", first["weight"]!!.jsonPrimitive.content)
        assertEquals("en", entries[2].jsonObject["lang"]!!.jsonPrimitive.content)
    }

    @Test
    fun overlongHotwordsAreRefused() {
        assertNull(hotwordProblem("你好助手"))
        assertTrue(hotwordProblem("一二三四五六七八九十一二三四五六")!!.contains("15"))
        assertTrue(hotwordProblem("one two three four five six seven eight")!!.contains("7"))
        assertTrue(hotwordProblem(" ") != null)
    }
}

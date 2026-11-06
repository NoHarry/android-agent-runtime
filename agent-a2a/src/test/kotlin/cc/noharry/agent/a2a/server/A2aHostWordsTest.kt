package cc.noharry.agent.a2a.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class A2aHostWordsTest {
    @Test
    fun theHostWordsTheCardAndTheStatuses() {
        val config = A2aServerConfig(
            description = "Answers in Chinese",
            skillDescription = "Runs a request with the car's tools",
            messages = A2aServerMessages(accepted = "已接收文字请求"),
        )

        val card = agentCard(config, 8766)
        val skill = (card["skills"] as JsonArray).single() as JsonObject
        val task = A2aTask(A2aRequest("t1", "c1", "hi"), streaming = false, messages = config.messages)

        assertEquals("Answers in Chinese", card["description"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Runs a request with the car's tools", skill["description"]?.jsonPrimitive?.contentOrNull)
        assertTrue(task.snapshot().toString().contains("已接收文字请求"))
    }

    @Test
    fun withoutWordsOfItsOwnTheServerSpeaksEnglish() {
        val task = A2aTask(A2aRequest("t1", "c1", "hi"), streaming = false)

        assertTrue(task.snapshot().toString().contains("Text request received"))
        assertTrue(agentCard(A2aServerConfig(), 8766).toString().none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
    }
}

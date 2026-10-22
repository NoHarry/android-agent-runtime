package cc.noharry.agent.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashScopeProtocolTest {
    @Test
    fun runTaskStreamsPcmAtSixteenKilohertz() {
        val root = Json.parseToJsonElement(dashScopeRunTask("t-1", "paraformer-realtime-v2", listOf("zh", "en"))).jsonObject
        val header = root["header"]!!.jsonObject
        val payload = root["payload"]!!.jsonObject
        val parameters = payload["parameters"]!!.jsonObject

        assertEquals("run-task", header["action"]!!.jsonPrimitive.content)
        assertEquals("duplex", header["streaming"]!!.jsonPrimitive.content)
        assertEquals("recognition", payload["function"]!!.jsonPrimitive.content)
        assertEquals("paraformer-realtime-v2", payload["model"]!!.jsonPrimitive.content)
        assertEquals("pcm", parameters["format"]!!.jsonPrimitive.content)
        assertEquals(16_000, parameters["sample_rate"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, parameters["language_hints"]!!.jsonArray.size)
    }

    @Test
    fun qwenAudioModelsDetectTheLanguageThemselves() {
        val model = DashScopeAsrModel.QWEN_AUDIO_3_0_FLASH
        val parameters = Json.parseToJsonElement(dashScopeRunTask("t", model.id, model.languageHints)).jsonObject["payload"]!!
            .jsonObject["parameters"]!!.jsonObject

        assertEquals("qwen-audio-3.0-asr-flash-streaming", model.id)
        assertEquals(null, parameters["language_hints"])
        assertEquals(DashScopeAsrModel.PARAFORMER_REALTIME_V2, DashScopeAsrModel.of("some-typed-model"))
        assertEquals("3.1 was dropped; a saved 3.1 falls back", DashScopeAsrModel.PARAFORMER_REALTIME_V2, DashScopeAsrModel.of("qwen-audio-3.1-asr-flash-streaming"))
    }

    @Test
    fun finishTaskNamesTheSameTask() {
        val header = Json.parseToJsonElement(dashScopeFinishTask("t-1")).jsonObject["header"]!!.jsonObject

        assertEquals("finish-task", header["action"]!!.jsonPrimitive.content)
        assertEquals("t-1", header["task_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun partialsAndFinalsComeFromSentenceEnd() {
        assertEquals(DashScopeEvent.Result("导航去", final = false), parseDashScopeEvent(result("导航去", end = false)))
        assertEquals(DashScopeEvent.Result("导航去广州塔。", final = true), parseDashScopeEvent(result("导航去广州塔。", end = true)))
    }

    @Test
    fun heartbeatsAndUnknownEventsAreIgnored() {
        assertNull(parseDashScopeEvent(result("", end = false, heartbeat = true)))
        assertNull(parseDashScopeEvent("""{"header":{"event":"something-new"}}"""))
        assertNull(parseDashScopeEvent("not json"))
    }

    @Test
    fun lifecycleEvents() {
        assertEquals(DashScopeEvent.Started, parseDashScopeEvent("""{"header":{"task_id":"t","event":"task-started","attributes":{}},"payload":{}}"""))
        assertEquals(DashScopeEvent.Finished, parseDashScopeEvent("""{"header":{"task_id":"t","event":"task-finished"},"payload":{"output":{}}}"""))
        assertEquals(
            DashScopeEvent.Failed("CLIENT_ERROR", "request timeout after 23 seconds."),
            parseDashScopeEvent(
                """{"header":{"task_id":"t","event":"task-failed","error_code":"CLIENT_ERROR","error_message":"request timeout after 23 seconds."},"payload":{}}""",
            ),
        )
    }

    private fun result(text: String, end: Boolean, heartbeat: Boolean = false): String =
        """{"header":{"task_id":"t","event":"result-generated","attributes":{}},"payload":{"output":{"sentence":""" +
            """{"begin_time":170,"end_time":null,"text":"$text","heartbeat":$heartbeat,"sentence_end":$end,"words":[]}},"usage":null}}"""
}

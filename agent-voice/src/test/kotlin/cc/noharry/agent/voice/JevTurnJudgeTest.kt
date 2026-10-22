package cc.noharry.agent.voice

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class JevTurnJudgeTest {
    private val snapshot = VoiceTurnSnapshot(
        version = 3,
        transcript = "导航去广州塔",
        trajectory = listOf("导航去" to 600L),
        turnElapsedMillis = 1_400,
        silenceMillis = 500,
        asrFinal = true,
    )

    @Test
    fun theBodyAsksThreeNoulsAboutTheLiveText() {
        val history = (1..8).map { VoiceHistoryMessage(if (it % 2 == 0) "assistant" else "user", "m$it") }
        val body = Json.parseToJsonElement(jevTurnBody("jev-1.13.0", VoiceJudgeRequest(snapshot, history))).jsonObject

        assertEquals("jev-1.13.0", body["model"]!!.jsonPrimitive.content)
        val questions = body["questions"]!!.jsonObject
        assertEquals(setOf("turn_complete", "holding_floor", "addressed_to_agent"), questions.keys)
        questions.values.forEach { question ->
            assertEquals("noul", question.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(setOf("true", "false"), question.jsonObject["criteria"]!!.jsonObject.keys)
        }
        val state = body["state"]!!.jsonObject
        assertEquals("导航去广州塔", state["current_user_transcript"]!!.jsonPrimitive.content)
        assertEquals(500, state["silence_since_last_word_ms"]!!.jsonPrimitive.content.toInt())
        assertEquals("m8", state["assistant_last_message"]!!.jsonPrimitive.content)
        assertEquals(6, state["conversation_history"]!!.jsonArray.size)
        assertEquals(600, state["transcript_trajectory"]!!.jsonArray[0].jsonObject["ms_ago"]!!.jsonPrimitive.content.toInt())
        assertEquals("listening", state["assistant_state"]!!.jsonPrimitive.content)
    }

    @Test
    fun silenceIsAbsentWhileTheMicHearsSpeech() {
        val body = jevTurnBody("m", VoiceJudgeRequest(snapshot.copy(silenceMillis = null, trajectory = emptyList()), emptyList()))
        val state = Json.parseToJsonElement(body).jsonObject["state"]!!.jsonObject

        assertFalse("silence_since_last_word_ms" in state)
        assertFalse("transcript_trajectory" in state)
        assertEquals("(none yet)", state["assistant_last_message"]!!.jsonPrimitive.content)
    }

    @Test
    fun whileTheAssistantSpeaksJevIsAlsoAskedWhetherTheWordsCutIn() {
        val speaking = jevTurnBody("m", VoiceJudgeRequest(snapshot, emptyList(), ASSISTANT_SPEAKING, "正在为你导航"))
        val listening = jevTurnBody("m", VoiceJudgeRequest(snapshot, emptyList()))
        val body = Json.parseToJsonElement(speaking).jsonObject

        assertTrue("taking_floor" in body["questions"]!!.jsonObject)
        assertEquals("正在为你导航", body["state"]!!.jsonObject["assistant_current_speech"]!!.jsonPrimitive.content)
        assertFalse("taking_floor" in Json.parseToJsonElement(listening).jsonObject["questions"]!!.jsonObject)
    }

    @Test
    fun parsesTakingFloorWhenAsked() {
        val answer = ANSWER.replace("}}}", "},\"taking_floor\":{\"noul\":0.77}}}")

        assertEquals(0.77, parseJevTurn(answer).takingFloor!!, 0.0)
        assertEquals(null, parseJevTurn(ANSWER).takingFloor)
    }

    @Test
    fun whileTheAgentWorksJevIsAskedWhetherTheWordsReplaceItsTask() {
        val working = jevTurnBody("m", VoiceJudgeRequest(snapshot, emptyList(), ASSISTANT_THINKING, currentTask = "导航去广州塔"))
        val body = Json.parseToJsonElement(working).jsonObject

        assertTrue("replaces_task" in body["questions"]!!.jsonObject)
        assertEquals("导航去广州塔", body["state"]!!.jsonObject["assistant_current_task"]!!.jsonPrimitive.content)
        val answer = ANSWER.replace("}}}", "},\"replaces_task\":{\"noul\":0.3}}}")
        assertEquals(0.3, parseJevTurn(answer).replacesTask!!, 0.0)
    }

    @Test
    fun theTasksProgressGoesWithItSoJevReadsTheWordsAgainstIt() {
        val progress = "工具：search_songs（调用完成）\n已说：我先找几首适合开车的歌"
        val working = VoiceJudgeRequest(snapshot, emptyList(), ASSISTANT_THINKING, currentTask = "推荐一首歌", taskProgress = progress)
        val state = Json.parseToJsonElement(jevTurnBody("m", working)).jsonObject["state"]!!.jsonObject
        assertEquals(progress, state["assistant_task_progress"]!!.jsonPrimitive.content)

        val idle = VoiceJudgeRequest(snapshot, emptyList(), ASSISTANT_LISTENING, taskProgress = progress)
        val idleState = Json.parseToJsonElement(jevTurnBody("m", idle)).jsonObject["state"]!!.jsonObject
        assertTrue("no task, no progress", "assistant_task_progress" !in idleState)
    }

    @Test
    fun onlyADismissibleSessionIsAskedWhetherTheWordsEndIt() {
        val woken = Json.parseToJsonElement(jevTurnBody("m", VoiceJudgeRequest(snapshot, emptyList(), canEndSession = true))).jsonObject
        val tapped = Json.parseToJsonElement(jevTurnBody("m", VoiceJudgeRequest(snapshot, emptyList()))).jsonObject

        assertTrue("ends_session" in woken["questions"]!!.jsonObject)
        assertTrue("ends_session" !in tapped["questions"]!!.jsonObject)
        assertEquals(0.92, parseJevTurn(ANSWER.replace("}}}", "},\"ends_session\":{\"noul\":0.92}}}")).endsSession!!, 0.0)
        assertEquals(null, parseJevTurn(ANSWER).endsSession)
    }

    @Test
    fun smallTalkIsScoredOnlyWhenTheHostAsksAndTheAgentIsIdle() {
        fun questions(request: VoiceJudgeRequest) = Json.parseToJsonElement(jevTurnBody("m", request)).jsonObject["questions"]!!.jsonObject
        val asked = questions(VoiceJudgeRequest(snapshot, emptyList(), askSmallTalk = true))
        val working = questions(VoiceJudgeRequest(snapshot, emptyList(), ASSISTANT_THINKING, currentTask = "导航去广州塔", askSmallTalk = true))

        assertTrue("small_talk" in asked)
        assertTrue("words during a task belong to it, never to the companion" , "small_talk" !in working)
        assertTrue("small_talk" !in questions(VoiceJudgeRequest(snapshot, emptyList())))
        assertEquals(0.81, parseJevTurn(ANSWER.replace("}}}", "},\"small_talk\":{\"noul\":0.81}}}")).smallTalk!!, 0.0)
        assertEquals(null, parseJevTurn(ANSWER).smallTalk)
    }

    @Test
    fun aCalledTurnTellsJevTheSpeakerJustCalledTheAssistant() {
        val identity = VoiceAssistantIdentity(name = "助手", wakePhrase = "你好助手")
        val called = Json.parseToJsonElement(
            jevTurnBody("m", VoiceJudgeRequest(snapshot.copy(called = true), emptyList()), identity = identity),
        ).jsonObject
        val plain = Json.parseToJsonElement(jevTurnBody("m", VoiceJudgeRequest(snapshot, emptyList()))).jsonObject

        assertTrue(called["state"]!!.jsonObject["addressing_notes"]!!.jsonPrimitive.content.contains("「你好助手」"))
        assertTrue(called.toString().contains("an AI assistant called 助手 on a tablet"))
        assertFalse("addressing_notes" in plain["state"]!!.jsonObject)
    }

    @Test
    fun anUnnamedAssistantIsCalledTheAssistant() {
        val body = jevTurnBody("m", VoiceJudgeRequest(snapshot.copy(called = true), emptyList()))

        assertTrue(body.contains("an AI assistant on a tablet"))
        assertTrue(body.contains("said its wake word or pressed its mic"))
        assertTrue(body.contains("anything naming it are addressed to it"))
        assertFalse(body.contains("{name}") || body.contains("{called}") || body.contains("{wake}") || body.contains("{named}"))
    }

    @Test
    fun usageIsReadWhenReported() {
        assertEquals(2_100 to 57, parseJevUsage(ANSWER.dropLast(1) + ""","usage":{"input_tokens":2100,"output_tokens":57}}"""))
        assertEquals(0 to 0, parseJevUsage(ANSWER))
    }

    @Test
    fun parsesTheThreeScores() {
        val judgment = parseJevTurn(ANSWER, latencyMillis = 320)

        assertEquals(VoiceJudgment(0.91, 0.05, 0.88, 320), judgment)
    }

    @Test
    fun rejectsAMissingOrOutOfRangeScore() {
        assertThrows(IOException::class.java) { parseJevTurn("""{"answers":{"turn_complete":{"noul":0.5}}}""") }
        assertThrows(IOException::class.java) {
            parseJevTurn(ANSWER.replace("0.88", "1.5"))
        }
    }

    @Test
    fun postsWithTheBearerKey() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(ANSWER))
            server.start()
            val judge = JevTurnJudge(server.url("/v1/systemone").toString(), "secret", "jev-1.13.0", timeoutMillis = 5_000)

            val judgment = judge.judge(VoiceJudgeRequest(snapshot, emptyList()))

            val recorded = server.takeRequest()
            assertEquals("Bearer secret", recorded.getHeader("Authorization"))
            assertTrue(recorded.body.readUtf8().contains("\"addressed_to_agent\""))
            assertEquals(0.88, judgment.addressedToAgent, 0.0)
        }
    }

    @Test
    fun anHttpErrorFailsTheJudgment() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.start()
            val judge = JevTurnJudge(server.url("/").toString(), "bad", "jev-1.13.0", timeoutMillis = 5_000)

            val failure = runCatching { judge.judge(VoiceJudgeRequest(snapshot, emptyList())) }.exceptionOrNull()
            assertTrue(failure is IOException && failure.message!!.contains("401"))
        }
    }

    private companion object {
        const val ANSWER = """{"answers":{"turn_complete":{"noul":0.91},"holding_floor":{"noul":0.05},"addressed_to_agent":{"noul":0.88}}}"""
    }
}

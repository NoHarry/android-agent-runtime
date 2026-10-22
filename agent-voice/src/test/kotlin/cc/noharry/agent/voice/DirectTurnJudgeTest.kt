package cc.noharry.agent.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Without Jev: every closed sentence goes to the assistant, and only plain words cut in or end the session. */
class DirectTurnJudgeTest {
    private val judge = DirectTurnJudge()
    private val thresholds = VoiceTurnThresholds()

    private fun judged(text: String, final: Boolean = true, state: String = ASSISTANT_LISTENING, task: String = "", canEnd: Boolean = false) =
        runBlocking {
            judge.judge(
                VoiceJudgeRequest(
                    VoiceTurnSnapshot(1, text, emptyList(), 2_000, 400, asrFinal = final),
                    emptyList(),
                    state,
                    currentTask = task,
                    canEndSession = canEnd,
                ),
            )
        }

    @Test
    fun aClosedSentenceIsOverAndForTheAssistant() {
        val done = judged("明天深圳天气怎么样")
        assertEquals(VoiceSegmentLabel.AGENT, VoiceTurnPolicy.label(thresholds, done))
        assertTrue(VoiceTurnPolicy.shouldEnd(thresholds, done, silenceMillis = thresholds.confirmQuietMillis))
        // Not closed yet: it ends only after the longer unsettled wait.
        val open = judged("明天深圳天气怎么样", final = false)
        assertFalse(VoiceTurnPolicy.shouldEnd(thresholds, open, silenceMillis = thresholds.confirmQuietMillis, recognizerSettled = false))
        assertTrue(VoiceTurnPolicy.shouldEnd(thresholds, open, silenceMillis = thresholds.unsettledQuietMillis, recognizerSettled = false))
    }

    @Test
    fun aSentenceThatLeadsOnWaitsForMore() {
        listOf("帮我查一下天气然后", "先放首歌，", "定个闹钟和").forEach { text ->
            assertFalse(text, VoiceTurnPolicy.shouldEnd(thresholds, judged(text), silenceMillis = 2_000))
        }
    }

    @Test
    fun onlyPlainWordsCutInAndTheTaskIsAddedTo() {
        assertNull(judged("你好").takingFloor)
        assertEquals(1.0, judged("等一下", state = ASSISTANT_SPEAKING).takingFloor!!, 0.0)
        assertEquals(0.0, judged("今天的天气是晴天", state = ASSISTANT_SPEAKING).takingFloor!!, 0.0)
        assertEquals(0.0, judged("顺便查下明天", task = "规划行程").replacesTask!!, 0.0)
        assertNull(judged("顺便查下明天").replacesTask)
        assertEquals(1.0, judged("好了再见", canEnd = true).endsSession!!, 0.0)
        assertEquals(0.0, judged("再见的英文怎么说呀助手", canEnd = true).endsSession!!, 0.0)
        assertNull(judged("再见").endsSession)
    }
}

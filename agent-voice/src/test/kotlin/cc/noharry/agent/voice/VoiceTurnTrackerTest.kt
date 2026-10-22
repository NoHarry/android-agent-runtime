package cc.noharry.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTurnTrackerTest {
    private val tracker = VoiceTurnTracker()

    @Test
    fun asksJevOnceTheTextHasSettledForTheDebounce() {
        tracker.onMic(true, 0)
        tracker.onPartial("导航去", 0)

        assertNull(tracker.poll(100))
        val judge = tracker.poll(JUDGE_DEBOUNCE_MILLIS) as VoiceTurnAction.Judge
        assertEquals("导航去", judge.snapshot.transcript)
        assertNull(judge.snapshot.silenceMillis)
        assertNull("one request in flight at a time", tracker.poll(400))
    }

    @Test
    fun aCompleteTurnForTheAgentEndsAfterTheQuietWindow() {
        val version = speak("导航去广州塔", at = 0)
        tracker.onMic(true, 100)
        tracker.onMic(false, 200)
        tracker.onJudgment(version, VoiceJudgment(turnComplete = 0.9, holdingFloor = 0.1, addressedToAgent = 0.95))

        // Quiet counts from the last voiced frame (100 ms).
        assertNull("the mic must be quiet for the confirm window", tracker.poll(400))
        val end = tracker.poll(560) as VoiceTurnAction.End
        assertEquals("导航去广州塔", end.text)
        assertEquals(VoiceSegmentLabel.AGENT, end.label)
        assertEquals(VoiceEndReason.JUDGED, end.reason)
        assertEquals("", tracker.transcript)
    }

    @Test
    fun aPauseMidSentenceDoesNotCutTheRequest() {
        val half = speak("推荐一首适合开车听", at = 0)
        tracker.onMic(false, 200)
        val unsure = VoiceJudgment(turnComplete = 0.68, holdingFloor = 0.04, addressedToAgent = 0.94)
        tracker.onJudgment(half, unsure)
        // The quiet passes a milestone, so Jev is asked again about the same words: same answer.
        val again = tracker.poll(1_000)
        assertTrue("the recognizer has not closed the sentence", again !is VoiceTurnAction.End)
        (again as? VoiceTurnAction.Judge)?.let { tracker.onJudgment(it.snapshot.version, unsure) }
        assertTrue(tracker.poll(1_050) !is VoiceTurnAction.End)

        tracker.onFinal("推荐一首适合开车听的歌。", 1_100)
        val whole = (tracker.poll(1_100 + JUDGE_DEBOUNCE_MILLIS) as VoiceTurnAction.Judge).snapshot
        assertEquals("推荐一首适合开车听的歌。", whole.transcript)
        tracker.onJudgment(whole.version, VoiceJudgment(turnComplete = 0.95, holdingFloor = 0.0, addressedToAgent = 0.95))
        assertEquals("推荐一首适合开车听的歌。", (tracker.poll(1_700) as VoiceTurnAction.End).text)
    }

    @Test
    fun sideTalkEndsUnsubmittedAsSide() {
        val version = speak("晚上吃什么", at = 0)
        tracker.onMic(false, 200)
        tracker.onJudgment(version, VoiceJudgment(turnComplete = 0.9, holdingFloor = 0.0, addressedToAgent = 0.1))

        assertEquals(VoiceSegmentLabel.SIDE, (tracker.poll(600) as VoiceTurnAction.End).label)
    }

    @Test
    fun aRequestForTimeHoldsTheFloorUntilTheLongerCap() {
        val version = speak("等一下我想想", at = 0)
        tracker.onMic(false, 200)
        tracker.onJudgment(version, VoiceJudgment(turnComplete = 0.9, holdingFloor = 0.8, addressedToAgent = 0.9))

        assertTrue(tracker.poll(5_300) !is VoiceTurnAction.End)
        val end = tracker.poll(12_300) as VoiceTurnAction.End
        assertEquals(VoiceEndReason.SILENCE_CAP, end.reason)
    }

    @Test
    fun theBarRelaxesWithSilence() {
        val version = speak("打开窗帘", at = 0)
        tracker.onMic(false, 200)
        tracker.onJudgment(version, VoiceJudgment(turnComplete = 0.6, holdingFloor = 0.0, addressedToAgent = 0.9))

        assertTrue(tracker.poll(600) !is VoiceTurnAction.End)
        // 0.72 → 0.55 over 3 s: 0.6 clears the bar after about 2.1 s of quiet.
        assertTrue(tracker.poll(2_600) is VoiceTurnAction.End)
    }

    @Test
    fun anAnswerForOlderTextNeverEndsTheTurn() {
        tracker.onMic(true, 0)
        tracker.onPartial("帮我把空调", 0)
        val first = (tracker.poll(200) as VoiceTurnAction.Judge).snapshot.version
        tracker.onPartial("帮我把空调调到二十四度", 300)
        tracker.onMic(false, 400)
        tracker.onJudgment(first, VoiceJudgment(turnComplete = 0.95, holdingFloor = 0.0, addressedToAgent = 0.9))

        val next = tracker.poll(800)
        assertTrue("stale answer must trigger a fresh question, not an end", next is VoiceTurnAction.Judge)
        assertEquals("帮我把空调调到二十四度", (next as VoiceTurnAction.Judge).snapshot.transcript)
        assertEquals(listOf("帮我把空调" to 800L), next.snapshot.trajectory)
    }

    @Test
    fun longerQuietAsksAgainAtEachMilestone() {
        val version = speak("那个", at = 0)
        tracker.onMic(true, 100)
        tracker.onMic(false, 200)
        tracker.onJudgment(version, VoiceJudgment(turnComplete = 0.2, holdingFloor = 0.3, addressedToAgent = 0.7))

        assertNull(tracker.poll(400))
        val again = tracker.poll(560) as VoiceTurnAction.Judge
        assertEquals(460L, again.snapshot.silenceMillis)
        assertNull("one request in flight at a time", tracker.poll(700))
        tracker.onJudgment(again.snapshot.version, VoiceJudgment(0.2, 0.3, 0.7))
        assertNull(tracker.poll(900))
        assertTrue(tracker.poll(1_010) is VoiceTurnAction.Judge)
    }

    @Test
    fun withoutAnyAnswerSilenceEndsTheTurnAsUncertain() {
        speak("嗯", at = 0)
        tracker.onMic(false, 200)

        val end = tracker.poll(5_200) as VoiceTurnAction.End
        assertEquals(VoiceSegmentLabel.UNCERTAIN, end.label)
        assertEquals(VoiceEndReason.SILENCE_CAP, end.reason)
    }

    @Test
    fun aFailedJudgmentEndsAsUncertainAfterAShortQuiet() {
        val version = speak("打开音乐", at = 0)
        tracker.onMic(false, 200)
        tracker.onJudgmentFailed(version)

        assertNull(tracker.poll(1_000))
        val end = tracker.poll(1_400) as VoiceTurnAction.End
        assertEquals(VoiceEndReason.JUDGE_FAILED, end.reason)
        assertEquals(VoiceSegmentLabel.UNCERTAIN, end.label)
    }

    @Test
    fun whileBusyTheTurnIsDroppedAndNothingIsHeard() {
        speak("打开", at = 0)
        tracker.setBusy(true, 300)
        tracker.onPartial("这是播报的回声", 400)

        assertEquals("", tracker.transcript)
        assertNull(tracker.poll(10_000))
        tracker.setBusy(false, 10_000)
        tracker.onPartial("下一首", 10_100)
        assertEquals("下一首", tracker.transcript)
    }

    @Test
    fun continuousSideTalkIsCutAtTheRecognizersSegmentBoundary() {
        val version = speak("你因为睡眠不足", at = 0)
        tracker.onJudgment(version, VoiceJudgment(turnComplete = 0.1, holdingFloor = 0.1, addressedToAgent = 0.2))
        tracker.onMic(true, 700)

        assertTrue("no cut mid-segment", tracker.poll(700) !is VoiceTurnAction.End)
        tracker.onFinal("你因为睡眠不足", 800)
        val end = tracker.poll(800) as VoiceTurnAction.End
        assertEquals(VoiceEndReason.SIDE_TALK, end.reason)
        assertEquals(VoiceSegmentLabel.SIDE, end.label)
    }

    @Test
    fun aTurnThatNeverGoesQuietEndsAtTheLengthCap() {
        tracker.onMic(true, 0)
        tracker.onPartial("一", 0)
        var now = 0L
        while (now < 14_900) {
            now += 100
            tracker.onMic(true, now)
            tracker.onPartial("一".repeat((now / 100).toInt() + 1), now)
            assertTrue(tracker.poll(now) !is VoiceTurnAction.End)
        }
        val end = tracker.poll(15_000) as VoiceTurnAction.End
        assertEquals(VoiceEndReason.LENGTH_CAP, end.reason)
    }

    @Test
    fun aLongRequestForTheAgentIsNotCutAtTheChatterCap() {
        val text = "我老婆在深圳湾万象城逛街，老大在南山书城学美术，老二在香蜜湖学芭蕾，老三在科技园学乐高，老四在深圳湾体育中心打羽毛球"
        val version = speak(text, at = 0)
        tracker.onJudgment(version, VoiceJudgment(turnComplete = 0.3, holdingFloor = 0.6, addressedToAgent = 0.9))
        tracker.onMic(true, 25_000)
        assertTrue("still talking to the assistant at 25 s", tracker.poll(25_000) !is VoiceTurnAction.End)

        tracker.onPartial("$text，先去接老大，帮我规划一条导航路线", 26_000)
        tracker.onMic(true, 26_000)
        val asked = tracker.poll(26_000 + JUDGE_DEBOUNCE_MILLIS) as VoiceTurnAction.Judge
        tracker.onJudgment(asked.snapshot.version, VoiceJudgment(turnComplete = 0.95, holdingFloor = 0.0, addressedToAgent = 0.95))
        tracker.onMic(false, 26_500)
        val end = tracker.poll(27_000) as VoiceTurnAction.End
        assertEquals(VoiceEndReason.JUDGED, end.reason)
        assertTrue(end.text.startsWith("我老婆在深圳湾万象城逛街") && end.text.endsWith("帮我规划一条导航路线"))
    }

    @Test
    fun aClosedSegmentOfOnlyADismissalEndsTheTurnWithoutJevOrQuiet() {
        // 15:59 on the bench: residue in front, talk nearby keeping the mic on, and Jev slow to answer.
        tracker.dismissal = { it.trimEnd('。') == "退出" }
        tracker.onMic(true, 0)
        tracker.onFinal("我一", 0)
        tracker.onPartial("退出", 300)
        assertFalse("an open segment is not enough", tracker.poll(300) is VoiceTurnAction.End)

        tracker.onFinal("退出。", 600)
        val end = tracker.poll(600) as VoiceTurnAction.End
        assertEquals(VoiceEndReason.DISMISSED, end.reason)
        assertEquals("退出。", end.text)
        assertEquals("", tracker.transcript)
    }

    @Test
    fun aDismissalJevReadsInAClosedSegmentEndsWhileTalkGoesOn() {
        tracker.onMic(true, 0)
        tracker.onFinal("我一退出。", 0)
        val asked = tracker.poll(JUDGE_DEBOUNCE_MILLIS) as VoiceTurnAction.Judge
        tracker.onJudgment(asked.snapshot.version, VoiceJudgment(turnComplete = 0.45, holdingFloor = 0.05, addressedToAgent = 0.8, endsSession = 0.75))

        // The mic never went quiet, and the end-of-turn bar is not met.
        val end = tracker.poll(JUDGE_DEBOUNCE_MILLIS + 100) as VoiceTurnAction.End
        assertEquals(VoiceEndReason.JUDGED, end.reason)
        assertEquals("我一退出。", end.text)
    }

    @Test
    fun aDismissalInsideALongerSegmentIsLeftToJev() {
        tracker.dismissal = { it.trimEnd('。') == "退出" }
        tracker.onMic(true, 0)
        tracker.onFinal("导航回家然后退出。", 0)

        assertFalse(tracker.poll(0) is VoiceTurnAction.End)
        assertEquals("导航回家然后退出。", tracker.transcript)
    }

    @Test
    fun aLengthCappedTurnWithoutACompleteSentenceIsNotHandedOver() {
        val version = speak("到第二天我就发现这个心魔好像在调戏", at = 0)
        tracker.onJudgment(version, VoiceJudgment(turnComplete = 0.2, holdingFloor = 0.1, addressedToAgent = 0.9))
        tracker.onMic(true, 60_000)

        val end = tracker.poll(60_000) as VoiceTurnAction.End
        assertEquals(VoiceEndReason.LENGTH_CAP, end.reason)
        assertEquals(VoiceSegmentLabel.UNCERTAIN, end.label)
    }

    @Test
    fun whileWordsKeepComingJevIsAskedAtMostEveryInterval() {
        var asked = 0
        for (step in 0..30) {
            val now = step * 100L
            tracker.onMic(true, now)
            // A new word every 300 ms, as a streaming recognizer delivers them.
            tracker.onPartial("字".repeat(step / 3 + 1), now)
            val action = tracker.poll(now)
            if (action is VoiceTurnAction.Judge) {
                asked++
                tracker.onJudgment(action.snapshot.version, VoiceJudgment(0.1, 0.1, 0.9))
            }
        }
        // 3 s of growing text: at most one question per 600 ms.
        assertTrue("asked $asked times", asked in 4..6)
    }

    @Test
    fun aTurnCutMidSegmentIsNotRepeatedByTheNextOne() {
        tracker.onMic(true, 0)
        tracker.onPartial("还没起来干活", 0)
        var now = 0L
        while (now < 15_000) {
            now += 100
            tracker.onMic(true, now)
        }
        assertEquals("还没起来干活", (tracker.poll(15_000) as VoiceTurnAction.End).text)

        tracker.onPartial("还没起来干活他还要写专栏", 15_100)
        assertEquals("他还要写专栏", tracker.transcript)
        tracker.onFinal("还没起来干活他还要写专栏好了", 15_200)
        assertEquals("他还要写专栏好了", tracker.transcript)
        tracker.onPartial("还没起来", 15_300)
        assertEquals("a closed segment no longer strips anything", "他还要写专栏好了还没起来", tracker.transcript)
    }

    @Test
    fun aCloudRecognizersRevisedFinalIsNotHandedOverTwice() {
        val version = speak("阿洁，导航去深圳北站", at = 0)
        tracker.onMic(true, 100)
        tracker.onMic(false, 200)
        tracker.onJudgment(version, VoiceJudgment(turnComplete = 0.9, holdingFloor = 0.0, addressedToAgent = 0.96))
        assertEquals("阿洁，导航去深圳北站", (tracker.poll(600) as VoiceTurnAction.End).text)

        // The segment closes later with a homophone fixed and punctuation added: the same words, already handed over.
        tracker.onFinal("阿杰，导航去深圳北站。", 900)
        assertEquals("", tracker.transcript)
        assertNull(tracker.poll(2_000))
    }

    @Test
    fun strippingTheConsumedStartToleratesRevisionsButNotNewWords() {
        assertEquals("然后找停车场", stripConsumed("阿杰，导航去深圳北站，然后找停车场", "阿洁，导航去深圳北站"))
        assertEquals("", stripConsumed("阿杰，导航去深圳北站。", "阿洁，导航去深圳北站"))
        assertEquals("打开窗帘", stripConsumed("打开窗帘", "导航去深圳北站"))
        assertEquals("他还要写专栏", stripConsumed("还没起来干活他还要写专栏", "还没起来干活"))
    }

    @Test
    fun finalsAccumulateAcrossSegments() {
        tracker.onFinal("导航去广州塔", 0)
        tracker.onPartial("然后", 100)
        assertEquals("导航去广州塔然后", tracker.transcript)
        tracker.onFinal("然后找个停车场", 200)
        assertEquals("导航去广州塔然后找个停车场", tracker.transcript)
    }

    @Test
    fun latinWordsKeepTheirSpace() {
        assertEquals("play music", joinSpeech("play", "music"))
        assertEquals("打开窗帘", joinSpeech("打开", "窗帘"))
        assertEquals("打开WIFI", joinSpeech("打开", "WIFI"))
    }

    /** Speaks [text] at [at] and lets the tracker ask Jev; returns the asked version. */
    @Test
    fun aLengthCappedTurnForTheAgentHandsOverItsLastCompleteSentence() {
        val version = speak("你不是plus吗？哦，plus对plus，运气半小时。你说那个pancake，你还没跟我说哪家最好吃。哎，那个pancake，你还没跟我", at = 0)
        tracker.onJudgment(version, VoiceJudgment(turnComplete = 0.16, holdingFloor = 0.13, addressedToAgent = 0.83))
        tracker.onMic(true, 60_000)

        val end = tracker.poll(60_000) as VoiceTurnAction.End
        assertEquals(VoiceEndReason.LENGTH_CAP, end.reason)
        assertEquals(VoiceSegmentLabel.AGENT, end.label)
        assertEquals("你说那个pancake，你还没跟我说哪家最好吃。", end.text)
    }

    @Test
    fun aShortLastQuestionKeepsTheSentenceItFollows() {
        val version = speak("这这这，你都不能用，这都被他都被干掉了，语音都不能用。你让Grok去看一下，嗯，那个时候的天气怎么样，还有。交通怎么样？", at = 0)
        tracker.onJudgment(version, VoiceJudgment(turnComplete = 0.58, holdingFloor = 0.09, addressedToAgent = 0.84))
        tracker.onMic(true, 60_000)

        val end = tracker.poll(60_000) as VoiceTurnAction.End
        assertEquals(VoiceSegmentLabel.AGENT, end.label)
        assertEquals("你让Grok去看一下，嗯，那个时候的天气怎么样，还有。交通怎么样？", end.text)
    }

    @Test
    fun aLengthCappedTurnNotClearlyForTheAgentIsKeptWhole() {
        val text = "感觉觉得他。不是我说，这几家你觉得哪家最好吃啊"
        val version = speak(text, at = 0)
        tracker.onJudgment(version, VoiceJudgment(turnComplete = 0.69, holdingFloor = 0.08, addressedToAgent = 0.51))
        tracker.onMic(true, 15_000)

        val end = tracker.poll(15_000) as VoiceTurnAction.End
        assertEquals(VoiceSegmentLabel.UNCERTAIN, end.label)
        assertEquals(text, end.text)
    }

    @Test
    fun theFirstTurnAfterACallIsForTheAgentUnlessClearlySideTalk() {
        tracker.onCalled(0)
        val first = speak("你觉得哪家最好吃？", at = 2_000)
        assertTrue(lastSnapshot.called)
        tracker.onJudgment(first, VoiceJudgment(turnComplete = 0.9, holdingFloor = 0.03, addressedToAgent = 0.58))
        tracker.onMic(false, 2_200)
        val end = tracker.poll(2_600) as VoiceTurnAction.End
        assertEquals(VoiceSegmentLabel.AGENT, end.label)

        // The call was answered: the next turn needs the usual bar again.
        val second = speak("那哪家离这最近？", at = 5_000)
        assertFalse(lastSnapshot.called)
        tracker.onJudgment(second, VoiceJudgment(turnComplete = 0.9, holdingFloor = 0.03, addressedToAgent = 0.58))
        tracker.onMic(false, 5_200)
        assertEquals(VoiceSegmentLabel.UNCERTAIN, (tracker.poll(5_600) as VoiceTurnAction.End).label)
    }

    @Test
    fun aCalledTurnJevNeverAnsweredGoesToTheAgentButALoneFillerDoesNot() {
        // Jev timed out: the answer never came, and five seconds of quiet end the turn.
        tracker.onCalled(0)
        speak("看一下华强北附近有什么好吃？", at = 2_000)
        tracker.onMic(false, 4_000)
        val silent = tracker.poll(9_000) as VoiceTurnAction.End
        assertEquals(VoiceEndReason.SILENCE_CAP, silent.reason)
        assertEquals(VoiceSegmentLabel.AGENT, silent.label)

        // Jev failed outright.
        tracker.onCalled(10_000)
        val failed = speak("打开窗帘", at = 11_000)
        tracker.onMic(false, 11_200)
        tracker.onJudgmentFailed(failed)
        val gaveUp = tracker.poll(12_400) as VoiceTurnAction.End
        assertEquals(VoiceEndReason.JUDGE_FAILED, gaveUp.reason)
        assertEquals(VoiceSegmentLabel.AGENT, gaveUp.label)

        tracker.onCalled(20_000)
        speak("呃，", at = 21_000)
        tracker.onMic(false, 21_200)
        assertEquals(VoiceSegmentLabel.UNCERTAIN, (tracker.poll(26_200) as VoiceTurnAction.End).label)

        // Not called: words Jev never judged may be side talk, so they stay uncertain.
        speak("看一下华强北附近有什么好吃？", at = 40_000)
        tracker.onMic(false, 40_200)
        assertEquals(VoiceSegmentLabel.UNCERTAIN, (tracker.poll(45_200) as VoiceTurnAction.End).label)
    }

    @Test
    fun aCallGoesStaleIfNobodySpeaksSoon() {
        tracker.onCalled(0)
        speak("你觉得哪家最好吃？", at = CALLED_TURN_MILLIS + 1)
        assertFalse(lastSnapshot.called)
    }

    @Test
    fun theWakePhraseMidTurnEndsWhatCameBeforeAndOpensACalledTurn() {
        tracker.wakePhrase = TEST_WAKE_HEARD
        tracker.onMic(true, 0)
        tracker.onPartial("你可能得调一下那个能量，让它消掉一点。你好助手", 0)

        val head = tracker.poll(100) as VoiceTurnAction.End
        assertEquals(VoiceEndReason.WAKE_PHRASE, head.reason)
        assertEquals(VoiceSegmentLabel.UNCERTAIN, head.label)
        assertEquals("你可能得调一下那个能量，让它消掉一点。", head.text)
        assertEquals("", tracker.transcript)

        // The recognizer goes on revising the same open segment; only the words after the wake phrase are new.
        tracker.onPartial("你可能得调一下那个能量，让它消掉一点。你好助手你觉得这5家里面哪家最好吃", 1_000)
        assertEquals("你觉得这5家里面哪家最好吃", tracker.transcript)
        assertTrue((tracker.poll(1_000 + JUDGE_DEBOUNCE_MILLIS) as VoiceTurnAction.Judge).snapshot.called)
    }

    @Test
    fun theWakePhraseInAClosedSegmentKeepsOnlyWhatFollows() {
        tracker.wakePhrase = TEST_WAKE_HEARD
        tracker.onMic(true, 0)
        tracker.onFinal("你好，助手。", 0)
        tracker.onPartial("打开空调", 200)

        assertTrue("nothing before the wake phrase, so nothing ends", tracker.poll(200) !is VoiceTurnAction.End)
        assertEquals("打开空调", tracker.transcript)
    }

    private var lastSnapshot = VoiceTurnSnapshot(0, "", emptyList(), 0, null, false)

    private fun speak(text: String, at: Long): Int {
        tracker.onMic(true, at)
        tracker.onPartial(text, at)
        lastSnapshot = (tracker.poll(at + JUDGE_DEBOUNCE_MILLIS) as VoiceTurnAction.Judge).snapshot
        return lastSnapshot.version
    }
}

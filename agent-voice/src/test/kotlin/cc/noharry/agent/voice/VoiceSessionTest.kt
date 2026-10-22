package cc.noharry.agent.voice

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)
    private val dispatchers = VoiceDispatchers(dispatcher, dispatcher, dispatcher)
    private val handed = mutableListOf<String>()

    @Test
    fun aRequestToTheAgentIsHandedOverAndTheMicGoesDeaf() = test.runTest {
        val engine = ScriptedEngine(mapOf(3 to "打开", 5 to "打开窗帘"))
        val session = session(engine, addressed = 0.9)

        session.start()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(listOf("打开窗帘"), handed)
        val segment = session.state.value.segments.single()
        assertEquals(VoiceSegmentLabel.AGENT, segment.label)
        assertTrue(segment.submitted)
        assertEquals(VoiceSessionStatus.BUSY, session.state.value.status)
        assertTrue("the recognizer hears silence while busy", engine.silentFramesAfterHandOver > 0)
        session.stop()
    }

    @Test
    fun sideTalkIsMarkedAndNotHandedOver() = test.runTest {
        val session = session(ScriptedEngine(mapOf(3 to "晚上吃什么")), addressed = 0.1)

        session.start()
        advanceTimeBy(3_000)
        runCurrent()

        assertTrue(handed.isEmpty())
        assertEquals(VoiceSegmentLabel.SIDE, session.state.value.segments.single().label)
        assertEquals(VoiceSessionStatus.LISTENING, session.state.value.status)
        session.stop()
    }

    @Test
    fun theHostReleasesTheMicAfterItsTail() = test.runTest {
        val session = session(ScriptedEngine(mapOf(3 to "打开窗帘")), addressed = 0.9)
        session.start()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(VoiceSessionStatus.BUSY, session.state.value.status)

        session.setHostBusy(true)
        advanceTimeBy(500)
        session.setHostBusy(false)
        advanceTimeBy(300)
        runCurrent()
        assertEquals("still in the post-speech tail", VoiceSessionStatus.BUSY, session.state.value.status)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(VoiceSessionStatus.LISTENING, session.state.value.status)
        session.stop()
    }

    @Test
    fun aRecognizerFailureStopsTheSession() = test.runTest {
        val session = session(ScriptedEngine(emptyMap(), failAt = 2), addressed = 0.9)

        session.start()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(VoiceSessionStatus.ERROR, session.state.value.status)
        assertEquals("boom", session.state.value.message)
        assertFalse(session.state.value.micActive)
    }

    @Test
    fun fullDuplexDropsTheRepliesEchoWithoutAskingJevOrInterrupting() = test.runTest {
        var asked = 0
        var interrupted = 0
        val session = VoiceSession(
            engine = ScriptedEngine(mapOf(3 to "正在为你导航", 5 to "正在为你导航到广州塔")),
            judge = { asked++; VoiceJudgment(0.9, 0.0, 0.9, takingFloor = 0.9) },
            audio = { flow { repeat(FRAMES) { index -> delay(100); emit(frame(voiced = index in 1..6)) } } },
            fullDuplex = true,
            onInterrupt = { interrupted++ },
            onAgentTurn = { handed += it.text },
            clock = { dispatcher.scheduler.currentTime },
            dispatchers = dispatchers,
        )
        session.start()
        session.setHostState(working = false, speaking = true, speech = "好的，正在为你导航到广州塔，全程大约二十分钟。")
        advanceTimeBy(6_000)
        runCurrent()

        assertEquals(0, asked)
        assertEquals(0, interrupted)
        assertTrue(handed.isEmpty())
        assertEquals(VoiceEndReason.ECHO, session.state.value.segments.single().reason)
        assertEquals(VoiceSessionStatus.LISTENING, session.state.value.status)
        session.stop()
    }

    @Test
    fun fullDuplexStopsTheReplyOnceWhenSomeoneCutsIn() = test.runTest {
        var interrupted = 0
        val session = VoiceSession(
            engine = ScriptedEngine(mapOf(3 to "等一下", 5 to "等一下不去了")),
            judge = { VoiceJudgment(0.9, 0.0, 0.9, takingFloor = 0.9) },
            audio = { flow { repeat(FRAMES) { index -> delay(100); emit(frame(voiced = index in 1..6)) } } },
            fullDuplex = true,
            onInterrupt = { interrupted++ },
            onAgentTurn = { handed += it.text },
            clock = { dispatcher.scheduler.currentTime },
            dispatchers = dispatchers,
        )
        session.start()
        session.setHostState(working = true, speaking = true, speech = "好的，正在为你导航到广州塔。")
        advanceTimeBy(800)
        runCurrent()
        assertEquals(1, interrupted)

        session.setHostState(working = false, speaking = false)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals("one interruption per turn", 1, interrupted)
        assertEquals(listOf("等一下不去了"), handed)
        assertEquals(1, session.state.value.interruptions)
        session.stop()
    }

    @Test
    fun talkClearlyForSomeoneElseNeverStopsTheReply() = test.runTest {
        var interrupted = 0
        // A video next to the tablet: Jev read it as taking the floor, but addressed to the assistant at only 0.10.
        val session = VoiceSession(
            engine = ScriptedEngine(mapOf(2 to "然后呢", 4 to "然后呢，咱们这个坦克呀")),
            // Clearly side talk, and an unsure 0.5 on the way: neither may stop the reply.
            judge = { VoiceJudgment(0.3, 0.0, if (it.snapshot.transcript.length < 6) 0.5 else 0.1, takingFloor = 0.8) },
            audio = { flow { repeat(FRAMES) { index -> delay(100); emit(frame(voiced = index in 1..6)) } } },
            fullDuplex = true,
            onInterrupt = { interrupted++ },
            onAgentTurn = { handed += it.text },
            clock = { dispatcher.scheduler.currentTime },
            dispatchers = dispatchers,
        )
        session.start()
        session.setHostState(working = false, speaking = true, speech = "Grok Bot 回报了：明天杭州晴。")
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(0, interrupted)
        assertTrue(handed.isEmpty())
        session.stop()
    }

    @Test
    fun wordsWhileTheAgentWorksAreHandedOverAsAnAppend() = test.runTest {
        val session = VoiceSession(
            engine = ScriptedEngine(mapOf(3 to "顺便打开空调")),
            judge = { VoiceJudgment(0.95, 0.0, 0.9) },
            audio = { flow { repeat(FRAMES) { index -> delay(100); emit(frame(voiced = index in 1..6)) } } },
            fullDuplex = true,
            onAgentTurn = { handed += it.text },
            clock = { dispatcher.scheduler.currentTime },
            dispatchers = dispatchers,
        )
        session.start()
        session.setHostState(working = true, speaking = false)
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(listOf("顺便打开空调"), handed)
        assertEquals(VoiceHandOverMode.APPEND, session.state.value.segments.single().handOver)
        assertTrue("the meter moved while someone spoke", session.state.value.micLevels.isNotEmpty())
        session.stop()
    }

    @Test
    fun aNewQuestionWhileTheAgentWorksReplacesItsTask() = test.runTest {
        val modes = mutableListOf<VoiceHandOverMode>()
        var asked: VoiceJudgeRequest? = null
        val session = VoiceSession(
            engine = ScriptedEngine(mapOf(3 to "今天天气怎么样")),
            judge = { request -> asked = request; VoiceJudgment(0.95, 0.0, 0.9, replacesTask = 0.8) },
            audio = { flow { repeat(FRAMES) { index -> delay(100); emit(frame(voiced = index in 1..6)) } } },
            fullDuplex = true,
            onAgentTurn = { modes += it.mode },
            clock = { dispatcher.scheduler.currentTime },
            dispatchers = dispatchers,
        )
        session.start()
        session.setHostState(working = true, speaking = false, task = "导航去广州塔")
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(listOf(VoiceHandOverMode.REPLACE), modes)
        assertEquals("导航去广州塔", asked!!.currentTask)
        session.stop()
    }

    @Test
    fun wordsDuringTheReplyThatDoNotTakeTheFloorAreNotHandedOver() = test.runTest {
        val session = VoiceSession(
            engine = ScriptedEngine(mapOf(3 to "可以可以")),
            judge = { VoiceJudgment(0.9, 0.0, 0.9, takingFloor = 0.1) },
            audio = { flow { repeat(FRAMES) { index -> delay(100); emit(frame(voiced = index in 1..6)) } } },
            fullDuplex = true,
            onAgentTurn = { handed += it.text },
            clock = { dispatcher.scheduler.currentTime },
            dispatchers = dispatchers,
        )
        session.start()
        session.setHostState(working = false, speaking = true, speech = "好的，给你推荐三个户外活动。")
        advanceTimeBy(3_000)
        runCurrent()

        assertTrue(handed.isEmpty())
        val segment = session.state.value.segments.single()
        assertEquals(VoiceEndReason.BACKCHANNEL, segment.reason)
        assertEquals(VoiceSegmentLabel.UNCERTAIN, segment.label)
        session.stop()
    }

    @Test
    fun aWokenSessionIsDismissedByVoiceInsteadOfHandingTheWordsOver() = test.runTest {
        var dismissed = 0
        val engine = ScriptedEngine(mapOf(3 to "退下吧"))
        val session = dismissible(engine, onDismiss = { dismissed++ })

        session.start()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(1, dismissed)
        assertTrue("the words never reach the agent", handed.isEmpty())
        val segment = session.state.value.segments.single()
        assertTrue(segment.dismissed)
        assertTrue(!segment.submitted)
        session.stop()
    }

    @Test
    fun aSegmentOfOnlyADismissalEndsAWokenSessionWhileTalkGoesOnAndJevSaysNo() = test.runTest {
        var dismissed = 0
        val engine = ScriptedEngine(mapOf(3 to "我一", 7 to "退出"), finals = mapOf(5 to "我一", 9 to "退出。"))
        val session = VoiceSession(
            engine = engine,
            // Jev read 「我一退出」 as neither finished nor a dismissal.
            judge = { VoiceJudgment(0.3, 0.1, 0.45, latencyMillis = 1, endsSession = 0.3) },
            // Talk nearby never lets the microphone go quiet.
            audio = { flow { repeat(FRAMES) { delay(100); emit(frame(voiced = true)) } } },
            onAgentTurn = { handed += it.text },
            onDismiss = { dismissed++ },
            dismissal = { it.trimEnd('。') == "退出" },
            clock = { dispatcher.scheduler.currentTime },
            dispatchers = dispatchers,
        )

        session.start()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, dismissed)
        assertTrue(handed.isEmpty())
        val segment = session.state.value.segments.single()
        assertEquals(VoiceEndReason.DISMISSED, segment.reason)
        assertTrue(segment.dismissed)
        session.stop()
    }

    @Test
    fun aTappedSessionIgnoresTheDismissalScore() = test.runTest {
        val session = dismissible(ScriptedEngine(mapOf(3 to "退下吧")), onDismiss = null)

        session.start()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(listOf("退下吧"), handed)
        session.stop()
    }

    @Test
    fun theFirstTurnAfterTheCallIsHandedOverOnALowerScore() = test.runTest {
        val session = session(ScriptedEngine(mapOf(3 to "你觉得哪家最好吃")), addressed = 0.5)

        session.start()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(listOf("你觉得哪家最好吃"), handed)
        session.stop()
    }

    @Test
    fun aSessionNobodyCalledKeepsTheUsualBar() = test.runTest {
        val session = session(ScriptedEngine(mapOf(3 to "你觉得哪家最好吃")), addressed = 0.5, calledAtStart = false)

        session.start()
        advanceTimeBy(3_000)
        runCurrent()

        assertTrue(handed.isEmpty())
        assertEquals(VoiceSegmentLabel.UNCERTAIN, session.state.value.segments.single().label)
        session.stop()
    }

    @Test
    fun theEarlierExchangeReachesJev() = test.runTest {
        val requests = mutableListOf<VoiceJudgeRequest>()
        val engine = ScriptedEngine(mapOf(3 to "哪家最好吃"))
        val session = VoiceSession(
            engine = engine,
            judge = { requests += it; VoiceJudgment(0.95, 0.0, 0.9, latencyMillis = 1) },
            audio = { flow { repeat(FRAMES) { index -> delay(100); emit(frame(voiced = index in 1..6)) } } },
            onAgentTurn = { handed += it.text },
            clock = { dispatcher.scheduler.currentTime },
            dispatchers = dispatchers,
        ).also { engine.handedOver = { handed.isNotEmpty() } }

        session.recordUser("帮我找深圳好吃的 pancake")
        session.recordAssistant("找到 5 家，店名已经显示在屏幕上了")
        session.start()
        advanceTimeBy(3_000)
        runCurrent()

        val first = requests.first()
        assertEquals(listOf("user", "assistant"), first.history.map { it.role })
        assertTrue(first.snapshot.called)
        session.stop()
    }

    @Test
    fun wordsBeforeTheWakePhraseAreNotHandedOver() = test.runTest {
        val engine = ScriptedEngine(mapOf(3 to "让它消掉一点你好助手", 5 to "让它消掉一点你好助手打开窗帘"))
        val session = VoiceSession(
            engine = engine,
            judge = { VoiceJudgment(0.95, 0.0, 0.9, latencyMillis = 1) },
            audio = { flow { repeat(FRAMES) { index -> delay(100); emit(frame(voiced = index in 1..6)) } } },
            onAgentTurn = { handed += it.text },
            wakePhrase = TEST_WAKE_HEARD,
            clock = { dispatcher.scheduler.currentTime },
            dispatchers = dispatchers,
        ).also { engine.handedOver = { handed.isNotEmpty() } }

        session.start()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(listOf("打开窗帘"), handed)
        val (head, request) = session.state.value.segments
        assertEquals(VoiceEndReason.WAKE_PHRASE, head.reason)
        assertFalse(head.submitted)
        assertEquals(VoiceSegmentLabel.AGENT, request.label)
        session.stop()
    }

    @Test
    fun aNearGateKeepsQuieterTalkFromTheRecognizer() = test.runTest {
        val sounding = mutableListOf<Boolean>()
        val engine = object : StreamingAsrEngine {
            override val label = "recording"
            override val punctuates = false
            override fun start(listener: AsrListener) = Unit
            override fun accept(samples: ShortArray) {
                sounding += samples.any { it.toInt() != 0 }
            }
            override fun stop() = Unit
        }
        // A video at about -50 dBFS all along; the speaker at about -21 dBFS for frames 10–14.
        val session = VoiceSession(
            engine = engine,
            judge = { VoiceJudgment(turnComplete = 0.95, holdingFloor = 0.0, addressedToAgent = 0.9, latencyMillis = 1) },
            audio = { flow { repeat(30) { index -> delay(100); emit(level(if (index in 10..14) 3_000 else 100)) } } },
            onAgentTurn = { handed += it.text },
            nearGate = NearSpeechGate(referenceDb = -20.0),
            clock = { dispatcher.scheduler.currentTime },
            dispatchers = dispatchers,
        )

        session.start()
        advanceTimeBy(3_500)
        runCurrent()

        // One frame behind: frames 9 (onset) to 16 (fading end) reach the recognizer, at calls 10 to 17.
        assertEquals((10..17).toList(), sounding.indices.filter { sounding[it] })
        assertEquals(8, session.state.value.nearGate?.passedFrames)
        session.stop()
    }

    @Test
    fun wordsForTheAssistantFromFartherAwayAreNotHandedOver() = test.runTest {
        // Past the -30 dBFS gate at -28, but 8 dB under the caller's -20: a video's 「叫什么？」 during a song reply.
        val session = gatedSession(ScriptedEngine(mapOf(3 to "叫什么")), amplitude = 1_305)

        session.start()
        advanceTimeBy(3_000)
        runCurrent()

        assertTrue(handed.isEmpty())
        val segment = session.state.value.segments.single()
        assertEquals(VoiceEndReason.FAR_SPEECH, segment.reason)
        assertEquals(VoiceSegmentLabel.UNCERTAIN, segment.label)
        assertEquals(-28.0, segment.peakDb!!, 0.1)
        session.stop()
    }

    @Test
    fun theCallersOwnWordsReachTheCallersLevelAndAreHandedOver() = test.runTest {
        val session = gatedSession(ScriptedEngine(mapOf(3 to "打开窗帘")), amplitude = 3_000)

        session.start()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(listOf("打开窗帘"), handed)
        assertEquals(VoiceSegmentLabel.AGENT, session.state.value.segments.single().label)
        session.stop()
    }

    /** The caller woke the assistant at -20 dBFS; speech of [amplitude] on frames 1–6, silence around it. */
    private fun gatedSession(engine: ScriptedEngine, amplitude: Int) = VoiceSession(
        engine = engine,
        judge = { VoiceJudgment(turnComplete = 0.95, holdingFloor = 0.0, addressedToAgent = 0.9, latencyMillis = 1) },
        audio = { flow { repeat(FRAMES) { index -> delay(100); emit(if (index in 1..6) level(amplitude) else ShortArray(VOICE_FRAME_SAMPLES)) } } },
        onAgentTurn = { handed += it.text },
        nearGate = NearSpeechGate(referenceDb = -20.0),
        clock = { dispatcher.scheduler.currentTime },
        dispatchers = dispatchers,
    ).also { engine.handedOver = { handed.isNotEmpty() } }

    /** Jev says the words are for the agent and end the conversation. */
    private fun dismissible(engine: ScriptedEngine, onDismiss: (() -> Unit)?) = VoiceSession(
        engine = engine,
        // Jev hesitated over who 「退下吧」 was for on the tablet (0.55): the dismissal must still count.
        judge = { VoiceJudgment(0.95, 0.0, if (it.canEndSession) 0.55 else 0.9, latencyMillis = 1, endsSession = if (it.canEndSession) 0.93 else null) },
        audio = { flow { repeat(FRAMES) { index -> delay(100); emit(frame(voiced = index in 1..6)) } } },
        onAgentTurn = { handed += it.text },
        onDismiss = onDismiss,
        clock = { dispatcher.scheduler.currentTime },
        dispatchers = dispatchers,
    ).also { engine.handedOver = { handed.isNotEmpty() } }

    private fun session(engine: ScriptedEngine, addressed: Double, calledAtStart: Boolean = true) = VoiceSession(
        engine = engine,
        judge = { VoiceJudgment(turnComplete = 0.95, holdingFloor = 0.0, addressedToAgent = addressed, latencyMillis = 1) },
        audio = { flow { repeat(FRAMES) { index -> delay(100); emit(frame(voiced = index in 1..6)) } } },
        onAgentTurn = { handed += it.text },
        calledAtStart = calledAtStart,
        clock = { dispatcher.scheduler.currentTime },
        dispatchers = dispatchers,
    ).also { engine.handedOver = { handed.isNotEmpty() } }

    /** Emits a partial on the scripted frame numbers (1-based). */
    private class ScriptedEngine(
        private val partials: Map<Int, String>,
        private val failAt: Int? = null,
        private val finals: Map<Int, String> = emptyMap(),
    ) : StreamingAsrEngine {
        override val label = "scripted"
        override val punctuates = false
        private var listener: AsrListener? = null
        private var frames = 0
        var handedOver: () -> Boolean = { false }
        var silentFramesAfterHandOver = 0

        override fun start(listener: AsrListener) {
            this.listener = listener
        }

        override fun accept(samples: ShortArray) {
            frames++
            if (frames == failAt) listener?.onError("boom")
            partials[frames]?.let { listener?.onPartial(it) }
            finals[frames]?.let { listener?.onFinal(it) }
            if (handedOver() && samples.all { it.toInt() == 0 }) silentFramesAfterHandOver++
        }

        override fun stop() {
            listener = null
        }
    }

    private companion object {
        const val FRAMES = 100

        fun frame(voiced: Boolean) = if (voiced) level(3_000) else ShortArray(VOICE_FRAME_SAMPLES)

        /** A square wave of [amplitude]: 3 000 is about -21 dBFS, 100 about -50. */
        fun level(amplitude: Int) = ShortArray(VOICE_FRAME_SAMPLES) { index ->
            (if (index % 2 == 0) amplitude else -amplitude).toShort()
        }
    }
}

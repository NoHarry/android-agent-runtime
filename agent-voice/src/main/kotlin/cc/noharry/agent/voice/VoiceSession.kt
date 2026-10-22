package cc.noharry.agent.voice

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.Executors

enum class VoiceSessionStatus { IDLE, STARTING, LISTENING, BUSY, ERROR }

/** A finished stretch of speech, as shown to the user. */
data class VoiceSegment(
    val id: Long,
    val text: String,
    val label: VoiceSegmentLabel,
    val judgment: VoiceJudgment?,
    val reason: VoiceEndReason,
    val endedAtEpochMillis: Long,
    /** Handed to the host as an agent turn. */
    val submitted: Boolean,
    /** How it was handed over; null when it was not. */
    val handOver: VoiceHandOverMode? = null,
    /** The speaker dismissed the assistant with it; it ended the session instead of reaching the agent. */
    val dismissed: Boolean = false,
    /** Its loudest frame after the near-speech gate (dBFS); null without a gate. */
    val peakDb: Double? = null,
)

/** How an agent turn reaches the agent. */
enum class VoiceHandOverMode {
    /** The agent was idle: a new turn. */
    NEW_TURN,

    /** The agent was working and the words belong to its task: added to its next model request, nothing stops. */
    APPEND,

    /** The agent was working and Jev judged the words drop its task: stop it and take this as the new request. */
    REPLACE,
}

/** An agent turn for the host; [smallTalk] is Jev's small-talk score when the host asked for one. */
data class VoiceHandOver(val text: String, val mode: VoiceHandOverMode, val smallTalk: Double? = null)

data class VoiceSessionState(
    val status: VoiceSessionStatus = VoiceSessionStatus.IDLE,
    val engineLabel: String = "",
    val liveText: String = "",
    val liveJudgment: VoiceJudgment? = null,
    val micActive: Boolean = false,
    /** Recent loudness, oldest first, 0..1 each, 50 per second: the level meter. */
    val micLevels: List<Float> = emptyList(),
    val segments: List<VoiceSegment> = emptyList(),
    val judgeCount: Int = 0,
    val judgeFailures: Int = 0,
    val lastJudgeLatencyMillis: Long? = null,
    val message: String? = null,
    /** Listening on while the agent works and speaks; off means half duplex. */
    val fullDuplex: Boolean = false,
    /** Null until the microphone opened. */
    val audioEffects: VoiceAudioEffects? = null,
    val agentWorking: Boolean = false,
    val assistantSpeaking: Boolean = false,
    /** Stretches dropped locally as the assistant's own voice. */
    val echoes: Int = 0,
    val interruptions: Int = 0,
    /** The near-speech gate's work so far; null without one. */
    val nearGate: NearGateStats? = null,
)

/** Where each part runs; tests pass one test dispatcher for all. */
class VoiceDispatchers(
    val logic: CoroutineDispatcher,
    /** One thread: engines are fed in order and native recognizers stay on it. */
    val asr: CoroutineDispatcher,
    val network: CoroutineDispatcher,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() = onClose()

    companion object {
        fun create(): VoiceDispatchers {
            val asr = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "voice-asr") }.asCoroutineDispatcher()
            return VoiceDispatchers(Dispatchers.Default, asr, Dispatchers.IO, asr::close)
        }
    }
}

/**
 * One tap-to-wake listening session: microphone → recognizer → turn tracker → Jev → labelled segments. Turns
 * labelled [VoiceSegmentLabel.AGENT] go to [onAgentTurn] when [autoSubmit] is on.
 *
 * Half duplex (default): while the host reports the agent working or speaking, the recognizer gets silence and
 * nothing is judged. [fullDuplex] (echo cancellation on): listening goes on; Jev learns the assistant's state, words
 * matching what it is saying are dropped locally as echo, and words that Jev says take the floor call
 * [onInterrupt] once per turn so the host stops speaking.
 */
class VoiceSession(
    private val engine: StreamingAsrEngine,
    private val judge: VoiceTurnJudge,
    private val audio: VoiceAudioSource,
    private val thresholds: VoiceTurnThresholds = VoiceTurnThresholds(),
    private val autoSubmit: Boolean = true,
    private val historyLimit: Int = 6,
    private val fullDuplex: Boolean = false,
    private val onInterrupt: () -> Unit = {},
    private val onAgentTurn: (VoiceHandOver) -> Unit,
    /**
     * Set for a session the speaker may dismiss by voice (the overlay's wake): words that end the conversation
     * ("退下吧") come here instead of [onAgentTurn], and the host ends the session.
     */
    private val onDismiss: (() -> Unit)? = null,
    /**
     * The wake phrase as the recognizer writes it: heard mid-turn, the words before it are not handed over and the
     * words after it open a turn expected to be for the assistant. Null: no wake phrase.
     */
    private val wakePhrase: Regex? = null,
    /**
     * With [onDismiss]: words that dismiss on their own ("退出", "退下吧"), as the host reads them. A recognizer segment
     * that is only such words ends the session at once, without Jev; anything longer is Jev's to judge.
     */
    private val dismissal: ((String) -> Boolean)? = null,
    /** The session opens because the speaker called the assistant (wake word, mic button): its first turn is expected for it. */
    private val calledAtStart: Boolean = true,
    /**
     * Keeps quieter talk (someone nearby, a video) from the recognizer, the silence timer and the meter; null hears
     * everything. Fed on the recognizer's thread.
     */
    private val nearGate: NearSpeechGate? = null,
    /** Whether Jev should also score small talk (the host routes it to a companion chat service); read per turn. */
    private val askSmallTalk: () -> Boolean = { false },
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val dispatchers: VoiceDispatchers = VoiceDispatchers.create(),
) {
    private val mutableState = MutableStateFlow(VoiceSessionState())
    val state: StateFlow<VoiceSessionState> = mutableState

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val tracker = VoiceTurnTracker(thresholds).also { tracker ->
        tracker.wakePhrase = wakePhrase
        // Not the assistant's own 「再见」 coming back while it speaks.
        tracker.dismissal = dismissal?.takeIf { onDismiss != null }
            ?.let { dismisses -> { words -> dismisses(words) && !(hostSpeaking && looksLikeEcho(words, speech)) } }
    }
    private val mic = MicActivity()
    private val history = ArrayDeque<VoiceHistoryMessage>()
    private var scope: CoroutineScope? = null
    private var asrJob: Job? = null
    @Volatile private var muted = false
    private var hostWorking = false
    private var hostSpeaking = false
    private var speech = ""
    private var task = ""
    private var progress = ""
    private var interruptSent = false
    private var handedOverAt: Long? = null
    /** Each frame's level after the near-speech gate, with when it was heard: a turn's loudest frame. */
    private val heardLevels = ArrayDeque<Pair<Long, Double>>()
    private var quietAfter = 0L
    private var segmentIds = 0L

    fun start() {
        if (scope != null) return
        val sessionScope = CoroutineScope(SupervisorJob() + dispatchers.logic)
        scope = sessionScope
        mutableState.value = VoiceSessionState(status = VoiceSessionStatus.STARTING, engineLabel = engine.label, fullDuplex = fullDuplex)
        sessionScope.launch { for (event in events) handle(event) }
        sessionScope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                events.trySend(Event.Tick)
            }
        }
        asrJob = sessionScope.launch(dispatchers.asr) {
            try {
                engine.start(asrListener)
                events.trySend(Event.Started)
                val silence = ShortArray(VOICE_FRAME_SAMPLES)
                audio.frames().collect { heard ->
                    val frame = nearGate?.process(heard) ?: heard
                    val levelDb = if (nearGate != null) NearSpeechGate.frameDb(frame) else null
                    events.trySend(Event.Mic(mic.update(frame), MicActivity.levels(frame), nearGate?.stats(), levelDb))
                    engine.accept(if (muted) silence else frame)
                }
                events.trySend(Event.Failed("麦克风已停止"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                events.trySend(Event.Failed(error.message ?: error.javaClass.simpleName))
            } finally {
                runCatching { engine.stop() }
            }
        }
    }

    /** Ends listening; the recognizer and microphone are released. Segments stay visible. */
    fun stop() {
        val active = scope ?: return
        scope = null
        active.cancel()
        val job = asrJob
        if (job == null) dispatchers.close() else job.invokeOnCompletion { dispatchers.close() }
        mutableState.update {
            it.copy(
                status = if (it.status == VoiceSessionStatus.ERROR) VoiceSessionStatus.ERROR else VoiceSessionStatus.IDLE,
                liveText = "",
                liveJudgment = null,
                micActive = false,
                micLevels = emptyList(),
            )
        }
    }

    /** Agent running or speech playing, as one flag: [setHostState] without the speech text. */
    fun setHostBusy(busy: Boolean) = setHostState(working = busy, speaking = false)

    /**
     * What the host is doing: the agent [working], its reply being [speaking] (queued, synthesized or played) with
     * the text being spoken. Half duplex stays deaf through both and shortly after.
     */
    fun setHostState(working: Boolean, speaking: Boolean, speech: String = "", task: String = "", progress: String = "") {
        events.trySend(Event.HostState(working, speaking, speech, task, progress))
    }

    /** What the microphone's capture effects turned out to be. */
    fun reportAudioEffects(effects: VoiceAudioEffects) {
        mutableState.update { it.copy(audioEffects = effects) }
    }

    /** What the assistant said, for Jev's conversation context. */
    fun recordAssistant(text: String) {
        if (text.isNotBlank()) events.trySend(Event.History(VoiceHistoryMessage("assistant", text.trim())))
    }

    /** What the user asked before this session (typed, or in an earlier one), for Jev's conversation context. */
    fun recordUser(text: String) {
        if (text.isNotBlank()) events.trySend(Event.History(VoiceHistoryMessage("user", text.trim())))
    }

    private val asrListener = object : AsrListener {
        override fun onPartial(text: String) {
            events.trySend(Event.Partial(text))
        }

        override fun onFinal(text: String) {
            events.trySend(Event.Final(text))
        }

        override fun onError(message: String) {
            events.trySend(Event.Failed(message))
        }
    }

    private fun handle(event: Event) {
        val now = clock()
        when (event) {
            Event.Started -> {
                if (calledAtStart) tracker.onCalled(now)
                mutableState.update { it.copy(status = VoiceSessionStatus.LISTENING, message = null) }
            }
            is Event.Partial -> tracker.onPartial(event.text, now)
            is Event.Final -> tracker.onFinal(event.text, now)
            is Event.Mic -> {
                tracker.onMic(event.active, now)
                event.levelDb?.let { level ->
                    heardLevels.addLast(now to level)
                    while (heardLevels.first().first < now - LEVELS_LOG_MILLIS) heardLevels.removeFirst()
                }
                mutableState.update {
                    it.copy(
                        micActive = event.active,
                        micLevels = (it.micLevels + event.levels.toList()).takeLast(LEVELS_KEPT),
                        nearGate = event.gate ?: it.nearGate,
                    )
                }
            }
            is Event.JudgeDone -> judged(event)
            is Event.JudgeFailed -> {
                tracker.onJudgmentFailed(event.version)
                mutableState.update { it.copy(judgeFailures = it.judgeFailures + 1, message = "Jev：${event.message}") }
            }
            is Event.HostState -> hostChanged(event, now)
            is Event.History -> remember(event.message)
            is Event.Failed -> {
                mutableState.update { it.copy(status = VoiceSessionStatus.ERROR, message = event.message) }
                stop()
                return
            }
            Event.Tick -> Unit
        }
        updateBusy(now)
        val openedAt = tracker.openedAtMillis
        when (val action = tracker.poll(now)) {
            is VoiceTurnAction.Judge -> askJev(action.snapshot)
            is VoiceTurnAction.End -> finish(action, turnPeakDb(openedAt))
            null -> Unit
        }
        publishLive()
    }

    private fun judged(event: Event.JudgeDone) {
        tracker.onJudgment(event.version, event.judgment)
        if (event.judgment.echo) {
            mutableState.update { it.copy(echoes = it.echoes + 1) }
            return
        }
        mutableState.update { it.copy(judgeCount = it.judgeCount + 1, lastJudgeLatencyMillis = event.judgment.latencyMillis) }
        maybeInterrupt(event.judgment)
    }

    private fun hostChanged(event: Event.HostState, now: Long) {
        val busy = event.working || event.speaking
        if (busy) handedOverAt = null else if (hostWorking || hostSpeaking) quietAfter = now + BUSY_TAIL_MILLIS
        if (event.speaking && !hostSpeaking) interruptSent = false
        hostWorking = event.working
        hostSpeaking = event.speaking
        speech = event.speech
        // The assistant saying its own name is not the speaker calling it.
        tracker.wakePhrase = wakePhrase?.takeUnless { event.speaking && it.containsMatchIn(event.speech) }
        task = event.task
        progress = event.progress
        mutableState.update { it.copy(agentWorking = event.working, assistantSpeaking = event.speaking) }
    }

    private fun updateBusy(now: Long) {
        handedOverAt?.let { if (now - it > HAND_OVER_TIMEOUT_MILLIS) handedOverAt = null }
        val busy = !fullDuplex && (hostWorking || hostSpeaking || handedOverAt != null || now < quietAfter)
        if (busy == tracker.busy) return
        tracker.setBusy(busy, now)
        muted = busy
        mutableState.update {
            if (it.status == VoiceSessionStatus.LISTENING || it.status == VoiceSessionStatus.BUSY) {
                it.copy(status = if (busy) VoiceSessionStatus.BUSY else VoiceSessionStatus.LISTENING)
            } else {
                it
            }
        }
    }

    private fun askJev(snapshot: VoiceTurnSnapshot) {
        // Residual echo of the reply is answered locally: no Jev call, no interruption.
        if (hostSpeaking && looksLikeEcho(snapshot.transcript, speech)) {
            events.trySend(Event.JudgeDone(snapshot.version, VoiceJudgment(0.0, 0.0, 0.0, echo = true)))
            return
        }
        val state = when {
            hostSpeaking -> ASSISTANT_SPEAKING
            hostWorking -> ASSISTANT_THINKING
            else -> ASSISTANT_LISTENING
        }
        val request = VoiceJudgeRequest(
            snapshot,
            history.toList(),
            state,
            assistantSpeech = if (hostSpeaking) speech else "",
            currentTask = if (hostWorking) task else "",
            taskProgress = if (hostWorking) progress else "",
            canEndSession = onDismiss != null,
            askSmallTalk = !hostWorking && askSmallTalk(),
        )
        scope?.launch(dispatchers.network) {
            val event = try {
                Event.JudgeDone(snapshot.version, judge.judge(request))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Event.JudgeFailed(snapshot.version, error.message ?: error.javaClass.simpleName)
            }
            events.trySend(event)
        }
    }

    /**
     * Words for the assistant that take the floor while it speaks stop it, once per turn. Talk from farther away never
     * does, nor talk Jev does not read as for the assistant: a video's 「然后呢，咱们这个坦克呀」 scored taking the floor at
     * addressed 0.10, and a video's half-sentence 「不…」 got past 0.4 on the way; both stopped a delegation report on the
     * tablet (owner 2026-10-12).
     */
    private fun maybeInterrupt(judgment: VoiceJudgment) {
        val taking = judgment.takingFloor ?: return
        if (!fullDuplex || !hostSpeaking || interruptSent || taking < thresholds.bargeIn) return
        if (judgment.addressedToAgent < thresholds.addresseeAgent) return
        if (isFar(turnPeakDb(tracker.openedAtMillis))) return
        interruptSent = true
        mutableState.update { it.copy(interruptions = it.interruptions + 1) }
        onInterrupt()
    }

    private fun finish(ended: VoiceTurnAction.End, peakDb: Double?) {
        interruptSent = false
        // Judged while the reply played and not taking the floor: a backchannel or residue, so it is not handed over.
        val taking = ended.judgment?.takingFloor
        val far = isFar(peakDb)
        val end = when {
            ended.label != VoiceSegmentLabel.AGENT -> ended
            taking != null && taking < thresholds.bargeIn -> ended.copy(label = VoiceSegmentLabel.UNCERTAIN, reason = VoiceEndReason.BACKCHANNEL)
            // Words fit for the assistant ("叫什么？" while a song was recommended) from a video that got past the gate.
            far -> ended.copy(label = VoiceSegmentLabel.UNCERTAIN, reason = VoiceEndReason.FAR_SPEECH)
            else -> ended
        }
        // A segment of only 「退出」 dismisses without Jev ([dismissal]). Otherwise "退下吧" dismisses even when Jev hesitates
        // over who it was for (it scored 0.55); talk between people nearby (SIDE) never does, nor does a turn Jev could not
        // judge. Neither does a 「再见」 from a video farther away.
        val dismissed = onDismiss != null && !far && (
            ended.reason == VoiceEndReason.DISMISSED ||
                end.label != VoiceSegmentLabel.SIDE && end.reason != VoiceEndReason.WAKE_PHRASE &&
                (end.judgment?.endsSession ?: 0.0) >= thresholds.endSession
            )
        val submit = autoSubmit && end.label == VoiceSegmentLabel.AGENT && !dismissed
        val mode = if (submit) handOverMode(end.judgment) else null
        val segment = VoiceSegment(
            id = ++segmentIds,
            text = end.text,
            label = end.label,
            judgment = end.judgment,
            reason = end.reason,
            endedAtEpochMillis = System.currentTimeMillis(),
            submitted = submit,
            handOver = mode,
            dismissed = dismissed,
            peakDb = peakDb,
        )
        mutableState.update { it.copy(segments = (it.segments + segment).takeLast(MAX_SEGMENTS)) }
        if (dismissed) onDismiss?.invoke()
        if (mode == null) return
        remember(VoiceHistoryMessage("user", end.text))
        if (!fullDuplex) handedOverAt = clock()
        updateBusy(clock())
        onAgentTurn(VoiceHandOver(end.text, mode, end.judgment?.smallTalk))
    }

    /** The loudest frame since shortly before the turn's first words (the recognizer runs behind the audio). */
    private fun turnPeakDb(openedAt: Long?): Double? {
        if (nearGate == null || openedAt == null) return null
        return heardLevels.filter { it.first >= openedAt - RECOGNIZER_LAG_MILLIS }.maxOfOrNull { it.second }
    }

    /**
     * Whether a turn whose loudest frame was [peakDb] came from farther away than the caller: the gate is filtering
     * and even that frame stayed [NEAR_PEAK_MARGIN_DB] under the caller's level. The caller's own words reach it.
     */
    private fun isFar(peakDb: Double?): Boolean {
        val reference = nearGate?.referenceDb ?: return false
        if (peakDb == null || mutableState.value.nearGate?.active != true) return false
        return peakDb < reference - NEAR_PEAK_MARGIN_DB
    }

    private fun handOverMode(judgment: VoiceJudgment?): VoiceHandOverMode = when {
        !hostWorking -> VoiceHandOverMode.NEW_TURN
        (judgment?.replacesTask ?: 0.0) >= thresholds.replaceTask -> VoiceHandOverMode.REPLACE
        else -> VoiceHandOverMode.APPEND
    }

    private fun remember(message: VoiceHistoryMessage) {
        history.addLast(message)
        while (history.size > historyLimit) history.removeFirst()
    }

    private fun publishLive() {
        val text = tracker.transcript
        val judgment = tracker.latestJudgment
        val current = mutableState.value
        if (current.liveText != text || current.liveJudgment != judgment) {
            mutableState.update { it.copy(liveText = text, liveJudgment = judgment) }
        }
    }

    private sealed interface Event {
        data object Started : Event
        data object Tick : Event
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        data class Mic(val active: Boolean, val levels: FloatArray, val gate: NearGateStats? = null, val levelDb: Double? = null) : Event
        data class JudgeDone(val version: Int, val judgment: VoiceJudgment) : Event
        data class JudgeFailed(val version: Int, val message: String) : Event
        data class HostState(
            val working: Boolean,
            val speaking: Boolean,
            val speech: String,
            val task: String,
            val progress: String = "",
        ) : Event
        data class History(val message: VoiceHistoryMessage) : Event
        data class Failed(val message: String) : Event
    }
}

private const val TICK_MILLIS = 100L
/** After the agent and its speech end, the room's echo and the user's breath settle. */
private const val BUSY_TAIL_MILLIS = 600L
/** A handed-over turn the host never picked up stops blocking the mic after this long. */
private const val HAND_OVER_TIMEOUT_MILLIS = 10_000L
private const val MAX_SEGMENTS = 50
/** Frame levels kept for a turn's loudest frame: longer than the longest turn. */
private const val LEVELS_LOG_MILLIS = 20_000L
/** Audio this long before a turn's first words still belongs to it: the recognizer writes them late. */
private const val RECOGNIZER_LAG_MILLIS = 1_200L
/**
 * A turn whose loudest frame stays this far under the caller's wake level is not the caller. The owner's requests peaked
 * near the wake level on the tablet; a talk video that got past a -41.8 dBFS gate (wake -31.8) never reached -38 in the
 * probe recordings.
 */
private const val NEAR_PEAK_MARGIN_DB = 7.0
/** About 1.6 s of meter at five slices per frame. */
private const val LEVELS_KEPT = 80

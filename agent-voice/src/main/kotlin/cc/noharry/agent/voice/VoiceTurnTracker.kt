package cc.noharry.agent.voice

/** The text Jev is asked about, with the timing it needs. */
data class VoiceTurnSnapshot(
    val version: Int,
    val transcript: String,
    /** Earlier texts of this turn, oldest first, with their age in ms. */
    val trajectory: List<Pair<String, Long>>,
    val turnElapsedMillis: Long,
    /** Mic quiet since the last voiced frame; null while the mic hears speech. */
    val silenceMillis: Long?,
    /** The recognizer closed its segment and has produced nothing since. */
    val asrFinal: Boolean,
    /** The speaker called the assistant just before this turn (wake word, mic button, or its name mid-turn). */
    val called: Boolean = false,
)

enum class VoiceEndReason {
    JUDGED,
    SILENCE_CAP,
    JUDGE_FAILED,
    /** Judged side talk at a recognizer segment boundary: continuous chatter is cut into pieces. */
    SIDE_TALK,
    LENGTH_CAP,
    /** The assistant's own speech heard back while it talked. */
    ECHO,

    /** Said while the assistant spoke without taking the floor (a backchannel or residue): not handed over. */
    BACKCHANNEL,

    /** Cut where the speaker said the wake phrase: what came before is not handed over; what follows opens a turn. */
    WAKE_PHRASE,

    /**
     * Judged for the assistant, but even its loudest frame stayed well under the caller's level: talk nearby that got
     * past the [NearSpeechGate], not handed over and never a barge-in.
     */
    FAR_SPEECH,

    /** A closed recognizer segment that only dismisses the assistant ("退出"): the session ends without asking Jev. */
    DISMISSED,
}

sealed interface VoiceTurnAction {
    data class Judge(val snapshot: VoiceTurnSnapshot) : VoiceTurnAction

    data class End(
        val text: String,
        val label: VoiceSegmentLabel,
        val judgment: VoiceJudgment?,
        val reason: VoiceEndReason,
    ) : VoiceTurnAction
}

/**
 * One listening turn at a time, driven by recognizer text, mic activity and Jev answers; [poll] says when to ask Jev
 * and when the turn is over. Not thread-safe: feed it from one thread. Times are any monotonic milliseconds.
 */
class VoiceTurnTracker(
    private val thresholds: VoiceTurnThresholds = VoiceTurnThresholds(),
) {
    /**
     * The wake phrase as the recognizer writes it, punctuation and homophones included ([WakeWordProfile.heardInTranscript]):
     * heard mid-turn, it calls the assistant afresh. Null turns this off (no wake phrase, or the assistant is saying it).
     */
    var wakePhrase: Regex? = null

    /**
     * Words that dismiss the assistant on their own ("退出", "退下吧"), as the host reads them; null when the session cannot
     * be dismissed by voice. A closed recognizer segment that is nothing else ends the turn at once ([dismissedNow]).
     */
    var dismissal: ((String) -> Boolean)? = null

    /** When the speaker last called the assistant; a turn opening within [CALLED_TURN_MILLIS] is expected for it. */
    private var calledAt: Long? = null
    private val finals = mutableListOf<String>()
    private var partial = ""
    /**
     * Text of the recognizer's open segment that an ended turn already took (a turn cut mid-segment): later partials
     * of that segment repeat it, so it is stripped until the segment closes.
     */
    private var consumed = ""
    private var rawPartial = ""
    private var version = 0
    /** Versions at or below this belong to an ended turn; their answers are dropped. */
    private var turnBase = 0
    private var openedAt = -1L
    private var lastChangeAt = 0L
    private var lastVoiceAt = 0L
    private var micActive = false
    private var asrFinal = false
    private val trajectory = ArrayDeque<Pair<String, Long>>()
    private var judgment: VoiceJudgment? = null
    private var judgedVersion = -1
    private var inFlightVersion: Int? = null
    private var milestone = 0
    private var failedVersion = -1
    private var lastJudgeAt = Long.MIN_VALUE / 2

    var busy = false
        private set

    /** The latest answer for this turn, whatever text it judged. */
    var latestJudgment: VoiceJudgment? = null
        private set

    val transcript: String get() = (finals + partial).fold("", ::joinSpeech)

    /** When the open turn's first words came; null between turns. */
    val openedAtMillis: Long? get() = openedAt.takeIf { it >= 0 }

    fun onPartial(text: String, now: Long) {
        if (busy) return
        val before = transcript
        rawPartial = text.trim()
        partial = unconsumed(text)
        if (partial.isNotEmpty()) asrFinal = false
        if (transcript != before) changed(now)
    }

    fun onFinal(text: String, now: Long) {
        if (busy) return
        val before = transcript
        partial = ""
        unconsumed(text).takeIf { it.isNotEmpty() }?.let(finals::add)
        consumed = ""
        rawPartial = ""
        asrFinal = true
        if (transcript != before) changed(now)
    }

    fun onMic(active: Boolean, now: Long) {
        micActive = active
        if (active) lastVoiceAt = now
    }

    /** The speaker just called the assistant (woke it, tapped the mic): the next turn is expected to be for it. */
    fun onCalled(now: Long) {
        calledAt = now
    }

    fun silenceMillis(now: Long): Long = if (micActive) 0 else (now - lastVoiceAt).coerceAtLeast(0)

    fun onJudgment(version: Int, answer: VoiceJudgment) {
        if (version == inFlightVersion) inFlightVersion = null
        if (version <= turnBase) return
        latestJudgment = answer
        if (version == this.version) {
            judgment = answer
            judgedVersion = version
        }
    }

    fun onJudgmentFailed(version: Int) {
        if (version == inFlightVersion) inFlightVersion = null
        if (version == this.version && version > turnBase) failedVersion = version
    }

    /** Agent working or speaking: the open turn is dropped and nothing is judged until it is idle again. */
    fun setBusy(busy: Boolean, now: Long) {
        if (busy == this.busy) return
        this.busy = busy
        reset()
        consumed = ""
        rawPartial = ""
        micActive = false
        lastVoiceAt = now
    }

    fun poll(now: Long): VoiceTurnAction? {
        if (busy) return null
        cutAtWakePhrase(now)?.let { return it }
        dismissedNow()?.let { return it }
        if (transcript.isEmpty()) return null
        val silence = silenceMillis(now)
        val current = judgment.takeIf { judgedVersion == version }
        if (current != null && VoiceTurnPolicy.shouldEnd(thresholds, current, silence, recognizerSettled = asrFinal || partial.isEmpty())) {
            return end(VoiceEndReason.JUDGED, current)
        }
        // A dismissal the recognizer has closed ends at once: talk nearby may never leave the quiet the bar waits for.
        if (asrFinal && current != null && (current.endsSession ?: 0.0) >= thresholds.endSession) {
            return end(VoiceEndReason.JUDGED, current)
        }
        if (silence >= VoiceTurnPolicy.silenceCap(thresholds, latestJudgment)) {
            return end(VoiceEndReason.SILENCE_CAP, current ?: latestJudgment)
        }
        if (asrFinal && current != null && VoiceTurnPolicy.label(thresholds, current) == VoiceSegmentLabel.SIDE) {
            return end(VoiceEndReason.SIDE_TALK, current)
        }
        if (now - openedAt >= maxTurnMillis()) {
            return end(VoiceEndReason.LENGTH_CAP, current ?: latestJudgment)
        }
        if (failedVersion == version) {
            return if (silence >= FAILED_QUIET_MILLIS) end(VoiceEndReason.JUDGE_FAILED, null) else null
        }
        if (inFlightVersion != null) return null
        if (judgedVersion != version) {
            // While words keep coming, ask at most every so often; slower still once it reads as side talk.
            val interval = when {
                silence > 0 -> 0L
                VoiceTurnPolicy.label(thresholds, latestJudgment) == VoiceSegmentLabel.SIDE -> SIDE_JUDGE_INTERVAL_MILLIS
                else -> JUDGE_INTERVAL_MILLIS
            }
            val settled = now - lastChangeAt >= JUDGE_DEBOUNCE_MILLIS && now - lastJudgeAt >= interval
            return if (settled) judge(now, silence) else null
        }
        // Same text, longer quiet: ask again as the silence passes each milestone.
        val next = SILENCE_MILESTONES.getOrNull(milestone) ?: return null
        if (silence < next) return null
        milestone = SILENCE_MILESTONES.count { it <= silence }
        return judge(now, silence)
    }

    private fun judge(now: Long, silence: Long): VoiceTurnAction.Judge {
        inFlightVersion = version
        lastJudgeAt = now
        val history = trajectory.toList().dropLast(1).takeLast(TRAJECTORY_SENT).map { (text, at) -> text to now - at }
        return VoiceTurnAction.Judge(
            VoiceTurnSnapshot(
                version = version,
                transcript = transcript,
                trajectory = history,
                turnElapsedMillis = (now - openedAt).coerceAtLeast(0),
                silenceMillis = silence.takeIf { it > 0 },
                asrFinal = asrFinal,
                called = called(),
            ),
        )
    }

    private fun changed(now: Long) {
        if (openedAt < 0) openedAt = now
        version++
        lastChangeAt = now
        // A new word means the mic heard speech just now, even if the energy gate missed it.
        lastVoiceAt = maxOf(lastVoiceAt, now)
        milestone = 0
        trajectory.addLast(transcript to now)
        while (trajectory.size > TRAJECTORY_KEPT) trajectory.removeFirst()
    }

    private fun unconsumed(text: String): String = stripConsumed(text.trim(), consumed)

    private fun end(reason: VoiceEndReason, answer: VoiceJudgment?): VoiceTurnAction.End {
        val heard = VoiceTurnPolicy.label(thresholds, answer, called())
        val (label, text) = when {
            // A called turn Jev never answered is for the assistant, as it would be unless Jev read it as side talk
            // (owner 2026-10-18: 「华强北附近有什么好吃的」 right after the wake phrase was dropped while Jev timed out); a lone
            // filler is not.
            answer == null && called() && reason in UNJUDGED_ENDS && transcript.hasWords() -> VoiceSegmentLabel.AGENT to transcript
            reason == VoiceEndReason.JUDGE_FAILED -> VoiceSegmentLabel.UNCERTAIN to transcript
            reason == VoiceEndReason.LENGTH_CAP -> lengthCapped(heard)
            else -> heard to transcript
        }
        // Cut mid-segment: the recognizer will keep repeating what this turn took.
        consumed = if (!asrFinal && partial.isNotEmpty()) rawPartial else ""
        reset()
        // An echo is never a request, however it was cut.
        if (answer?.echo == true) return VoiceTurnAction.End(text, VoiceSegmentLabel.SIDE, answer, VoiceEndReason.ECHO)
        if (label == VoiceSegmentLabel.AGENT) calledAt = null
        return VoiceTurnAction.End(text, label, answer, reason)
    }

    /** Words for the assistant may run to [VoiceTurnThresholds.maxAgentTurnMillis]; anything else ends at the shorter cap. */
    private fun maxTurnMillis(): Long =
        if (VoiceTurnPolicy.label(thresholds, latestJudgment, called()) == VoiceSegmentLabel.AGENT) thresholds.maxAgentTurnMillis else thresholds.maxTurnMillis

    /**
     * A turn that never finishes is mostly chatter or a video. Only when the latest score says the words are for the
     * assistant (so after [VoiceTurnThresholds.maxAgentTurnMillis]) are its last complete sentences handed over
     * ("…你还没跟我说哪家最好吃。哎，那个"); with no complete sentence, or not for the assistant, nothing is.
     */
    private fun lengthCapped(heard: VoiceSegmentLabel): Pair<VoiceSegmentLabel, String> {
        if (heard == VoiceSegmentLabel.SIDE) return heard to transcript
        val last = lastSentences().takeIf { heard == VoiceSegmentLabel.AGENT }
        return if (last != null) VoiceSegmentLabel.AGENT to last else VoiceSegmentLabel.UNCERTAIN to transcript
    }

    /**
     * The last complete sentences, at least one and otherwise up to [TAIL_CHARS] letters, so a short last question
     * keeps what it follows ("你让Grok去看一下那个时候的天气怎么样，还有。交通怎么样？"). Complete means closed by sentence
     * punctuation, or a whole recognizer segment (an on-device recognizer has no punctuation).
     */
    private fun lastSentences(): String? {
        val complete = finals.flatMap(::sentences) + sentences(partial).filter { it.last() in SENTENCE_ENDS }
        if (complete.isEmpty()) return null
        var letters = 0
        val taken = complete.asReversed().takeWhile { sentence ->
            val first = letters == 0
            letters += sentence.count(Char::isLetterOrDigit)
            first || letters <= TAIL_CHARS
        }
        return taken.asReversed().fold("", ::joinSpeech)
    }

    /**
     * The wake phrase heard mid-turn ("…让它消掉一点。<wake phrase>，你觉得哪家最好吃"): the words before it end here and are not
     * handed over, and the words after it open a new turn expected to be for the assistant. Null when there is no
     * wake phrase, or nothing came before it.
     */
    private fun cutAtWakePhrase(now: Long): VoiceTurnAction.End? {
        val phrase = wakePhrase ?: return null
        val inPartial = phrase.findAll(partial).lastOrNull()
        val head: String
        val tailFinals: List<String>
        val tailPartial: String
        if (inPartial != null) {
            head = (finals + partial.substring(0, inPartial.range.first)).fold("", ::joinSpeech)
            tailFinals = emptyList()
            tailPartial = partial.substring(inPartial.range.last + 1).trimStart(::isSeparator)
            // The recognizer keeps repeating its open segment: later partials drop it up to the wake phrase.
            consumed = phrase.findAll(rawPartial).lastOrNull()?.let { rawPartial.substring(0, it.range.last + 1) } ?: rawPartial
        } else {
            val index = finals.indexOfLast(phrase::containsMatchIn).takeIf { it >= 0 } ?: return null
            val match = phrase.findAll(finals[index]).last()
            head = (finals.take(index) + finals[index].substring(0, match.range.first)).fold("", ::joinSpeech)
            tailFinals = listOf(finals[index].substring(match.range.last + 1).trimStart(::isSeparator)) + finals.drop(index + 1)
            tailPartial = partial
        }
        val answer = latestJudgment
        val heard = VoiceTurnPolicy.label(thresholds, answer)
        val settled = asrFinal
        reset()
        finals += tailFinals.filter(String::isNotEmpty)
        partial = tailPartial
        asrFinal = settled && tailPartial.isEmpty()
        calledAt = now
        if (transcript.isNotEmpty()) changed(now)
        if (head.none(Char::isLetterOrDigit)) return null
        val label = heard.takeIf { it == VoiceSegmentLabel.SIDE } ?: VoiceSegmentLabel.UNCERTAIN
        return VoiceTurnAction.End(head.trim(), label, answer, VoiceEndReason.WAKE_PHRASE)
    }

    /**
     * The newest closed segment dismisses the assistant ([dismissal]): the turn ends there with that segment as its text,
     * without waiting for Jev or for talk around it to stop; words before it in the turn go with it. Owner 2026-10-20:
     * 「唤醒之后马上说退出，它不会退出」: talk nearby kept the turn open, and Jev read 「我一退出」 (recognizer residue in
     * front) as no dismissal; on the bench a clean 「退出」 still waited 4.6 s for Jev, the first call taking 3.5 s.
     */
    private fun dismissedNow(): VoiceTurnAction.End? {
        val words = finals.lastOrNull()?.takeIf { asrFinal && dismissal?.invoke(it) == true } ?: return null
        val answer = latestJudgment
        reset()
        return VoiceTurnAction.End(words, VoiceSegmentLabel.AGENT, answer, VoiceEndReason.DISMISSED)
    }

    private fun called(): Boolean {
        val at = calledAt ?: return false
        return openedAt >= at && openedAt - at <= CALLED_TURN_MILLIS
    }

    private fun reset() {
        finals.clear()
        partial = ""
        turnBase = ++version
        openedAt = -1
        asrFinal = false
        trajectory.clear()
        judgment = null
        judgedVersion = -1
        latestJudgment = null
        inFlightVersion = null
        milestone = 0
        failedVersion = -1
    }
}

/**
 * [text] without the [consumed] start a cut turn already took. Cloud recognizers revise a segment when they close it
 * (a homophone swapped, punctuation added), so the start matches on letters and digits only, allowing one differing
 * character in five; anything else is kept whole.
 */
internal fun stripConsumed(text: String, consumed: String): String {
    if (consumed.isEmpty()) return text
    if (text.startsWith(consumed)) return text.removePrefix(consumed).trimStart(::isSeparator)
    val wanted = consumed.filter(Char::isLetterOrDigit)
    if (wanted.isEmpty()) return text
    var matched = 0
    var differing = 0
    var end = 0
    while (end < text.length && matched < wanted.length) {
        val char = text[end++]
        if (!char.isLetterOrDigit()) continue
        if (!char.equals(wanted[matched], ignoreCase = true)) differing++
        matched++
    }
    val allowed = maxOf(1, wanted.length / CONSUMED_MISMATCH_RATIO)
    if (matched < wanted.length || differing > allowed) return text
    return text.substring(end).trimStart(::isSeparator)
}

private fun isSeparator(char: Char): Boolean = !char.isLetterOrDigit()

/** [text] cut after each sentence-ending mark, blank pieces dropped. */
private fun sentences(text: String): List<String> {
    val pieces = mutableListOf<String>()
    var start = 0
    text.forEachIndexed { index, char ->
        if (char in SENTENCE_ENDS) {
            pieces += text.substring(start, index + 1)
            start = index + 1
        }
    }
    pieces += text.substring(start)
    return pieces.map(String::trim).filter { piece -> piece.any(Char::isLetterOrDigit) }
}

/** Joins recognizer pieces: a space only between two Latin letters or digits, as Chinese runs need none. */
internal fun joinSpeech(head: String, tail: String): String = when {
    head.isEmpty() -> tail
    tail.isEmpty() -> head
    head.last().isAsciiWord() && tail.first().isAsciiWord() -> "$head $tail"
    else -> head + tail
}

private fun Char.isAsciiWord(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/** At least two letters besides fillers: 「开灯」 is a request, 「嗯」 or 「呃，」 is not. */
private fun String.hasWords(): Boolean = count { it.isLetterOrDigit() && it !in FILLERS } >= 2

private const val FILLERS = "嗯啊呃哦唔额"
/** Ends a turn can reach without a Jev answer once its words are over: quiet, or Jev failing. */
private val UNJUDGED_ENDS = setOf(VoiceEndReason.SILENCE_CAP, VoiceEndReason.JUDGE_FAILED)

internal const val JUDGE_DEBOUNCE_MILLIS = 150L
/** Least time between questions while speech goes on (about two per second at most, given Jev's ~300 ms). */
internal const val JUDGE_INTERVAL_MILLIS = 600L
internal const val SIDE_JUDGE_INTERVAL_MILLIS = 2_000L
/** Without a Jev answer for the latest text, the turn ends as uncertain after this much quiet. */
internal const val FAILED_QUIET_MILLIS = 1_200L
internal val SILENCE_MILESTONES = listOf(350L, 900L, 1_800L, 3_000L)
private const val TRAJECTORY_KEPT = 8
private const val TRAJECTORY_SENT = 6
private const val CONSUMED_MISMATCH_RATIO = 5
private const val SENTENCE_ENDS = "。！？!?；;"
/** About two short sentences of Chinese. */
private const val TAIL_CHARS = 30
/** A turn that opens this long after the speaker called the assistant is no longer taken as the answer to the call. */
internal const val CALLED_TURN_MILLIS = 10_000L

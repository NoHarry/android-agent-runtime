package cc.noharry.agent.voice

/** What a finished stretch of speech turned out to be. */
enum class VoiceSegmentLabel {
    /** Spoken to the assistant: handed to the host. */
    AGENT,

    /** People talking to each other, a call, the radio: ignored. */
    SIDE,

    /** Could not tell, or Jev was unreachable: shown, never handed over. */
    UNCERTAIN,
}

/** Jev's scores for the current text, each 0..1; [takingFloor] only while the assistant speaks. */
data class VoiceJudgment(
    val turnComplete: Double,
    val holdingFloor: Double,
    val addressedToAgent: Double,
    val latencyMillis: Long = 0,
    val takingFloor: Double? = null,
    /** Only while the agent works: the words drop its task for a new one, rather than add to it. */
    val replacesTask: Double? = null,
    /** Only in a session that may be dismissed by voice: the words end the conversation ("退下吧", "再见"). */
    val endsSession: Double? = null,
    /** Not asked: the words matched what the assistant was saying, so they are its own voice coming back. */
    val echo: Boolean = false,
    /** Only when the host asks (a companion chat service is set and the agent is idle): the words are small talk. */
    val smallTalk: Double? = null,
)

/**
 * When a turn ends and what it was. Defaults follow convoai-jev-vad: the end-of-turn bar relaxes from [eos] to
 * [eosFloor] over [eosDecayMillis] of silence, a request for time holds the floor, and silence alone ends a turn
 * after [silenceCapMillis] ([holdingSilenceCapMillis] while holding). Addressee scores split three ways.
 */
data class VoiceTurnThresholds(
    val eos: Double = 0.72,
    val eosFloor: Double = 0.55,
    val eosDecayMillis: Long = 3_000,
    val holdingFloorMin: Double = 0.5,
    val addresseeAgent: Double = 0.6,
    val addresseeSide: Double = 0.4,
    val confirmQuietMillis: Long = 350,
    val silenceCapMillis: Long = 5_000,
    val holdingSilenceCapMillis: Long = 12_000,
    /** Open-mic chatter (a video, a long conversation) never goes quiet; a turn this long ends anyway. */
    val maxTurnMillis: Long = 15_000,
    /**
     * A turn Jev reads as for the assistant runs this long instead: a multi-stop route said in one go (the demo's
     * 「老大在南山书城学美术，老二在香蜜湖学芭蕾…」, about 110 characters) takes 25–30 s, and [maxTurnMillis] cut it
     * (owner 2026-10-20: 「正常人说不完，时间太短会截断」).
     */
    val maxAgentTurnMillis: Long = 60_000,
    /** While the assistant speaks, words scoring this on `taking_floor` stop it (convoai-jev-vad's barge-in bar). */
    val bargeIn: Double = 0.5,
    /**
     * Words for the agent while it works replace its task at this `replaces_task` score; below, they are appended.
     * High, since replacing throws the work away and an append still gets the words handled (owner 2026-10-11).
     */
    val replaceTask: Double = 0.7,
    /**
     * While the recognizer has not closed the sentence (no final yet), a turn Jev is not sure of ([sureEnd]) waits this
     * long: a pause mid-sentence ("推荐一首适合开车听…的歌") otherwise cut the request, with the rest arriving late.
     */
    val unsettledQuietMillis: Long = 1_200,
    val sureEnd: Double = 0.88,
    /** In a session that may be dismissed by voice, words for the assistant at this `ends_session` score end it. */
    val endSession: Double = 0.7,
) {
    fun validationError(): String? = when {
        listOf(eos, eosFloor, holdingFloorMin, addresseeAgent, addresseeSide, bargeIn, replaceTask, sureEnd, endSession).any { !it.isFinite() || it !in 0.0..1.0 } ->
            "Scores must be between 0 and 1."
        eosFloor > eos -> "The end-of-turn floor cannot exceed the end-of-turn threshold."
        addresseeSide > addresseeAgent -> "The side-talk bound cannot exceed the agent bound."
        eosDecayMillis <= 0 || confirmQuietMillis < 0 -> "Durations must be positive."
        silenceCapMillis <= confirmQuietMillis || holdingSilenceCapMillis < silenceCapMillis ->
            "Silence caps must exceed the quiet window, and holding must not shorten them."
        maxTurnMillis < silenceCapMillis -> "The longest turn cannot be shorter than the silence cap."
        maxAgentTurnMillis < maxTurnMillis -> "A turn for the assistant cannot be capped shorter than any other."
        unsettledQuietMillis !in confirmQuietMillis..silenceCapMillis -> "The unsettled wait must lie between the quiet window and the silence cap."
        else -> null
    }
}

object VoiceTurnPolicy {
    /** The end-of-turn bar after [silenceMillis] of quiet: [VoiceTurnThresholds.eos] at once, the floor after the decay. */
    fun eosBar(thresholds: VoiceTurnThresholds, silenceMillis: Long): Double {
        if (silenceMillis <= 0) return thresholds.eos
        val progress = (silenceMillis.toDouble() / thresholds.eosDecayMillis).coerceAtMost(1.0)
        return thresholds.eos - (thresholds.eos - thresholds.eosFloor) * progress
    }

    fun holding(thresholds: VoiceTurnThresholds, judgment: VoiceJudgment?): Boolean =
        judgment != null && judgment.holdingFloor >= thresholds.holdingFloorMin

    /**
     * A judged turn ends once the mic has been quiet long enough and the text clears the bar. Long enough is the confirm
     * window when the recognizer has closed the sentence ([recognizerSettled]) or Jev is sure; otherwise the longer
     * unsettled wait, so words still on their way are not cut off.
     */
    fun shouldEnd(thresholds: VoiceTurnThresholds, judgment: VoiceJudgment, silenceMillis: Long, recognizerSettled: Boolean = true): Boolean {
        val quiet = if (recognizerSettled || judgment.turnComplete >= thresholds.sureEnd) thresholds.confirmQuietMillis else thresholds.unsettledQuietMillis
        return silenceMillis >= quiet && !holding(thresholds, judgment) && judgment.turnComplete >= eosBar(thresholds, silenceMillis)
    }

    fun silenceCap(thresholds: VoiceTurnThresholds, judgment: VoiceJudgment?): Long =
        if (holding(thresholds, judgment)) thresholds.holdingSilenceCapMillis else thresholds.silenceCapMillis

    /**
     * AGENT, SIDE or UNCERTAIN by `addressed_to_agent`. A [called] turn (the speaker had just called the assistant: a wake
     * word, the mic button, or its name said mid-turn) is for the assistant unless Jev reads it as clearly side talk.
     */
    fun label(thresholds: VoiceTurnThresholds, judgment: VoiceJudgment?, called: Boolean = false): VoiceSegmentLabel = when {
        judgment == null -> VoiceSegmentLabel.UNCERTAIN
        judgment.addressedToAgent >= if (called) thresholds.addresseeSide else thresholds.addresseeAgent -> VoiceSegmentLabel.AGENT
        judgment.addressedToAgent < thresholds.addresseeSide -> VoiceSegmentLabel.SIDE
        else -> VoiceSegmentLabel.UNCERTAIN
    }
}

/**
 * Whether [heard] is the assistant's own [speech] coming back through the mic: most of its character pairs occur in
 * what is being spoken. Echo cancellation leaves some residue, and the recognizer turns it into the reply's words.
 */
fun looksLikeEcho(heard: String, speech: String): Boolean {
    val words = heard.speechKey()
    val spoken = speech.speechKey()
    if (words.isEmpty() || spoken.isEmpty()) return false
    // One character ("好", "嗯") that the reply contains: residue, never a request worth a Jev call.
    if (words.length == 1) return words in spoken
    if (spoken.length < 2) return false
    val pairs = spoken.windowed(2).toSet()
    val heardPairs = words.windowed(2)
    return heardPairs.count(pairs::contains).toDouble() / heardPairs.size >= ECHO_PAIR_SHARE
}

/** Letters and digits only, lower case: punctuation and spacing differ between a reply and its recognition. */
private fun String.speechKey(): String = filter(Char::isLetterOrDigit).lowercase()

private const val ECHO_PAIR_SHARE = 0.6

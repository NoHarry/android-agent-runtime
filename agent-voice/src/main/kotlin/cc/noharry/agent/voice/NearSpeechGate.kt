package cc.noharry.agent.voice

import kotlin.math.log10

/**
 * Lets through only speech about as loud as the person who called the assistant. Someone talking nearby, or a video,
 * reaches the microphone far quieter than the speaker at arm's length: on the TB323FU a talk video at 1 m and at 2 m
 * stayed under -43 dBFS for 95 % of its frames while the owner at 40–50 cm spoke at -32 dBFS median. Without the gate
 * the recognizer writes the video's words into the turn, the turn never goes quiet, and its words even stop the reply.
 *
 * A frame passes when it reaches [referenceDb] minus [belowReferenceDb]; so does the frame before it (a word's onset)
 * and [hangoverFrames] after (its fading end). Everything else becomes digital silence, so the recognizer, the silence
 * timer and Jev hear only the speaker. Output runs one frame behind input for that look-ahead. With no reference the
 * gate does nothing, and it stands aside while the quieter sound's median sits within [minRoomDb] of the threshold:
 * the speaker is then too faint, or the room too loud, to tell apart.
 *
 * Not thread-safe: feed it from the thread that feeds the recognizer.
 */
class NearSpeechGate(
    /** The speaker's level in dBFS, from the wake phrase ([wakeLevelDb]); null keeps the gate open. */
    val referenceDb: Double?,
    private val belowReferenceDb: Double = DEFAULT_BELOW_REFERENCE_DB,
    private val minRoomDb: Double = DEFAULT_MIN_ROOM_DB,
    private val hangoverFrames: Int = DEFAULT_HANGOVER_FRAMES,
) {
    /** Frames under this level are not the speaker; null when the gate is off. */
    val thresholdDb: Double? = referenceDb?.let { it - belowReferenceDb }

    private val quieter = ArrayDeque<Double>()
    private var held: ShortArray? = null
    private var heldSinceNear = hangoverFrames + 1
    private var silence = ShortArray(0)

    var passedFrames = 0
        private set
    var blockedFrames = 0
        private set

    /** Median level of the voiced frames below the threshold (the room's other talk); null until enough were heard. */
    val backgroundDb: Double?
        get() = quieter.takeIf { it.size >= MIN_BACKGROUND_FRAMES }?.sorted()?.let { it[it.size / 2] }

    /** Whether the gate is filtering now: it has a reference and the quieter sound leaves room under it. */
    val active: Boolean
        get() {
            val threshold = thresholdDb ?: return false
            val background = backgroundDb ?: return true
            return background < threshold - minRoomDb
        }

    /** What the gate is doing, for the UI and the log; null when it has no reference. */
    fun stats(): NearGateStats? = thresholdDb?.let { NearGateStats(it, backgroundDb, active, passedFrames, blockedFrames) }

    /** Feeds one frame and returns the one to recognize: the previous frame, or silence in its place. */
    fun process(frame: ShortArray): ShortArray {
        val threshold = thresholdDb ?: return frame
        val level = frameDb(frame)
        val below = level < threshold
        // Counted even while the gate stands aside, so it comes back once the room quiets down.
        if (below && level > VOICED_DB) {
            quieter.addLast(level)
            while (quieter.size > BACKGROUND_FRAMES) quieter.removeFirst()
        }
        val near = !below || !active
        val previous = held
        val out = when {
            previous == null -> silenceOf(frame.size)
            near || heldSinceNear <= hangoverFrames -> previous.also { passedFrames++ }
            else -> silenceOf(previous.size).also { blockedFrames++ }
        }
        held = frame
        heldSinceNear = if (near) 0 else (heldSinceNear + 1).coerceAtMost(hangoverFrames + 1)
        return out
    }

    private fun silenceOf(size: Int): ShortArray {
        if (silence.size != size) silence = ShortArray(size)
        return silence
    }

    companion object {
        /** Under this a frame is the noise suppressor's digital silence or a gap between words, not a talker. */
        const val VOICED_DB = -70.0
        const val DEFAULT_BELOW_REFERENCE_DB = 10.0
        /** 6 dB stood aside for 2.4 s on the tablet when a talk video at 2 m rose to a -46 dBFS median under a -42 threshold. */
        const val DEFAULT_MIN_ROOM_DB = 3.0
        const val DEFAULT_HANGOVER_FRAMES = 2

        /** RMS level of [frame] in dBFS; digital silence is -120. */
        fun frameDb(frame: ShortArray): Double = 20 * log10(MicActivity.rms(frame).coerceAtLeast(1e-6))
    }
}

/** A [NearSpeechGate] at work: its threshold, the quieter sound's median, and the frames it let through or held back. */
data class NearGateStats(
    val thresholdDb: Double,
    val backgroundDb: Double?,
    val active: Boolean,
    val passedFrames: Int,
    val blockedFrames: Int,
)

/**
 * The speaker's level from the frames heard while the wake phrase was said ([recentDb], oldest first, about the last
 * 1.5 s): the median of the loudest [WAKE_LEVEL_FRAMES] voiced frames, which are the phrase's syllables rather than the
 * room around it. Null with too few voiced frames to tell.
 */
fun wakeLevelDb(recentDb: List<Double>): Double? {
    val loudest = recentDb.filter { it > NearSpeechGate.VOICED_DB }.sortedDescending().take(WAKE_LEVEL_FRAMES)
    if (loudest.size < WAKE_LEVEL_FRAMES) return null
    return loudest[loudest.size / 2]
}

/** About the syllables of a four-syllable wake phrase at 100 ms a frame. */
internal const val WAKE_LEVEL_FRAMES = 6
/** 1.5 s of frames before a wake hit: the phrase and the spotter's delay. */
internal const val WAKE_LEVEL_WINDOW_FRAMES = 15
private const val BACKGROUND_FRAMES = 100
private const val MIN_BACKGROUND_FRAMES = 20

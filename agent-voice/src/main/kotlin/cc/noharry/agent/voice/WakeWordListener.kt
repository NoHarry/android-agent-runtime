package cc.noharry.agent.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Listens for a wake phrase on the device with sherpa-onnx keyword spotting: the 3M-parameter Chinese–English Zipformer
 * (2025-12-20), INT8 encoder and joiner, one CPU thread. It is light enough to stay on while the full recognizer is
 * off. A phrase is given as tokens ([wakeKeyword]): pinyin for Chinese, so homophones match alike, and phones for
 * English. Audio stays in memory.
 * [onWake] runs on a background thread with the phrase heard; [onError] when the mic or model fails, which ends it.
 * Before [onWake], [lastHitLevelDb] is set to how loud the phrase was said ([wakeLevelDb]), the voice session's
 * [NearSpeechGate] reference.
 */
class WakeWordListener(
    private val assets: AssetManager,
    private val audio: VoiceAudioSource,
    profile: WakeWordProfile,
    sensitivity: WakeSensitivity = WakeSensitivity.STANDARD,
    private val keywords: String = sensitivity.keywords(profile),
    private val modelDir: String = WAKE_MODEL_ASSET_DIR,
    private val threshold: Float = sensitivity.threshold,
    private val boost: Float = sensitivity.boost,
    private val paths: Int = sensitivity.paths,
    private val onWake: (String) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    val listening: Boolean get() = job?.isActive == true

    /** The speaker's level at the latest hit in dBFS; null before one, or when too little was voiced to tell. */
    @Volatile
    var lastHitLevelDb: Double? = null
        private set

    fun start() {
        if (listening) return
        availabilityError(assets, modelDir)?.let { throw IllegalStateException(it) }
        job = scope.launch { spot() }
    }

    /** Stops listening; the microphone is released shortly after, on the listening thread. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun spot() {
        val spotter = KeywordSpotter(assets, spotterConfig(modelDir, threshold, boost, paths))
        val stream = spotter.createStream(keywords)
        val recentDb = ArrayDeque<Double>()
        try {
            audio.frames().collect { frame ->
                recentDb.addLast(NearSpeechGate.frameDb(frame))
                while (recentDb.size > WAKE_LEVEL_WINDOW_FRAMES) recentDb.removeFirst()
                stream.acceptWaveform(FloatArray(frame.size) { frame[it] / PCM_SCALE }, VOICE_SAMPLE_RATE)
                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val heard = spotter.getResult(stream).keyword
                    // The stream must be reset right after a hit, or the same phrase fires again.
                    if (heard.isNotEmpty()) {
                        spotter.reset(stream)
                        // A near miss that outscored the phrase ([WakeWordProfile.decoys]): heard, and let go.
                        if (heard.startsWith(DECOY_MARK)) continue
                        lastHitLevelDb = wakeLevelDb(recentDb.toList())
                        onWake(heard)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onError(error)
        } finally {
            stream.release()
            spotter.release()
        }
    }

    companion object {
        /** Null when the native library and every model file are present, else why wake-word listening cannot run. */
        fun availabilityError(assets: AssetManager, modelDir: String = WAKE_MODEL_ASSET_DIR): String? {
            val files = runCatching { assets.list(modelDir)?.toSet().orEmpty() }.getOrDefault(emptySet())
            val missing = WAKE_MODEL_FILES.filterNot(files::contains)
            if (missing.isNotEmpty()) return "唤醒词模型未安装：assets/$modelDir 里缺少 ${missing.joinToString()}，放进去后重新打包"
            return SherpaOnnxAsrEngine.nativeLibraryError
        }
    }
}

/**
 * One microphone user at a time: the wrapped [source] opens only once the previous holder of [lease] has closed its
 * recording, and holds it until its own flow ends. The wake-word listener and the voice session hand the mic over
 * this way without either blocking the main thread.
 */
class LeasedAudioSource(private val lease: Mutex, private val source: VoiceAudioSource) : VoiceAudioSource {
    override fun frames(): Flow<ShortArray> = flow { lease.withLock { emitAll(source.frames()) } }
}

/**
 * Lets a second listener hear what one recording hears, without opening the microphone again: [frames] records from
 * [source] as usual and offers a copy of each frame to [copies], dropping the oldest when that listener lags. The
 * wake-word spotter listens to a voice session this way, so saying the wake phrase again can interrupt it. Without
 * [copySource] the recording hands over other frames itself through [offer]: a session that hears only the caller's
 * seat gives the spotter every seat ([ArrayMicrophoneAudioSource]'s `onEveryone`).
 */
class VoiceAudioTee(
    private val source: VoiceAudioSource,
    buffered: Int = TEE_BUFFERED_FRAMES,
    private val copySource: Boolean = true,
) : VoiceAudioSource {
    private val shared = MutableSharedFlow<ShortArray>(extraBufferCapacity = buffered, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override fun frames(): Flow<ShortArray> = if (copySource) source.frames().onEach(::offer) else source.frames()

    /** Hands [copies] a copy of [frame]. */
    fun offer(frame: ShortArray) {
        shared.tryEmit(frame.copyOf())
    }

    /** The frames [frames] records from when this is collected; it never completes, so its collector is cancelled. */
    val copies: VoiceAudioSource = VoiceAudioSource { shared }
}

/** Two seconds of 100 ms frames. */
private const val TEE_BUFFERED_FRAMES = 20

/**
 * A keyword line for the spotter: space-separated tokens, its own [boost] (`:`) and [threshold] (`#`) if it has them, in
 * place of the spotter's, then `@` and the phrase reported on a hit.
 */
fun wakeKeyword(tokens: String, phrase: String, threshold: Float? = null, boost: Float? = null): String {
    val parts = listOfNotNull(
        tokens.trim().split(Regex("\\s+")).joinToString(" "),
        boost?.let { ":$it" },
        threshold?.let { "#$it" },
        "@$phrase",
    )
    return parts.joinToString(" ")
}

/**
 * One phrase the spotter listens for: its [tokens] and the [phrase] a hit reports (no spaces: the spotter splits its
 * line on them). Chinese is written in pinyin split into initial and final (`n ǐ h ǎo`), English in the model's phones
 * with stress (`OW1 K EY1`). [threshold] and [boost] replace the spotter's for this phrase alone: a lower threshold for a
 * phrase with a weak syllable that other words do not come near, a higher boost (the score its tokens earn on the search)
 * to keep a phrase on it that the model hears less surely.
 */
data class WakePhrase(val tokens: String, val phrase: String, val threshold: Float? = null, val boost: Float? = null) {
    init {
        require(phrase.isNotEmpty() && phrase.none(Char::isWhitespace)) { "A wake phrase is reported without spaces: '$phrase'." }
        require(threshold == null || threshold in 0f..1f) { "A wake phrase's threshold is between 0 and 1." }
        require(boost == null || boost > 0f) { "A wake phrase's boost is positive." }
    }
}

/**
 * The assistant's wake phrases for the spotter: [phrases] wake it at every [WakeSensitivity], [lenientPhrases] (near
 * misses of the phrase still meant for it) only at [WakeSensitivity.LOW], and [decoys] are listened for so that they,
 * not the phrase, win on their own words; a hit on a decoy never wakes, so each decoy's phrase starts with
 * [DECOY_MARK]. [heardInTranscript] finds the wake phrase as a recognizer writes it inside a turn, so saying it again
 * mid-turn calls the assistant ([VoiceTurnTracker.wakePhrase]). Phrases need at least four syllables: fewer wake on
 * everyday speech.
 */
data class WakeWordProfile(
    val phrases: List<WakePhrase>,
    val lenientPhrases: List<WakePhrase> = emptyList(),
    val decoys: List<WakePhrase> = emptyList(),
    val heardInTranscript: Regex? = null,
) {
    init {
        require(phrases.isNotEmpty()) { "A wake word profile needs at least one phrase." }
        require(decoys.all { it.phrase.startsWith(DECOY_MARK) }) { "Every decoy's phrase starts with $DECOY_MARK." }
        require((phrases + lenientPhrases).none { it.phrase.startsWith(DECOY_MARK) }) { "Only decoys start with $DECOY_MARK." }
    }

    /** The phrases that wake the assistant at [sensitivity]. */
    fun phrasesFor(sensitivity: WakeSensitivity): List<WakePhrase> =
        if (sensitivity.lenient) phrases + lenientPhrases else phrases
}

/**
 * How readily the spotter wakes: how many paths its search keeps ([paths]) and whether it also takes the profile's
 * lenient phrases ([lenient]). Every level also listens for the profile's decoys, which never wake.
 *
 * More paths keep the phrase in the search, at some cost: each spotter used about 11% more time at 12 paths than at 4.
 * The decoys keep a wider search from waking on near misses. Re-measure on recordings of the phrase before tuning.
 */
enum class WakeSensitivity(
    val wireName: String,
    val threshold: Float,
    val boost: Float,
    val paths: Int,
    val lenient: Boolean,
) {
    HIGH("high", DEFAULT_WAKE_THRESHOLD, DEFAULT_WAKE_BOOST, 6, lenient = false),
    STANDARD("standard", DEFAULT_WAKE_THRESHOLD, DEFAULT_WAKE_BOOST, 12, lenient = false),
    LOW("low", DEFAULT_WAKE_THRESHOLD, DEFAULT_WAKE_BOOST, 12, lenient = true),
    ;

    /** The spotter's keywords for [profile]: one line per phrase, the decoys after them, `/` between them. */
    fun keywords(profile: WakeWordProfile): String =
        (profile.phrasesFor(this) + profile.decoys).joinToString("/") { wakeKeyword(it.tokens, it.phrase, it.threshold, it.boost) }

    companion object {
        fun of(wireName: String?): WakeSensitivity? = entries.firstOrNull { it.wireName == wireName }
    }
}

internal fun spotterConfig(
    modelDir: String,
    threshold: Float,
    boost: Float = DEFAULT_WAKE_BOOST,
    paths: Int = DEFAULT_WAKE_PATHS,
) = KeywordSpotterConfig(
    featConfig = FeatureConfig(sampleRate = VOICE_SAMPLE_RATE, featureDim = 80),
    modelConfig = OnlineModelConfig(
        transducer = OnlineTransducerModelConfig(
            encoder = "$modelDir/$WAKE_ENCODER",
            decoder = "$modelDir/$WAKE_DECODER",
            joiner = "$modelDir/$WAKE_JOINER",
        ),
        tokens = "$modelDir/$WAKE_TOKENS",
        numThreads = 1,
        modelType = "zipformer2",
    ),
    keywordsFile = "$modelDir/$WAKE_KEYWORDS_FILE",
    keywordsScore = boost,
    keywordsThreshold = threshold,
    maxActivePaths = paths,
)

/** Starts a decoy's phrase. */
const val DECOY_MARK = "~"
/** sherpa-onnx's default; higher wakes less often by mistake and misses more. */
const val DEFAULT_WAKE_THRESHOLD = 0.25f
/** The Kotlin binding's keywords score, which the spotter always ran with. */
const val DEFAULT_WAKE_BOOST = 1.5f
/** The paths the spotter's search keeps, as [WakeSensitivity.STANDARD] (sherpa-onnx's default is 4). */
const val DEFAULT_WAKE_PATHS = 12
/** Chinese and English in one model (the 2024 WenetSpeech one heard Chinese only); 320 ms chunks. */
const val WAKE_MODEL_NAME = "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"
const val WAKE_MODEL_ASSET_DIR = "voice-models/$WAKE_MODEL_NAME"
private const val WAKE_ENCODER = "encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx"
private const val WAKE_DECODER = "decoder-epoch-13-avg-2-chunk-16-left-64.onnx"
private const val WAKE_JOINER = "joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx"
private const val WAKE_TOKENS = "tokens.txt"
/** Written by the asset fetch script; each stream is created with [WakeWordListener]'s own keywords. */
private const val WAKE_KEYWORDS_FILE = "keywords.txt"
private val WAKE_MODEL_FILES = listOf(WAKE_ENCODER, WAKE_DECODER, WAKE_JOINER, WAKE_TOKENS, WAKE_KEYWORDS_FILE)
private const val PCM_SCALE = 32_768f

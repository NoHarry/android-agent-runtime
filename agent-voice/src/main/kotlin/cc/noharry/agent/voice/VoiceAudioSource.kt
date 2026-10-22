package cc.noharry.agent.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.IOException
import kotlin.math.max
import kotlin.math.sqrt

/** 16 kHz mono PCM16 frames of [VOICE_FRAME_SAMPLES]; the audio stays in memory and is never written anywhere. */
fun interface VoiceAudioSource {
    fun frames(): Flow<ShortArray>
}

/**
 * Which capture effects actually run on this recording: the platform's echo canceller and noise suppressor, or a
 * device's reference-driven [EchoGate].
 */
data class VoiceAudioEffects(val echoCanceller: Boolean, val noiseSuppressor: Boolean, val echoGate: Boolean = false)

/**
 * The device microphone. The host must hold RECORD_AUDIO before collecting. With [echoCancel] it records the
 * `VOICE_COMMUNICATION` source, which devices pair with their echo canceller (the tablet's audio_effects.xml applies
 * AEC and NS there), and enables [AcousticEchoCanceler] and [NoiseSuppressor] on the session where available.
 * [onEffects] reports what ended up active.
 */
class MicrophoneAudioSource(
    private val echoCancel: Boolean = false,
    private val onEffects: (VoiceAudioEffects) -> Unit = {},
) : VoiceAudioSource {
    private val audioSource =
        if (echoCancel) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION

    @SuppressLint("MissingPermission")
    override fun frames(): Flow<ShortArray> = flow {
        val minBuffer = AudioRecord.getMinBufferSize(VOICE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw IOException("Microphone does not support 16 kHz mono PCM16.")
        val record = AudioRecord(
            audioSource,
            VOICE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer, VOICE_FRAME_SAMPLES * 2 * BUFFERED_FRAMES),
        )
        var canceller: AcousticEchoCanceler? = null
        var suppressor: NoiseSuppressor? = null
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) throw IOException("Microphone could not be opened.")
            if (echoCancel) {
                canceller = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId) else null
                suppressor = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId) else null
                runCatching { canceller?.enabled = true }
                runCatching { suppressor?.enabled = true }
            }
            onEffects(VoiceAudioEffects(canceller?.enabled == true, suppressor?.enabled == true))
            record.startRecording()
            while (currentCoroutineContext().isActive) {
                val frame = ShortArray(VOICE_FRAME_SAMPLES)
                var filled = 0
                while (filled < frame.size) {
                    val read = record.read(frame, filled, frame.size - filled)
                    if (read < 0) throw IOException("Microphone read failed: $read")
                    filled += read
                }
                emit(frame)
            }
        } finally {
            canceller?.release()
            suppressor?.release()
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * Whether the mic hears speech, from frame energy against the noise floor: the gate is three times the floor and
 * never below 0.006 full scale (convoai-jev-vad's acoustic gate). The floor is the quietest frame of the last 3 s,
 * since speech always has gaps; it follows a louder cabin within seconds and is not fooled by speech at the start.
 * A voiced frame keeps it active briefly.
 */
class MicActivity {
    private val recent = ArrayDeque<Double>()
    private var hangover = 0

    /** Feeds one frame; returns whether speech is present. */
    fun update(frame: ShortArray): Boolean {
        val level = rms(frame)
        recent.addLast(level)
        while (recent.size > FLOOR_WINDOW_FRAMES) recent.removeFirst()
        // Until the window has seen a gap, assume a quiet room rather than calling the first words noise.
        val floor = if (recent.size < FLOOR_WINDOW_FRAMES) minOf(recent.min(), MIN_GATE / GATE_OVER_FLOOR) else recent.min()
        val voiced = level > maxOf(MIN_GATE, floor * GATE_OVER_FLOOR)
        hangover = if (voiced) HANGOVER_FRAMES else (hangover - 1).coerceAtLeast(0)
        return voiced || hangover > 0
    }

    companion object {
        /**
         * Loudness of [frame] in [blocks] equal slices, each 0..1 on a dBFS scale (-60 dB and below is 0, full scale
         * is 1), for a level meter that moves faster than the 100 ms frames.
         */
        fun levels(frame: ShortArray, blocks: Int = LEVEL_BLOCKS): FloatArray {
            val size = frame.size / blocks
            if (size == 0) return FloatArray(0)
            return FloatArray(blocks) { block ->
                val slice = frame.copyOfRange(block * size, (block + 1) * size)
                val db = 20 * kotlin.math.log10(rms(slice).coerceAtLeast(1e-6))
                ((db - LEVEL_FLOOR_DB) / -LEVEL_FLOOR_DB).toFloat().coerceIn(0f, 1f)
            }
        }

        fun rms(frame: ShortArray): Double {
            if (frame.isEmpty()) return 0.0
            var sum = 0.0
            for (sample in frame) {
                val value = sample / 32_768.0
                sum += value * value
            }
            return sqrt(sum / frame.size)
        }
    }
}

private const val BUFFERED_FRAMES = 4
private const val MIN_GATE = 0.006
private const val GATE_OVER_FLOOR = 3.0
private const val HANGOVER_FRAMES = 2
private const val FLOOR_WINDOW_FRAMES = 30
private const val LEVEL_BLOCKS = 5
private const val LEVEL_FLOOR_DB = -60.0

/** Whether this device offers a platform echo canceller at all; without it the session stays half duplex. */
fun echoCancellerAvailable(): Boolean = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)

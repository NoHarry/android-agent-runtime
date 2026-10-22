package cc.noharry.agent.voice

/** Recognizer output; called from the engine's own thread. */
interface AsrListener {
    /** The best guess for the open segment so far; replaces the previous partial. */
    fun onPartial(text: String)

    /** The segment closed with this text; the next partial starts a new segment. */
    fun onFinal(text: String)

    /** The engine stopped working; the session ends. */
    fun onError(message: String)
}

/**
 * A streaming recognizer fed 16 kHz mono PCM16. [start] and [stop] bracket one listening session; [accept] is called
 * from a single thread, in order, with consecutive frames.
 */
interface StreamingAsrEngine {
    /** Shown in the UI, e.g. "本地 · sherpa-onnx". */
    val label: String

    /** Whether the transcript has punctuation, which changes what Jev is told about it. */
    val punctuates: Boolean

    fun start(listener: AsrListener)

    fun accept(samples: ShortArray)

    fun stop()
}

const val VOICE_SAMPLE_RATE = 16_000
/** 100 ms per frame. */
const val VOICE_FRAME_SAMPLES = 1_600

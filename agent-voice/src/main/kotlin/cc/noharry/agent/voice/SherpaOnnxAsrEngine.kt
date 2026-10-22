package cc.noharry.agent.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * On-device streaming recognition with sherpa-onnx: the Zipformer Small Chinese-English transducer, INT8 encoder and
 * joiner with an FP32 decoder, on the CPU. The model loads from APK assets; nothing leaves the device.
 */
class SherpaOnnxAsrEngine(
    private val assets: AssetManager,
    private val modelDir: String = SHERPA_MODEL_ASSET_DIR,
    private val numThreads: Int = 2,
) : StreamingAsrEngine {
    override val label: String = "本地 · sherpa-onnx Zipformer Small"
    override val punctuates: Boolean = false

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var listener: AsrListener? = null
    private var lastPartial = ""
    private val floats = FloatArray(VOICE_FRAME_SAMPLES)

    override fun start(listener: AsrListener) {
        availabilityError(assets, modelDir)?.let { throw IllegalStateException(it) }
        val created = OnlineRecognizer(assets, recognizerConfig(modelDir, numThreads))
        recognizer = created
        stream = created.createStream()
        this.listener = listener
        lastPartial = ""
    }

    override fun accept(samples: ShortArray) {
        val recognizer = recognizer ?: return
        val stream = stream ?: return
        val input = if (samples.size == floats.size) floats else FloatArray(samples.size)
        for (index in samples.indices) input[index] = samples[index] / 32_768f
        stream.acceptWaveform(input, VOICE_SAMPLE_RATE)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        if (recognizer.isEndpoint(stream)) {
            if (text.isNotEmpty()) listener?.onFinal(text)
            recognizer.reset(stream)
            lastPartial = ""
        } else if (text != lastPartial) {
            lastPartial = text
            listener?.onPartial(text)
        }
    }

    override fun stop() {
        stream?.release()
        recognizer?.release()
        stream = null
        recognizer = null
        listener = null
    }

    companion object {
        /** Null when the native library and every model file are present, else why local recognition cannot run. */
        fun availabilityError(assets: AssetManager, modelDir: String = SHERPA_MODEL_ASSET_DIR): String? {
            val files = runCatching { assets.list(modelDir)?.toSet().orEmpty() }.getOrDefault(emptySet())
            val missing = SHERPA_MODEL_FILES.filterNot(files::contains)
            if (missing.isNotEmpty()) return "本地模型未安装：assets/$modelDir 里缺少 ${missing.joinToString()}，放进去后重新打包"
            return nativeLibraryError
        }

        internal val nativeLibraryError: String? by lazy {
            runCatching { System.loadLibrary("sherpa-onnx-jni") }
                .exceptionOrNull()?.let { "sherpa-onnx 原生库加载失败（${it.message}），这台设备的 ABI 可能不在 agent-voice 自带的库里" }
        }
    }
}

internal fun recognizerConfig(modelDir: String, numThreads: Int): OnlineRecognizerConfig = OnlineRecognizerConfig(
    featConfig = FeatureConfig(sampleRate = VOICE_SAMPLE_RATE, featureDim = 80),
    modelConfig = OnlineModelConfig(
        transducer = OnlineTransducerModelConfig(
            encoder = "$modelDir/$SHERPA_ENCODER",
            decoder = "$modelDir/$SHERPA_DECODER",
            joiner = "$modelDir/$SHERPA_JOINER",
        ),
        tokens = "$modelDir/$SHERPA_TOKENS",
        numThreads = numThreads,
        provider = "cpu",
        modelType = "zipformer",
    ),
    // Segments close sooner than sherpa's demo (1.4 s): Jev, not the endpoint, decides when a turn ends.
    endpointConfig = EndpointConfig(
        rule1 = EndpointRule(false, 2.4f, 0.0f),
        rule2 = EndpointRule(true, 1.0f, 0.0f),
        rule3 = EndpointRule(false, 0.0f, 20.0f),
    ),
    enableEndpoint = true,
    decodingMethod = "greedy_search",
)

const val SHERPA_MODEL_NAME = "sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16"
const val SHERPA_MODEL_ASSET_DIR = "voice-models/$SHERPA_MODEL_NAME"
private const val SHERPA_ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
private const val SHERPA_DECODER = "decoder-epoch-99-avg-1.onnx"
private const val SHERPA_JOINER = "joiner-epoch-99-avg-1.int8.onnx"
private const val SHERPA_TOKENS = "tokens.txt"
val SHERPA_MODEL_FILES = listOf(SHERPA_ENCODER, SHERPA_DECODER, SHERPA_JOINER, SHERPA_TOKENS)

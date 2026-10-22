package cc.noharry.agent.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Alibaba Cloud Model Studio (百炼) real-time recognition over WebSocket: `run-task`, binary PCM, `result-generated`
 * partials and finals, `finish-task`. The audio is uploaded to the service.
 */
class DashScopeAsrEngine(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
    private val languageHints: List<String> = listOf("zh", "en"),
    private val client: OkHttpClient = DASHSCOPE_HTTP_CLIENT,
    /**
     * The hotword list (预编译热词) to recognize with, asked for on [start]'s thread (it may create or update the list
     * over the network); null, or a failure, recognizes without one.
     */
    private val vocabularyId: () -> String? = { null },
) : StreamingAsrEngine {
    override val label: String = "云端 · 百炼 $model"
    override val punctuates: Boolean = true

    private val lock = Any()
    private var socket: WebSocket? = null
    private var listener: AsrListener? = null
    private var taskId = ""
    private var started = false
    private val pending = ArrayDeque<ByteString>()
    private var finished: CountDownLatch? = null
    private var vocabulary: String? = null

    override fun start(listener: AsrListener) {
        vocabulary = runCatching { vocabularyId() }.getOrNull()
        taskId = UUID.randomUUID().toString()
        this.listener = listener
        finished = CountDownLatch(1)
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $apiKey").build()
        socket = client.newWebSocket(request, events)
    }

    override fun accept(samples: ShortArray) {
        val bytes = ByteArray(samples.size * 2)
        for (index in samples.indices) {
            val sample = samples[index].toInt()
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
        val frame = bytes.toByteString()
        synchronized(lock) {
            if (started) {
                socket?.send(frame)
            } else {
                pending.addLast(frame)
                while (pending.size > MAX_PENDING_FRAMES) pending.removeFirst()
            }
        }
    }

    override fun stop() {
        val active = socket ?: return
        val wasStarted = synchronized(lock) { started }
        if (wasStarted) {
            active.send(dashScopeFinishTask(taskId))
            runCatching { finished?.await(FINISH_WAIT_MILLIS, TimeUnit.MILLISECONDS) }
        }
        active.close(NORMAL_CLOSURE, null)
        synchronized(lock) {
            socket = null
            started = false
            pending.clear()
        }
        listener = null
    }

    private val events = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(dashScopeRunTask(taskId, model, languageHints, vocabulary))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val event = parseDashScopeEvent(text)) {
                DashScopeEvent.Started -> synchronized(lock) {
                    started = true
                    while (pending.isNotEmpty()) webSocket.send(pending.removeFirst())
                }
                is DashScopeEvent.Result -> if (event.final) listener?.onFinal(event.text) else listener?.onPartial(event.text)
                DashScopeEvent.Finished -> finished?.countDown()
                is DashScopeEvent.Failed -> {
                    finished?.countDown()
                    listener?.onError("百炼识别失败：${event.code} ${event.message}")
                }
                null -> Unit
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            finished?.countDown()
            if (socket === webSocket) listener?.onError("百炼连接失败：${response?.code ?: ""} ${t.message.orEmpty()}".trim())
        }
    }
}

internal sealed interface DashScopeEvent {
    data object Started : DashScopeEvent
    data class Result(val text: String, val final: Boolean) : DashScopeEvent
    data object Finished : DashScopeEvent
    data class Failed(val code: String, val message: String) : DashScopeEvent
}

internal fun dashScopeRunTask(taskId: String, model: String, languageHints: List<String>, vocabularyId: String? = null): String = buildJsonObject {
    putJsonObject("header") {
        put("action", "run-task")
        put("task_id", taskId)
        put("streaming", "duplex")
    }
    putJsonObject("payload") {
        put("task_group", "audio")
        put("task", "asr")
        put("function", "recognition")
        put("model", model)
        putJsonObject("parameters") {
            put("format", "pcm")
            put("sample_rate", VOICE_SAMPLE_RATE)
            if (languageHints.isNotEmpty()) {
                put("language_hints", buildJsonArray { languageHints.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            }
            if (!vocabularyId.isNullOrBlank()) put("vocabulary_id", vocabularyId)
        }
        putJsonObject("input") {}
    }
}.toString()

internal fun dashScopeFinishTask(taskId: String): String = buildJsonObject {
    putJsonObject("header") {
        put("action", "finish-task")
        put("task_id", taskId)
        put("streaming", "duplex")
    }
    putJsonObject("payload") { putJsonObject("input") {} }
}.toString()

/** Null for events the engine ignores, such as heartbeats. */
internal fun parseDashScopeEvent(text: String): DashScopeEvent? {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    val header = root["header"] as? JsonObject ?: return null
    return when (header["event"]?.jsonPrimitive?.contentOrNull) {
        "task-started" -> DashScopeEvent.Started
        "task-finished" -> DashScopeEvent.Finished
        "task-failed" -> DashScopeEvent.Failed(
            header["error_code"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            header["error_message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
        "result-generated" -> {
            val sentence = (root["payload"] as? JsonObject)?.get("output")?.jsonObject?.get("sentence") as? JsonObject ?: return null
            if (sentence["heartbeat"]?.jsonPrimitive?.booleanOrNull == true) return null
            val words = sentence["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            DashScopeEvent.Result(words, sentence["sentence_end"]?.jsonPrimitive?.booleanOrNull == true)
        }
        else -> null
    }
}

/**
 * The 百炼 real-time recognition models offered, all on the same `/api-ws/v1/inference` task protocol. Language hints
 * differ by model (fun-asr takes one at most), so only Paraformer gets zh+en; the Qwen-Audio models detect the
 * language themselves.
 */
enum class DashScopeAsrModel(val id: String, val label: String, val languageHints: List<String>) {
    PARAFORMER_REALTIME_V2("paraformer-realtime-v2", "Paraformer 实时 v2", listOf("zh", "en")),
    QWEN_AUDIO_3_0_FLASH("qwen-audio-3.0-asr-flash-streaming", "Qwen-Audio 3.0 ASR Flash", emptyList()),
    // qwen-audio-3.1-asr-flash-streaming works on the same protocol but was dropped for cost (owner, 2026-10-11).
    ;

    companion object {
        /** An unknown or retired ID falls back to the default. */
        fun of(id: String): DashScopeAsrModel = entries.firstOrNull { it.id == id } ?: PARAFORMER_REALTIME_V2
    }
}

const val DASHSCOPE_DEFAULT_ENDPOINT = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
const val DASHSCOPE_DEFAULT_MODEL = "paraformer-realtime-v2"
/** Audio sent before `task-started` is held, up to 3 s. */
private const val MAX_PENDING_FRAMES = 30
private const val FINISH_WAIT_MILLIS = 2_000L
private const val NORMAL_CLOSURE = 1000
private val DASHSCOPE_HTTP_CLIENT = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()

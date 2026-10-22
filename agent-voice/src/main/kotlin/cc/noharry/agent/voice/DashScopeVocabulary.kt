package cc.noharry.agent.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 百炼 hotword lists (预编译热词, model `speech-biasing`): a list belongs to one recognition model (`target_model` must
 * equal it, e.g. qwen-audio-3.0-asr-flash-streaming) and is referenced by `vocabulary_id` in the run-task. Blocking
 * calls; run them off the main thread.
 */
class DashScopeVocabularyClient(
    private val apiKey: String,
    private val endpoint: String = DASHSCOPE_CUSTOMIZATION_URL,
    private val client: OkHttpClient = VOCABULARY_HTTP_CLIENT,
) {
    /** A new list for [targetModel] holding [words]; its vocabulary_id. */
    fun create(targetModel: String, words: List<String>, prefix: String = DEFAULT_VOCABULARY_PREFIX): String {
        val output = call(buildJsonObject {
            put("action", "create_vocabulary")
            put("target_model", targetModel)
            put("prefix", prefix)
            put("vocabulary", vocabularyEntries(words))
        })
        return (output["vocabulary_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IOException("百炼没有返回热词表 ID。")
    }

    /** Replaces the words of an existing list. */
    fun update(vocabularyId: String, words: List<String>) {
        call(buildJsonObject {
            put("action", "update_vocabulary")
            put("vocabulary_id", vocabularyId)
            put("vocabulary", vocabularyEntries(words))
        })
    }

    private fun call(input: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("model", VOCABULARY_MODEL)
            put("input", input)
        }.toString().toRequestBody(JSON_TYPE)
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $apiKey").post(body).build()
        client.newCall(request).execute().use { response ->
            val root = runCatching { Json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject }.getOrNull()
            if (!response.isSuccessful) {
                val message = (root?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty()
                throw IOException("百炼热词接口返回 HTTP ${response.code}${if (message.isBlank()) "" else "：$message"}")
            }
            return root?.get("output") as? JsonObject ?: JsonObject(emptyMap())
        }
    }
}

/** Each word as `{text, weight, lang}`; blank words and repeats dropped. */
fun vocabularyEntries(words: List<String>, weight: Int = DEFAULT_HOTWORD_WEIGHT): JsonArray = buildJsonArray {
    words.map(String::trim).filter(String::isNotEmpty).distinct().forEach { word ->
        add(buildJsonObject {
            put("text", word)
            put("weight", weight)
            put("lang", if (word.all { it.code < 128 }) "en" else "zh")
        })
    }
}

/** Why [word] cannot be a hotword, or null: at most 15 characters with any non-ASCII, 7 words for plain ASCII. */
fun hotwordProblem(word: String): String? = when {
    word.isBlank() -> "热词不能为空。"
    word.any { it.code >= 128 } && word.length > MAX_HOTWORD_CHARS -> "「$word」超过 $MAX_HOTWORD_CHARS 个字。"
    word.all { it.code < 128 } && word.trim().split(Regex("\\s+")).size > MAX_HOTWORD_ASCII_WORDS -> "「$word」超过 $MAX_HOTWORD_ASCII_WORDS 个单词。"
    else -> null
}

const val DASHSCOPE_CUSTOMIZATION_URL = "https://dashscope.aliyuncs.com/api/v1/services/audio/asr/customization"
/** Lower-case letters and digits, at most 10. */
const val DEFAULT_VOCABULARY_PREFIX = "agentvoice"
private const val VOCABULARY_MODEL = "speech-biasing"
private const val DEFAULT_HOTWORD_WEIGHT = 4
private const val MAX_HOTWORD_CHARS = 15
private const val MAX_HOTWORD_ASCII_WORDS = 7
private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
private val VOCABULARY_HTTP_CLIENT = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .callTimeout(10, TimeUnit.SECONDS)
    .build()

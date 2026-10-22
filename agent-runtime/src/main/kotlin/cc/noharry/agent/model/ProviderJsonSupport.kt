package cc.noharry.agent.model

import cc.noharry.agent.kernel.api.model.ModelClientException

import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import cc.noharry.agent.transport.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelImage
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelResponseMetadata

internal fun providerRequest(
    url: String,
    headers: Map<String, String>,
    body: JsonObject,
): HttpRequest = HttpRequest(
    method = "POST",
    url = url,
    headers = headers + ("Content-Type" to "application/json"),
    body = body.toString().toByteArray(Charsets.UTF_8),
)

/**
 * The messages a wire without mid-conversation tool changes can take: a system update that only loads deferred tools
 * is dropped (those tools go out as ordinary tools there).
 */
@OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)
internal fun List<ModelMessage>.withoutToolLoads(): List<ModelMessage> =
    filterNot { it.role == "system" && it.content.isBlank() && it.toolAdditions.isNotEmpty() }

internal fun JsonObjectBuilder.putMessages(request: ModelRequest) {
    putJsonArray("messages") {
        for (message in request.messages.withoutToolLoads()) {
            add(kotlinx.serialization.json.buildJsonObject {
                put("role", message.role)
                put("content", message.content)
            })
        }
    }
}

internal fun ModelMessage.isAssistantToolCall(): Boolean =
    role == "assistant" && !toolCallId.isNullOrBlank() && !toolName.isNullOrBlank()

internal fun ModelMessage.isToolResult(): Boolean =
    role == "tool" && !toolCallId.isNullOrBlank()

/**
 * OpenAI-style tool outputs carry text only, so a tool result's images follow it in one user
 * message naming the call. Anthropic carries them inside `tool_result` instead.
 */
internal fun ModelMessage.toolResultImagesFollowUp(): ModelMessage? =
    if (!isToolResult() || images.isEmpty()) {
        null
    } else {
        ModelMessage(role = "user", content = "$TOOL_RESULT_IMAGES_LABEL $toolCallId:", images = images)
    }

internal const val TOOL_RESULT_IMAGES_LABEL = "Attached image(s) from tool result"

internal fun ModelImage.base64Data(): String =
    Base64.getEncoder().encodeToString(bytes)

internal fun ModelImage.dataUrl(): String =
    "data:$mediaType;base64,${base64Data()}"

internal fun endpoint(baseUrl: String, path: String): String =
    baseUrl.trimEnd('/').let { base ->
        if (base.endsWith(path)) base else base + path
    }

internal suspend fun executeProviderRequest(
    provider: String,
    transport: HttpTransport,
    request: HttpRequest,
): HttpResponse =
    try {
        transport.execute(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: RuntimeException) {
        throw providerTransportFailure(provider, error)
    } catch (error: IOException) {
        throw providerTransportFailure(provider, error)
    }

internal fun streamProviderRequest(
    provider: String,
    transport: HttpTransport,
    request: HttpRequest,
): Flow<HttpEvent> =
    try {
        transport.stream(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: RuntimeException) {
        throw providerTransportFailure(provider, error)
    } catch (error: IOException) {
        throw providerTransportFailure(provider, error)
    }

internal fun requireProviderSuccess(provider: String, response: HttpResponse) {
    if (response.statusCode !in 200..299) {
        throw providerHttpFailure(provider, response.statusCode, response.body)
    }
}

internal fun parseObject(text: String): JsonObject =
    Json.parseToJsonElement(text) as JsonObject

internal fun parseProviderObject(provider: String, text: String): JsonObject =
    try {
        parseObject(text)
    } catch (error: RuntimeException) {
        throw providerMalformedResponse(provider, error)
    }

internal fun parseObjectOrEmpty(text: String): JsonObject =
    try {
        Json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
    } catch (_: SerializationException) {
        JsonObject(emptyMap())
    } catch (_: IllegalArgumentException) {
        JsonObject(emptyMap())
    }

internal suspend fun streamText(events: Flow<HttpEvent>): String {
    val output = ByteArrayOutputStream()
    events.collect { event ->
        when (event) {
            is HttpEvent.Response -> Unit
            is HttpEvent.Data -> output.write(event.bytes)
            HttpEvent.Done -> Unit
        }
    }
    return output.toByteArray().toString(Charsets.UTF_8)
}

internal suspend fun providerStreamText(provider: String, events: Flow<HttpEvent>): String =
    try {
        var failureStatus: Int? = null
        val output = ByteArrayOutputStream()
        events.collect { event ->
            when (event) {
                is HttpEvent.Response -> {
                    if (event.statusCode !in 200..299) failureStatus = event.statusCode
                }
                is HttpEvent.Data -> if (failureStatus == null) {
                    output.write(event.bytes)
                } else {
                    output.writeCapped(event.bytes)
                }
                HttpEvent.Done -> Unit
            }
        }
        failureStatus?.let { throw providerHttpFailure(provider, it, output.toByteArray()) }
        output.toByteArray().toString(Charsets.UTF_8)
    } catch (error: CancellationException) {
        throw error
    } catch (error: RuntimeException) {
        if (error is ModelClientException) throw error
        throw providerTransportFailure(provider, error)
    } catch (error: IOException) {
        throw providerTransportFailure(provider, error)
    }

internal fun providerSsePayloads(provider: String, events: Flow<HttpEvent>): Flow<String> = flow {
    val current = mutableListOf<String>()
    val pending = ByteArrayOutputStream()
    val failureBody = ByteArrayOutputStream()
    var failureStatus: Int? = null
    suspend fun flush() {
        val payload = current.joinToString("\n").trim()
        if (payload.isNotBlank() && payload != "[DONE]") emit(payload)
        current.clear()
    }
    suspend fun processLine(rawLine: String) {
        val line = rawLine.removeSuffix("\r")
        if (line.isBlank()) flush() else if (line.startsWith("data:")) current += line.removePrefix("data:").trimStart()
    }
    suspend fun processPendingLine() { processLine(pending.toByteArray().toString(Charsets.UTF_8)); pending.reset() }
    try {
        events.collect { event ->
            when (event) {
                is HttpEvent.Response -> {
                    if (event.statusCode !in 200..299) failureStatus = event.statusCode
                }
                is HttpEvent.Data -> for (byte in event.bytes) {
                    if (failureStatus == null) {
                        if (byte == NEWLINE_BYTE) {
                            processPendingLine()
                        } else {
                            pending.write(byte.toInt())
                        }
                    } else {
                        failureBody.writeCapped(byte)
                    }
                }
                HttpEvent.Done -> Unit
            }
        }
        failureStatus?.let { throw providerHttpFailure(provider, it, failureBody.toByteArray()) }
        if (pending.size() > 0) processPendingLine()
        flush()
    } catch (error: CancellationException) {
        throw error
    } catch (error: RuntimeException) { if (error is ModelClientException) throw error; throw providerTransportFailure(provider, error) }
    catch (error: IOException) { throw providerTransportFailure(provider, error) }
}

private fun ByteArrayOutputStream.writeCapped(bytes: ByteArray) {
    val remaining = MAX_PROVIDER_ERROR_BODY_BYTES - size()
    if (remaining > 0) write(bytes, 0, minOf(remaining, bytes.size))
}

private fun ByteArrayOutputStream.writeCapped(byte: Byte) {
    if (size() < MAX_PROVIDER_ERROR_BODY_BYTES) write(byte.toInt())
}

internal fun ssePayloads(text: String): List<String> {
    val frames = mutableListOf<String>()
    val current = mutableListOf<String>()
    fun flush() {
        val payload = current.joinToString("\n").trim()
        if (payload.isNotBlank() && payload != "[DONE]") frames += payload
        current.clear()
    }
    for (line in text.lineSequence()) {
        when {
            line.isBlank() -> flush()
            line.startsWith("data:") -> current += line.removePrefix("data:").trimStart()
        }
    }
    flush()
    return frames
}

internal fun MutableList<ModelEvent>.addThinking(text: String?, signature: String? = null) {
    if (!text.isNullOrBlank()) add(ModelEvent.ThinkingDelta(text, signature))
}

internal fun MutableList<ModelEvent>.addText(text: String?) {
    if (!text.isNullOrBlank()) add(ModelEvent.TextDelta(text))
}

internal fun MutableList<ModelEvent>.addUsage(delta: ModelEvent.UsageDelta?) {
    if (delta != null) add(delta)
}

internal fun String.toThinkingEvent(): ModelEvent.ThinkingDelta? =
    takeIf { it.isNotBlank() }?.let(ModelEvent::ThinkingDelta)

internal fun String.toTextEvent(): ModelEvent.TextDelta? =
    takeIf { it.isNotBlank() }?.let(ModelEvent::TextDelta)

internal fun JsonArray.firstObject(): JsonObject? =
    firstOrNull() as? JsonObject

internal fun JsonObject.obj(name: String): JsonObject? =
    this[name] as? JsonObject

internal fun JsonObject.array(name: String): JsonArray? =
    this[name] as? JsonArray

internal fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull

internal fun JsonObject.rawOrString(name: String): String? =
    string(name) ?: this[name]?.toString()

internal fun JsonObject.modelUsageDelta(): ModelEvent.UsageDelta? {
    val promptDetails = obj("prompt_tokens_details")
    val completionDetails = obj("completion_tokens_details") ?: obj("output_tokens_details")
    val cacheRead = int("prompt_cache_hit_tokens")
        ?: int("cache_read_input_tokens")
        ?: promptDetails?.int("cached_tokens")
        ?: 0
    val cacheWrite = int("prompt_cache_miss_tokens") ?: int("cache_creation_input_tokens") ?: 0
    val rawInput = int("prompt_tokens") ?: int("input_tokens") ?: 0
    // Anthropic reports input_tokens excluding cache_read/cache_creation. OpenAI-compatible
    // and DeepSeek already include cached tokens inside prompt/input_tokens.
    val anthropicExclusiveCache =
        containsKey("cache_read_input_tokens") || containsKey("cache_creation_input_tokens")
    val inputTokens = if (anthropicExclusiveCache) {
        rawInput + cacheRead + cacheWrite
    } else {
        rawInput
    }
    val delta = ModelEvent.UsageDelta(
        inputTokens = inputTokens,
        outputTokens = int("completion_tokens") ?: int("output_tokens") ?: 0,
        reasoningTokens = completionDetails?.int("reasoning_tokens") ?: 0,
        cacheReadTokens = cacheRead,
        cacheWriteTokens = cacheWrite,
    )
    return delta.takeIf {
        it.inputTokens != 0 ||
            it.outputTokens != 0 ||
            it.reasoningTokens != 0 ||
            it.cacheReadTokens != 0 ||
            it.cacheWriteTokens != 0
    }
}

internal fun JsonObject.modelResponseMetadata(): ModelResponseMetadata? {
    val model = string("model")
        ?: obj("response")?.string("model")
        ?: obj("message")?.string("model")
    val systemFingerprint = string("system_fingerprint")
        ?: obj("response")?.string("system_fingerprint")
        ?: obj("message")?.string("system_fingerprint")
    return ModelResponseMetadata(
        model = model.orEmpty(),
        systemFingerprint = systemFingerprint.orEmpty(),
    ).takeIf { it.model.isNotBlank() || it.systemFingerprint.isNotBlank() }
}

private const val NEWLINE_BYTE = 10.toByte()
private const val MAX_PROVIDER_ERROR_BODY_BYTES = 4096

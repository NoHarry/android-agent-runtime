package cc.noharry.agent.a2a.server

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.serialization.json.*

internal class A2aRpcError(val code: Int, override val message: String) : RuntimeException(message)

internal data class A2aRpc(val id: JsonElement, val method: String, val params: JsonObject)

internal fun parseRpc(body: String): A2aRpc {
    val root = try { Json.parseToJsonElement(body) } catch (_: IllegalArgumentException) {
        throw A2aRpcError(-32700, "Parse error")
    } as? JsonObject ?: throw A2aRpcError(-32600, "Invalid Request")
    val id = root["id"]
    if (root.string("jsonrpc") != "2.0" || id == null || id == JsonNull ||
        id !is JsonPrimitive || (!id.isString && id.longOrNull == null)
    ) throw A2aRpcError(-32600, "A request with a string or integer id is required")
    return A2aRpc(id, root.string("method") ?: "", root["params"] as? JsonObject ?: jsonObject())
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun jsonObject(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*pairs))
internal fun text(value: String): JsonPrimitive = JsonPrimitive(value)
internal fun strings(vararg values: String): JsonArray = JsonArray(values.map(::text))
internal fun rpcResult(id: JsonElement, result: JsonElement): JsonObject =
    jsonObject("jsonrpc" to text("2.0"), "id" to id, "result" to result)
internal fun rpcError(id: JsonElement, error: A2aRpcError): JsonObject = jsonObject(
    "jsonrpc" to text("2.0"), "id" to id,
    "error" to jsonObject("code" to JsonPrimitive(error.code), "message" to text(error.message)),
)

internal fun agentCard(config: A2aServerConfig, port: Int): JsonObject = buildJsonObject {
    put("name", config.name)
    put("description", "Text requests, brief tool progress and speech text; no local audio playback.")
    put("version", "0.1.0")
    put("protocolVersion", "0.3.0")
    put("url", "http://127.0.0.1:$port/a2a")
    put("preferredTransport", "JSONRPC")
    put("capabilities", jsonObject(
        "streaming" to JsonPrimitive(true), "pushNotifications" to JsonPrimitive(false),
        "extensions" to JsonArray(listOf(jsonObject(
            "uri" to text(TASK_CONTROL_EXTENSION),
            "description" to text(
                "Steer: message/send with the running task's taskId adds the text to its next model request; the task keeps " +
                    "running. Interrupt: a new message (message/send or message/stream) with metadata interrupt=true and the " +
                    "contextId cancels that context's running task, which ends canceled, then runs the new message as a task.",
            ),
            "required" to JsonPrimitive(false),
        ))),
    ))
    put("defaultInputModes", strings("text/plain"))
    put("defaultOutputModes", strings("text/plain"))
    put("skills", JsonArray(listOf(jsonObject(
        "id" to text("runtime-text"), "name" to text("Runtime text execution"),
        "description" to text("Execute text with configured host tools and return text-only speech."),
        "tags" to strings("tools", "text", "tts"),
    ))))
}

internal fun parseText(params: JsonObject): Pair<String, String?> {
    val message = params["message"] as? JsonObject ?: invalidParams("message is required")
    if (message.string("role") != "user" || message.string("messageId").isNullOrBlank()) {
        invalidParams("A user message with messageId is required")
    }
    if (message["taskId"] != null && message.string("taskId").isNullOrBlank()) invalidParams("Invalid taskId")
    val parts = message["parts"] as? JsonArray ?: invalidParams("Text parts are required")
    if (parts.isEmpty()) invalidParams("Text parts are required")
    val input = parts.joinToString("\n") { part ->
        val value = part as? JsonObject ?: invalidParams("Only text parts are supported")
        if (value.string("kind") != "text") throw A2aRpcError(-32005, "Only text parts are supported")
        value.string("text") ?: invalidParams("Text part must contain a string")
    }
    if (input.isBlank()) invalidParams("Text must not be blank")
    val context = message["contextId"]?.let {
        message.string("contextId")?.takeIf(String::isNotBlank) ?: invalidParams("Invalid contextId")
    }
    val config = params["configuration"] as? JsonObject
    if (config?.get("pushNotificationConfig") != null) throw A2aRpcError(-32003, "Push notifications unsupported")
    val modes = config?.get("acceptedOutputModes") as? JsonArray
    if (modes != null && text("text/plain") !in modes) throw A2aRpcError(-32005, "Output is text/plain")
    return input to context
}

internal fun invalidParams(message: String): Nothing = throw A2aRpcError(-32602, message)

/** Top-level scalar message metadata as text; nested values are dropped and the map is bounded. */
internal fun messageMetadata(message: JsonObject): Map<String, String> {
    // New messages historically ignored metadata, so a malformed value stays ignored rather than rejected.
    val metadata = message["metadata"] as? JsonObject ?: return emptyMap()
    return metadata.entries.asSequence()
        .mapNotNull { (key, value) -> (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.let { key to it.content } }
        .filter { (key, value) -> key.length <= MAX_METADATA_KEY_CHARS && value.length <= MAX_METADATA_VALUE_CHARS }
        .take(MAX_METADATA_ENTRIES)
        .toMap()
}

/** Agent Card extension for steering and interrupting tasks; [agentCard] describes both. */
internal const val TASK_CONTROL_EXTENSION = "urn:agent-runtime:a2a:task-control:v1"
/** Message metadata key: `true` makes a new message interrupt its context's running task. */
internal const val INTERRUPT_METADATA = "interrupt"

private const val MAX_METADATA_ENTRIES = 16
private const val MAX_METADATA_KEY_CHARS = 64
private const val MAX_METADATA_VALUE_CHARS = 256

internal fun status(state: String, message: String, taskId: String, contextId: String): JsonObject = jsonObject(
    "state" to text(state), "timestamp" to text(timestamp()),
    "message" to jsonObject(
        "kind" to text("message"), "messageId" to text(UUID.randomUUID().toString()),
        "role" to text("agent"), "taskId" to text(taskId), "contextId" to text(contextId),
        "parts" to JsonArray(listOf(textPart(message))),
        "metadata" to jsonObject("channel" to text("status")),
    ),
)

internal fun textPart(value: String): JsonObject = jsonObject("kind" to text("text"), "text" to text(value))

private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date())

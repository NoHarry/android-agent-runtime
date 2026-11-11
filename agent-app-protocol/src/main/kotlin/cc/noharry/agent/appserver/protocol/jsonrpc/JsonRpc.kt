package cc.noharry.agent.appserver.protocol.jsonrpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One message on the wire: JSON-RPC 2.0 without the `"jsonrpc"` member, as Codex's app-server writes it. Either side
 * sends requests; a response answers the request with the same [JsonRpcRequest.id]; a notification has no id.
 */
sealed interface JsonRpcMessage

data class JsonRpcRequest(val id: JsonPrimitive, val method: String, val params: JsonElement? = null) : JsonRpcMessage

data class JsonRpcNotification(val method: String, val params: JsonElement? = null) : JsonRpcMessage

data class JsonRpcResponse(val id: JsonPrimitive, val result: JsonElement? = null, val error: JsonRpcError? = null) : JsonRpcMessage

@Serializable
data class JsonRpcError(val code: Int, val message: String, val data: JsonElement? = null)

/** The standard JSON-RPC codes, and the server's own from -32000 down. */
object JsonRpcErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    /** A request other than `initialize` before the handshake. */
    const val NOT_INITIALIZED = -32002

    /** The request was understood but the server would not do it now, e.g. a turn while one is running elsewhere. */
    const val REJECTED = -32001

    /** The connection closed before the answer came. */
    const val CONNECTION_CLOSED = -32003
}

/** Thrown for an error response, and by handlers to answer with one. */
class JsonRpcException(val code: Int, message: String, val data: JsonElement? = null) : Exception(message) {
    fun toError(): JsonRpcError = JsonRpcError(code, message.orEmpty(), data)
}

/** The protocol's JSON: unknown members are skipped, so a newer peer can add fields; nulls are left out. */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    classDiscriminator = "type"
}

/** Reads and writes [JsonRpcMessage]s as JSON objects, and as single lines for stream transports. */
object JsonRpcCodec {
    fun encode(message: JsonRpcMessage): String = ProtocolJson.encodeToString(JsonObject.serializer(), toJson(message))

    fun decode(line: String): JsonRpcMessage {
        val element = try {
            ProtocolJson.parseToJsonElement(line)
        } catch (error: IllegalArgumentException) {
            throw JsonRpcException(JsonRpcErrorCodes.PARSE_ERROR, "not JSON: ${error.message}")
        }
        val json = element as? JsonObject ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_REQUEST, "not an object")
        return try {
            fromJson(json)
        } catch (error: IllegalArgumentException) {
            // An error member that is no error object, say: still a line that is not a message, never a crash.
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_REQUEST, error.message.orEmpty())
        }
    }

    /**
     * What a server answers a [line] that is not a message ([bad] tells why), so the client is not left waiting on
     * it: an error under the request's id when one can be read, null otherwise. A broken response gets no answer.
     */
    fun answerTo(line: String, bad: JsonRpcException): JsonRpcResponse? {
        val json = runCatching { ProtocolJson.parseToJsonElement(line) as? JsonObject }.getOrNull()
        if (json != null && "method" !in json) return null
        val id = json?.get("id") as? JsonPrimitive ?: JsonNull
        return JsonRpcResponse(id, error = bad.toError())
    }

    fun toJson(message: JsonRpcMessage): JsonObject = buildJsonObject {
        when (message) {
            is JsonRpcRequest -> {
                put("id", message.id)
                put("method", message.method)
                message.params?.let { put("params", it) }
            }
            is JsonRpcNotification -> {
                put("method", message.method)
                message.params?.let { put("params", it) }
            }
            is JsonRpcResponse -> {
                put("id", message.id)
                val error = message.error
                if (error != null) {
                    put("error", ProtocolJson.encodeToJsonElement(JsonRpcError.serializer(), error))
                } else {
                    put("result", message.result ?: JsonNull)
                }
            }
        }
    }

    fun fromJson(json: JsonObject): JsonRpcMessage {
        val id = json["id"]?.takeUnless { it is JsonNull }?.let { it as? JsonPrimitive ?: invalid("id is not a number or string") }
        val method = json["method"]?.let { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: invalid("method is not a string") }
        val params = json["params"]?.takeUnless { it is JsonNull }
        return when {
            method != null && id != null -> JsonRpcRequest(id, method, params)
            method != null -> JsonRpcNotification(method, params)
            // `"error": null` beside a result, as JSON-RPC 1.0 peers write it, is no error.
            id != null && json["error"].let { it != null && it !is JsonNull } -> JsonRpcResponse(
                id = id,
                error = ProtocolJson.decodeFromJsonElement(JsonRpcError.serializer(), json.getValue("error").jsonObject),
            )
            id != null && json.containsKey("result") -> JsonRpcResponse(id, result = json.getValue("result"))
            else -> invalid("neither a request, a notification nor a response")
        }
    }

    private fun invalid(why: String): Nothing = throw JsonRpcException(JsonRpcErrorCodes.INVALID_REQUEST, why)
}

/** A request id as the peers compare it: numbers and strings never collide. */
internal fun JsonPrimitive.idKey(): String = if (isString) "s:$content" else "n:${longOrNull ?: content}"

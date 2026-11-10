package cc.noharry.agent.acp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A JSON-RPC error answered to the client. */
internal class AcpError(val code: Int, override val message: String) : Exception(message)

internal const val PARSE_ERROR = -32700
internal const val INVALID_REQUEST = -32600
internal const val METHOD_NOT_FOUND = -32601
internal const val INVALID_PARAMS = -32602
internal const val INTERNAL_ERROR = -32603
internal const val RESOURCE_NOT_FOUND = -32002

/** Writes messages one at a time and matches the client's responses to the requests the agent sent. */
internal class AcpConnection(private val transport: AcpTransport) {
    private val writing = Mutex()
    private val waiting = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val ids = AtomicLong()

    suspend fun notify(method: String, params: JsonObject) = write(buildJsonObject {
        put("jsonrpc", "2.0"); put("method", method); put("params", params)
    })

    /** The result of a request to the client; throws [AcpError] when the client answers with an error. */
    suspend fun request(method: String, params: JsonObject): JsonObject {
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<JsonObject>()
        waiting[id] = answer
        try {
            write(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
            return answer.await()
        } finally {
            waiting.remove(id)
        }
    }

    suspend fun respond(id: JsonElement, result: JsonObject) = write(buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", result)
    })

    suspend fun fail(id: JsonElement, code: Int, message: String) = write(buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        put("error", buildJsonObject { put("code", code); put("message", message) })
    })

    /** Completes the request [message] answers; a response to nothing the agent asked is dropped. */
    fun resolve(message: JsonObject) {
        val id = (message["id"] as? JsonPrimitive)?.longOrNull ?: return
        waiting[id]?.settle(message)
    }

    /** The connection ended: nobody will answer what is still waiting. */
    fun closed() {
        waiting.values.forEach { it.completeExceptionally(AcpError(INTERNAL_ERROR, "The connection closed")) }
    }

    private suspend fun write(message: JsonObject) = writing.withLock { transport.send(message.toString()) }
}

private fun CompletableDeferred<JsonObject>.settle(message: JsonObject) {
    val error = message["error"] as? JsonObject
    if (error == null) {
        complete(message["result"] as? JsonObject ?: JsonObject(emptyMap()))
    } else {
        val code = (error["code"] as? JsonPrimitive)?.intOrNull ?: INTERNAL_ERROR
        completeExceptionally(AcpError(code, (error["message"] as? JsonPrimitive)?.content.orEmpty()))
    }
}

internal fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.requireText(name: String): String =
    text(name) ?: throw AcpError(INVALID_PARAMS, "$name is required")

internal fun JsonElement?.orNull(): JsonElement? = this.takeUnless { it is JsonNull }

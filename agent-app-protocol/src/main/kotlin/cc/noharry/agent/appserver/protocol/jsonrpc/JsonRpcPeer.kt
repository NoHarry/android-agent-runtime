package cc.noharry.agent.appserver.protocol.jsonrpc

import cc.noharry.agent.appserver.protocol.transport.MessageChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A request either side can send: its method name and how its params and result look on the wire. */
class RequestMethod<P, R>(val name: String, val params: KSerializer<P>, val result: KSerializer<R>)

/** A notification: its method name and how its params look on the wire. */
class NotificationMethod<P>(val name: String, val params: KSerializer<P>)

/**
 * One end of a connection, the same on the server and the client: sends requests and waits for their answers, sends
 * notifications, and hands what arrives to [onRequest] and [onNotification]. Notifications are handled one at a time
 * in the order they came, so state built from them stays consistent; each incoming request starts in arrival order
 * and then runs on its own, so one that takes long (or waits for a person, like an approval) holds up nothing else.
 */
class JsonRpcPeer(
    private val channel: MessageChannel,
    private val scope: CoroutineScope,
    private val onRequest: suspend (JsonRpcRequest) -> JsonElement?,
    private val onNotification: suspend (JsonRpcNotification) -> Unit,
    private val onClosed: () -> Unit = {},
    /** A notification handler that threw, or an answer that could not be sent; the connection goes on. */
    private val onError: (Throwable) -> Unit = {},
) {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private var loop: Job? = null

    fun start(): Job = scope.launch {
        try {
            for (message in channel.incoming) handle(message)
        } catch (closed: ClosedReceiveChannelException) {
            onError(closed)
        } finally {
            failPending()
            onClosed()
        }
    }.also { loop = it }

    suspend fun <P, R> request(method: RequestMethod<P, R>, params: P): R {
        val id = JsonPrimitive(nextId.getAndIncrement())
        val answer = CompletableDeferred<JsonElement>()
        pending[id.idKey()] = answer
        try {
            send(JsonRpcRequest(id, method.name, ProtocolJson.encodeToJsonElement(method.params, params)))
            val result = answer.await()
            return try {
                ProtocolJson.decodeFromJsonElement(method.result, result)
            } catch (unreadable: IllegalArgumentException) {
                // An answer that is not what [method] returns is refused like an error, which every caller handles.
                throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "unreadable answer to ${method.name}: ${unreadable.message}")
            }
        } finally {
            pending.remove(id.idKey())
        }
    }

    suspend fun <P> notify(method: NotificationMethod<P>, params: P) {
        send(JsonRpcNotification(method.name, ProtocolJson.encodeToJsonElement(method.params, params)))
    }

    fun close() {
        channel.close()
        loop?.cancel()
        failPending()
    }

    private suspend fun handle(message: JsonRpcMessage) {
        when (message) {
            is JsonRpcNotification -> runCatchingHandler { onNotification(message) }
            // Undispatched: each request runs up to its first suspension in arrival order, so a handler that hands
            // its work to one thread (the server's main) queues it in the order the requests came.
            is JsonRpcRequest -> scope.launch(start = CoroutineStart.UNDISPATCHED) { answer(message) }
            is JsonRpcResponse -> {
                val waiting = pending[message.id.idKey()] ?: return
                val error = message.error
                if (error != null) {
                    waiting.completeExceptionally(JsonRpcException(error.code, error.message, error.data))
                } else {
                    waiting.complete(message.result ?: JsonNull)
                }
            }
        }
    }

    private suspend fun answer(request: JsonRpcRequest) {
        val response = try {
            JsonRpcResponse(request.id, result = onRequest(request) ?: JsonNull)
        } catch (error: CancellationException) {
            throw error
        } catch (error: JsonRpcException) {
            JsonRpcResponse(request.id, error = error.toError())
        } catch (error: SerializationException) {
            JsonRpcResponse(request.id, error = JsonRpcError(JsonRpcErrorCodes.INVALID_PARAMS, error.message.orEmpty()))
        } catch (error: IllegalArgumentException) {
            JsonRpcResponse(request.id, error = JsonRpcError(JsonRpcErrorCodes.INVALID_PARAMS, error.message.orEmpty()))
        } catch (error: Exception) {
            JsonRpcResponse(request.id, error = JsonRpcError(JsonRpcErrorCodes.INTERNAL_ERROR, error.toString()))
        }
        runCatchingSend { send(response) }
    }

    private suspend fun send(message: JsonRpcMessage) {
        try {
            channel.send(message)
        } catch (error: ClosedSendChannelException) {
            throw JsonRpcException(JsonRpcErrorCodes.CONNECTION_CLOSED, "connection closed")
        }
    }

    private suspend fun runCatchingHandler(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            // A notification has no one to answer; one the handler cannot take must not end the connection.
            onError(error)
        }
    }

    private suspend fun runCatchingSend(block: suspend () -> Unit) {
        try {
            block()
        } catch (gone: JsonRpcException) {
            // The asker is gone; there is no one to answer.
            onError(gone)
        }
    }

    private fun failPending() {
        val closed = JsonRpcException(JsonRpcErrorCodes.CONNECTION_CLOSED, "connection closed")
        pending.values.forEach { it.completeExceptionally(closed) }
        pending.clear()
    }
}

/** Decodes a request's params as [method] describes them; a request without params reads as `{}`. */
fun <P, R> JsonRpcRequest.paramsOf(method: RequestMethod<P, R>): P =
    ProtocolJson.decodeFromJsonElement(method.params, params ?: kotlinx.serialization.json.JsonObject(emptyMap()))

/** Encodes [result] as [method]'s answer. */
fun <P, R> resultOf(method: RequestMethod<P, R>, result: R): JsonElement = ProtocolJson.encodeToJsonElement(method.result, result)

/** Decodes a notification's params as [method] describes them. */
fun <P> JsonRpcNotification.paramsOf(method: NotificationMethod<P>): P =
    ProtocolJson.decodeFromJsonElement(method.params, params ?: kotlinx.serialization.json.JsonObject(emptyMap()))

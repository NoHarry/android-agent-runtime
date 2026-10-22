package cc.noharry.agent.a2a.server

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * Candidate A2A 0.3 JSON-RPC/SSE loopback server. Owns bounded in-memory tasks and workers.
 * Start once and [close] with the host lifecycle. It does not create an Android service.
 */
class A2aServer(
    private val config: A2aServerConfig = A2aServerConfig(),
    handler: A2aRequestHandler,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = A2aTaskStore(config, scope, handler)
    private val clients = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val workers = ThreadPoolExecutor(
        0, config.maxActiveTasks + CONTROL_WORKERS, WORKER_IDLE_SECONDS, TimeUnit.SECONDS, SynchronousQueue(),
        { action -> Thread(action, "a2a-http").apply { isDaemon = true } },
    )
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var closed = false

    /** Actual port after [start], including when port zero requests an ephemeral test port. */
    val localPort: Int get() = listener?.localPort ?: config.port

    /** Bind IPv4 loopback and return the listening port. Bind errors are propagated to the host. */
    @Synchronized
    fun start(): Int {
        check(!closed) { "Server is closed" }
        listener?.let { return it.localPort }
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), config.port))
        } catch (error: Exception) {
            socket.close()
            throw error
        }
        listener = socket
        Thread({ accept(socket) }, "a2a-listener").apply { isDaemon = true; start() }
        return socket.localPort
    }

    /** Idempotently stop listening, close all sockets, and cancel host task flows. */
    @Synchronized
    override fun close() {
        closed = true
        listener?.close()
        listener = null
        scope.cancel()
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }

    private fun accept(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try { server.accept() } catch (_: java.io.IOException) { break }
            clients.add(client)
            try {
                workers.execute { serve(client) }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                clients.remove(client)
                client.close()
            }
        }
    }

    private fun serve(client: Socket) {
        // Socket writes are blocking. A separate watchdog bounds even a client that stops reading.
        val watchdog = scope.launch { delay(config.taskTimeoutMillis + HTTP_TIMEOUT_MS); client.close() }
        try {
            client.use { socket ->
                try { runBlocking { route(socket, readHttp(socket)) } } catch (error: A2aHttpError) {
                    writeJson(socket.getOutputStream(), jsonObject("error" to text("Invalid HTTP request")), error.status)
                }
            }
        } catch (_: Exception) {
            // A disconnected client has no writable error channel; its task is canceled in stream().
            runCatching { client.close() }
        } finally {
            watchdog.cancel()
            client.close()
            clients.remove(client)
        }
    }

    private suspend fun route(socket: Socket, http: A2aHttpRequest) {
        val output = socket.getOutputStream()
        if (http.method == "GET" && http.path in CARD_PATHS) {
            writeJson(output, agentCard(config, localPort))
            return
        }
        if (http.path != RPC_PATH || http.method != "POST") {
            writeJson(output, jsonObject("error" to text("Not found")), 404)
            return
        }
        var id: JsonElement = JsonNull
        try {
            val rpc = parseRpc(http.body)
            id = rpc.id
            dispatch(socket, rpc)
        } catch (error: A2aRpcError) {
            writeJson(output, rpcError(id, error))
        }
    }

    private suspend fun dispatch(socket: Socket, rpc: A2aRpc) {
        when (rpc.method) {
            "message/send", "message/stream" -> sendMessage(socket, rpc)
            "tasks/get" -> writeJson(socket.getOutputStream(), rpcResult(rpc.id, store.get(taskId(rpc)).snapshot()))
            "tasks/cancel" -> writeJson(socket.getOutputStream(), rpcResult(rpc.id, store.cancel(taskId(rpc)).snapshot()))
            else -> throw A2aRpcError(-32601, "Method not found")
        }
    }

    private suspend fun sendMessage(socket: Socket, rpc: A2aRpc) {
        val (input, context) = parseText(rpc.params)
        val streaming = rpc.method == "message/stream"
        val message = rpc.params["message"] as JsonObject
        val existingTaskId = message.string("taskId")
        if (existingTaskId != null) {
            if (streaming) invalidParams("Use message/send for active task input; the original stream stays open")
            val metadata = message["metadata"] as? JsonObject
            if (message["metadata"] != null && metadata == null) invalidParams("Invalid metadata")
            val questionId = metadata?.get("questionId")?.let {
                metadata.string("questionId")?.takeIf(String::isNotBlank) ?: invalidParams("Invalid questionId")
            }
            val receipt = store.postInput(existingTaskId, context, requireNotNull(message.string("messageId")), input, questionId)
            writeJson(socket.getOutputStream(), rpcResult(rpc.id, receipt))
            return
        }
        val metadata = messageMetadata(message)
        val task = if (metadata[INTERRUPT_METADATA] == "true") {
            store.interrupt(input, context, streaming, metadata)
        } else {
            store.start(input, context, streaming, metadata)
        }
        if (streaming) {
            stream(socket, rpc.id, task)
        } else {
            val configuration = rpc.params["configuration"] as? JsonObject
            if ((configuration?.get("blocking") as? JsonPrimitive)?.booleanOrNull != false) task.done.await()
            writeJson(socket.getOutputStream(), rpcResult(rpc.id, task.snapshot()))
        }
    }

    private suspend fun stream(socket: Socket, id: JsonElement, task: A2aTask) {
        try {
            val output = socket.getOutputStream()
            startSse(output)
            writeSse(output, rpcResult(id, task.initialSnapshot))
            val events = requireNotNull(task.events)
            while (true) {
                val next = withTimeoutOrNull(HEARTBEAT_MS) { events.receiveCatching() }
                if (next?.isClosed == true) break
                writeSse(output, next?.getOrNull()?.let { rpcResult(id, it) })
            }
        } finally {
            if (!task.done.isCompleted) task.job.cancel()
            task.events?.cancel()
        }
    }

    private fun taskId(rpc: A2aRpc): String = rpc.params.string("id") ?: invalidParams("Task id is required")

    private companion object {
        const val RPC_PATH = "/a2a"
        val CARD_PATHS = setOf("/.well-known/agent-card.json", "/.well-known/agent.json")
        const val CONTROL_WORKERS = 4
        const val WORKER_IDLE_SECONDS = 30L
        const val HEARTBEAT_MS = 1_000L
    }
}

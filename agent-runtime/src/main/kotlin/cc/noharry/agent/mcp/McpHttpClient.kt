package cc.noharry.agent.mcp

import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import cc.noharry.agent.transport.HttpTransport
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A standard MCP server over Streamable HTTP (MCP 2025-06-18). Every message is one JSON-RPC POST; the reply is JSON
 * or a short SSE stream whose `data:` event with the same id is the response. `initialize` comes first, and the
 * `Mcp-Session-Id` it returns rides on every later request. A 404 for that session means the server ended it, so the
 * client initializes again once. [headers] and [query] are read for each request, so a changed token applies at once;
 * [query] is added to [url] only when a request is sent, so a key never sits in the stored address. A server that could
 * not be reached at all is not tried again for [unreachableCooldownMillis], and with a [retryScope] never in a caller's
 * way again: connecting says at once that it is unreachable and tries anew in the background, so the next caller finds
 * it back. A run connects to every switched-on server first, and one the device's network cannot reach would otherwise
 * hold each run up. [messages] words what went wrong. Candidate API, not frozen.
 */
class McpHttpClient(
    private val url: String,
    private val headers: () -> Map<String, String>,
    private val http: HttpTransport,
    private val clientName: String,
    private val clientVersion: String,
    private val query: () -> Map<String, String> = { emptyMap() },
    private val unreachableCooldownMillis: Long = 0L,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val retryScope: CoroutineScope? = null,
    private val messages: McpClientMessages = McpClientMessages.Default,
) : McpRpcClient {
    private val ids = AtomicInteger(1)
    private val lock = Mutex()

    /** When the server last could not be reached, and what connecting said then. */
    @Volatile
    private var unreachable: Pair<Long, McpConnectResult>? = null

    @Volatile
    private var session: Session? = null

    private data class Session(val id: String?, val protocolVersion: String)

    private class Reply(val status: Int, val body: JsonObject?, val raw: String, val sessionId: String?, val problem: String?)

    @Volatile
    private var retrying = false

    override suspend fun connect(): McpConnectResult {
        // Known unreachable: said at once, without waiting on the lock an attempt in flight holds.
        val down = unreachable.takeIf { session == null }
        if (down != null) {
            val (at, said) = down
            if (nowMillis() - at < unreachableCooldownMillis) return said
            retryScope?.let { scope -> return said.also { retryInBackground(scope) } }
        }
        return lock.withLock { if (session != null) OK_RESULT else initialize() }
    }

    private fun retryInBackground(scope: CoroutineScope) {
        if (retrying) return
        retrying = true
        scope.launch {
            try {
                lock.withLock { if (session == null) initialize() }
            } finally {
                retrying = false
            }
        }
    }

    override suspend fun sendRequest(method: String, params: JsonObject): McpRpcResponse {
        connect().takeIf { !it.ok }?.let { return McpRpcResponse(it.statusCode, "", null, it.errorMessage) }
        var reply = exchange(rpcRequest(method, params), session)
        if (reply.status == HTTP_NOT_FOUND && session?.id != null) {
            val again = lock.withLock {
                session = null
                initialize()
            }
            if (!again.ok) return McpRpcResponse(again.statusCode, "", null, again.errorMessage)
            reply = exchange(rpcRequest(method, params), session)
        }
        reply.problem?.let { return McpRpcResponse(McpStatus.CONNECT_ERROR, reply.raw, null, it) }
        val body = reply.body ?: return McpRpcResponse(McpStatus.CONNECT_ERROR, reply.raw, null, messages.notJsonRpc())
        return McpRpcResponse(McpStatus.OK, reply.raw, body, null)
    }

    /** Forgets the session (and that the server could not be reached); the next request starts a new one. */
    override fun close() {
        session = null
        unreachable = null
    }

    private suspend fun initialize(): McpConnectResult {
        val params = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", buildJsonObject {
                put("name", clientName)
                put("version", clientVersion)
            })
        }
        val reply = exchange(rpcRequest("initialize", params), null, INITIALIZE_TIMEOUT_MS)
        reply.problem?.let { problem ->
            return McpConnectResult(McpStatus.CONNECT_ERROR, reply.raw, problem).also { failed ->
                // No HTTP answer at all (timed out, refused, no route); a server that answered with an error is tried again.
                unreachable = if (reply.status == 0) nowMillis() to failed else null
            }
        }
        unreachable = null
        // A gateway that refuses the key may answer 200 with its own JSON (such as {"info":"INVALID_USER_KEY"}); show it.
        val result = reply.body?.get("result") as? JsonObject
            ?: return McpConnectResult(McpStatus.CONNECT_ERROR, reply.raw, reply.body?.errorText() ?: messages.initializeRefused(reply.raw.trim().take(RAW_HINT_CHARS)))
        val version = (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull ?: PROTOCOL_VERSION
        val established = Session(reply.sessionId, version)
        // 202 Accepted with no body; a server that refuses it shows that on the next request.
        exchange(buildJsonObject {
            put("jsonrpc", JSON_RPC_VERSION)
            put("method", "notifications/initialized")
        }, established, INITIALIZE_TIMEOUT_MS)
        session = established
        return OK_RESULT
    }

    private fun rpcRequest(method: String, params: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", JSON_RPC_VERSION)
        put("id", ids.getAndIncrement())
        put("method", method)
        put("params", params)
    }

    private suspend fun exchange(message: JsonObject, session: Session?, timeoutMillis: Long = EXCHANGE_TIMEOUT_MS): Reply {
        val requestHeaders = buildMap {
            putAll(headers())
            put("Content-Type", "application/json")
            put("Accept", "application/json, text/event-stream")
            session?.let {
                put("MCP-Protocol-Version", it.protocolVersion)
                it.id?.let { id -> put("Mcp-Session-Id", id) }
            }
        }
        val response = try {
            // A dead or hung server must not hold up every run (and the client's lock) for OkHttp's minutes.
            withTimeoutOrNull(timeoutMillis) {
                http.execute(HttpRequest("POST", withQuery(url, query()), requestHeaders, message.toString().toByteArray(Charsets.UTF_8)))
            } ?: return Reply(0, null, "", null, messages.timedOut(timeoutMillis / 1000))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Reply(0, null, "", null, messages.unreachable(error.message ?: error.javaClass.simpleName))
        }
        val raw = response.body.toString(Charsets.UTF_8)
        val sessionId = response.header("Mcp-Session-Id")
        messages.httpProblem(response.statusCode)?.let { return Reply(response.statusCode, null, raw, sessionId, it) }
        val id = message["id"] ?: return Reply(response.statusCode, null, raw, sessionId, null)
        val body = if (response.header("Content-Type")?.startsWith("text/event-stream") == true) sseResponse(raw, id) else jsonObject(raw)
        return Reply(response.statusCode, body, raw, sessionId, null)
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        /** One request and its reply; a tool doing real work answers well inside it. */
        private const val EXCHANGE_TIMEOUT_MS = 30_000L
        /** Opening a session is quick for any server that is up; a run waits this long at most for one that is not. */
        private const val INITIALIZE_TIMEOUT_MS = 10_000L
        private const val JSON_RPC_VERSION = "2.0"
        private const val HTTP_NOT_FOUND = 404
        private const val RAW_HINT_CHARS = 120
        private val OK_RESULT = McpConnectResult(McpStatus.OK)
    }
}

/** [url] with [query] set on it (replacing a parameter of the same name); unchanged when there is none. */
internal fun withQuery(url: String, query: Map<String, String>): String {
    if (query.isEmpty()) return url
    val parsed = url.toHttpUrlOrNull() ?: return url
    return parsed.newBuilder().apply { query.forEach { (name, value) -> setQueryParameter(name, value) } }.build().toString()
}

/** The JSON-RPC response with [id] among a `text/event-stream` body's events (notifications may come first). */
internal fun sseResponse(stream: String, id: JsonElement): JsonObject? =
    stream.replace("\r\n", "\n").split("\n\n").asSequence()
        .map { event -> event.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").removePrefix(" ") } }
        .filter { it.isNotBlank() }
        .mapNotNull(::jsonObject)
        .firstOrNull { it["id"] == id && (it["result"] != null || it["error"] != null) }

private fun jsonObject(text: String): JsonObject? =
    try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun JsonObject.errorText(): String? =
    ((this["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull

private fun HttpResponse.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

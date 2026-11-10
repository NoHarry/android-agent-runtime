@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.acp

import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.AgentThread
import cc.noharry.agent.ApprovalPolicy
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.ThreadOptions
import cc.noharry.agent.TurnError
import cc.noharry.agent.TurnStatus
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the agent tells a client about itself and how its sessions start. [threadOptions] gives a new session's
 * thread its defaults from the client's working directory (the model, a workspace, instructions).
 */
@ExperimentalAgentRuntimeApi
data class AcpAgentConfig(
    val name: String = "agent",
    val title: String? = null,
    val version: String = "0",
    val threadOptions: (cwd: String) -> ThreadOptions = { ThreadOptions() },
    /** Whether clients may send images; off for a model that takes none. */
    val imagePrompts: Boolean = true,
    val messages: AcpAgentMessages = AcpAgentMessages(),
)

/** The words a client shows for the agent's choices: English unless the host words them in its own language. */
@ExperimentalAgentRuntimeApi
data class AcpAgentMessages(
    val allowOnce: String = "Allow",
    val allowAlways: String = "Always allow",
    val reject: String = "Reject",
    val search: String = "Search",
    val modeAsk: String = "Ask",
    val modeRemember: String = "Ask once",
    val modeApproveAll: String = "Approve all",
    val modeDenyAll: String = "Deny all",
)

/**
 * Serves [runtime]'s threads over the Agent Client Protocol (protocol version 1), so an editor or any other ACP client
 * can drive them: a session is a thread, a prompt is a turn, the turn's items go out as `session/update`, an approval
 * is a `session/request_permission`, and the session modes are the approval policies. A call a hook marks to always
 * ask is a permission request in every mode, approve-all included, without the "always" option.
 *
 * The client's file system, terminals and MCP servers are not used: the agent works with the runtime's own workspace
 * and tools. Audio prompts are refused.
 *
 * Experimental until it has been used with the clients it is for; its configuration may change in a minor release.
 */
@ExperimentalAgentRuntimeApi
class AcpAgent(private val runtime: AgentRuntime, private val config: AcpAgentConfig = AcpAgentConfig()) {
    /** Serves one connection until the client closes it; turns still running then are interrupted. */
    suspend fun serve(transport: AcpTransport): Unit = coroutineScope {
        val session = AcpServing(runtime, config, AcpConnection(transport), this)
        try {
            while (true) {
                val line = transport.receive() ?: break
                if (line.isNotBlank()) session.accept(line)
            }
        } finally {
            session.connection.closed()
            coroutineContext.job.cancelChildren()
            runCatching { transport.close() }
        }
    }

    /** Serves the process's standard input and output, as an editor that starts the agent expects. */
    suspend fun serveStdio(): Unit = serve(StreamAcpTransport(System.`in`, System.out))
}

/** One connection's sessions and requests. */
internal class AcpServing(
    private val runtime: AgentRuntime,
    private val config: AcpAgentConfig,
    val connection: AcpConnection,
    private val scope: CoroutineScope,
) {
    private val threads = ConcurrentHashMap<String, AgentThread>()
    /** Prompts accepted and not answered yet, by session, and the sessions whose next turn is to be cancelled. */
    private val prompts = mutableMapOf<String, Int>()
    private val cancels = mutableSetOf<String>()
    private val promptLock = Any()

    suspend fun accept(line: String) {
        val message = try { Json.parseToJsonElement(line) as? JsonObject } catch (_: IllegalArgumentException) { null }
            ?: return connection.fail(kotlinx.serialization.json.JsonNull, PARSE_ERROR, "Not a JSON object")
        val method = message.text("method") ?: return connection.resolve(message)
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        val id = message["id"].orNull()
        // Notifications are handled in the order they came; each request runs on its own, since a prompt lasts a whole
        // turn while cancels and permission answers come in.
        if (id == null) return notified(method, params)
        if (method == "session/prompt") params.text("sessionId")?.let { session -> synchronized(promptLock) { prompts[session] = (prompts[session] ?: 0) + 1 } }
        scope.launch { answer(id, method, params) }
    }

    @Suppress("TooGenericExceptionCaught") // A notification has nobody to report to, and must not end the connection.
    private suspend fun notified(method: String, params: JsonObject) {
        if (method != "session/cancel") return
        val id = params.text("sessionId") ?: return
        // A prompt accepted but not started yet is cancelled when its turn starts.
        synchronized(promptLock) { if ((prompts[id] ?: 0) > 0) cancels += id }
        try {
            threads[id]?.interrupt()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }

    private suspend fun answer(id: JsonElement, method: String, params: JsonObject) {
        try {
            connection.respond(id, handle(method, params))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AcpError) {
            connection.fail(id, error.code, error.message)
        } catch (error: Exception) {
            connection.fail(id, INTERNAL_ERROR, error.message ?: error.javaClass.simpleName)
        }
    }

    private suspend fun handle(method: String, params: JsonObject): JsonObject = when (method) {
        "initialize" -> initialize()
        "authenticate" -> JsonObject(emptyMap())
        "session/new" -> newSession(params)
        "session/load" -> loadSession(params)
        "session/prompt" -> prompt(params)
        "session/set_mode" -> setMode(params)
        else -> throw AcpError(METHOD_NOT_FOUND, "Unknown method: $method")
    }

    private fun initialize(): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        put("agentCapabilities", buildJsonObject {
            put("loadSession", true)
            put("promptCapabilities", buildJsonObject { put("image", config.imagePrompts); put("audio", false); put("embeddedContext", true) })
        })
        put("agentInfo", buildJsonObject {
            put("name", config.name); config.title?.let { put("title", it) }; put("version", config.version)
        })
        put("authMethods", JsonArray(emptyList()))
    }

    private suspend fun newSession(params: JsonObject): JsonObject {
        val options = config.threadOptions(params.text("cwd").orEmpty())
        val thread = withContext(Dispatchers.IO) { runtime.startThread(options) }
        threads[thread.id] = thread
        return buildJsonObject {
            put("sessionId", thread.id)
            put("modes", modes(options.approvalPolicy ?: ApprovalPolicy.ASK))
        }
    }

    private suspend fun loadSession(params: JsonObject): JsonObject {
        val id = params.requireText("sessionId")
        if (runtime.listThreads().none { it.id == id }) throw AcpError(RESOURCE_NOT_FOUND, "No session $id")
        val thread = withContext(Dispatchers.IO) { runtime.resumeThread(id) }
        threads[id] = thread
        val history = AcpTurn(connection, id, config.messages, scope)
        thread.items().forEach { history.replay(it) }
        return JsonObject(emptyMap())
    }

    private suspend fun prompt(params: JsonObject): JsonObject {
        val id = params.requireText("sessionId")
        try {
            return runPrompt(id, params)
        } finally {
            synchronized(promptLock) {
                val left = (prompts[id] ?: 1) - 1
                if (left > 0) prompts[id] = left else { prompts -= id; cancels -= id }
            }
        }
    }

    private suspend fun runPrompt(id: String, params: JsonObject): JsonObject {
        val thread = threads[id] ?: throw AcpError(INVALID_PARAMS, "Unknown session $id: create or load it first")
        val input = promptInput(params["prompt"] as? JsonArray ?: throw AcpError(INVALID_PARAMS, "prompt is required"))
        val turn = AcpTurn(connection, id, config.messages, scope)
        var status = TurnStatus.FAILED
        var error: TurnError? = null
        thread.runStreamed(input).collect { event ->
            if (event is ThreadEvent.TurnStarted && synchronized(promptLock) { cancels.remove(id) }) thread.interrupt()
            turn.on(event)
            if (event is ThreadEvent.TurnCompleted) { status = event.result.status; error = event.result.error }
            if (event is ThreadEvent.TurnFailed) error = event.error
        }
        return buildJsonObject { put("stopReason", stopReason(status, error)) }
    }

    private fun stopReason(status: TurnStatus, error: TurnError?): String = when {
        status == TurnStatus.INTERRUPTED -> "cancelled"
        // A question ends the turn like an answer does: the next prompt answers it.
        status == TurnStatus.COMPLETED || status == TurnStatus.WAITING_FOR_ANSWER -> "end_turn"
        error?.code == LOOP_BUDGET_CODE -> "max_turn_requests"
        else -> throw AcpError(INTERNAL_ERROR, listOfNotNull(error?.code, error?.message).joinToString(": ").ifEmpty { "The turn failed" })
    }

    private suspend fun setMode(params: JsonObject): JsonObject {
        val id = params.requireText("sessionId")
        val mode = params.requireText("modeId")
        val policy = ApprovalPolicy.entries.firstOrNull { it.modeId() == mode } ?: throw AcpError(INVALID_PARAMS, "Unknown mode $mode")
        if (!threads.containsKey(id)) throw AcpError(INVALID_PARAMS, "Unknown session $id: create or load it first")
        // The policy reaches a turn already running, an approval it waits for included.
        threads[id] = withContext(Dispatchers.IO) { runtime.resumeThread(id, ThreadOptions(approvalPolicy = policy)) }
        return JsonObject(emptyMap())
    }

    private fun modes(current: ApprovalPolicy): JsonObject = buildJsonObject {
        put("currentModeId", current.modeId())
        put("availableModes", buildJsonArray {
            ApprovalPolicy.entries.forEach { policy ->
                add(buildJsonObject { put("id", policy.modeId()); put("name", policy.label()) })
            }
        })
    }

    private fun ApprovalPolicy.modeId(): String = name.lowercase()

    private fun ApprovalPolicy.label(): String = when (this) {
        ApprovalPolicy.ASK -> config.messages.modeAsk
        ApprovalPolicy.REMEMBER -> config.messages.modeRemember
        ApprovalPolicy.APPROVE_ALL -> config.messages.modeApproveAll
        ApprovalPolicy.DENY_ALL -> config.messages.modeDenyAll
    }

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val LOOP_BUDGET_CODE = "MODEL_LOOP_BUDGET_EXCEEDED"
    }
}

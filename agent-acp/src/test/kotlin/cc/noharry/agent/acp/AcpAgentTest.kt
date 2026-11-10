@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.acp

import cc.noharry.agent.runtime.AgentRuntimeBuilder
import cc.noharry.agent.runtime.functionTools
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.HookDecision
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.ToolMatcher
import cc.noharry.agent.spi.ToolUse
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcpAgentTest {
    @Test fun aPromptStreamsTheTurnAndAnApprovedToolRuns() = acp { client, notes ->
        val init = client.call("initialize", buildJsonObject { put("protocolVersion", 1) })
        assertEquals(1, init.getValue("protocolVersion").jsonPrimitive.int)
        assertTrue(init.getValue("agentCapabilities").jsonObject.getValue("loadSession").jsonPrimitive.content.toBoolean())
        val session = client.newSession()
        client.onPermission = { "allow_once" }

        val result = client.prompt(session, "Remember to buy milk")

        assertEquals("end_turn", result.text("stopReason"))
        assertEquals(listOf("buy milk"), notes)
        val asked = client.permissions.single()
        val saved = client.updates("tool_call").single { it.text("title") == "notes: save" }
        assertEquals(saved.text("toolCallId"), asked.getValue("toolCall").jsonObject.text("toolCallId"))
        assertEquals(listOf("allow_once", "allow_always", "reject_once"), asked.getValue("options").jsonArray.map { it.jsonObject.text("kind") })
        assertEquals("completed", client.updates("tool_call_update").last { it.text("toolCallId") == saved.text("toolCallId") }.text("status"))
        assertEquals("Saved.", client.said())
        // finish is the answer, not a tool the client shows.
        assertTrue(client.updates("tool_call").none { it.text("title") == "finish" })
    }

    @Test fun aRejectedToolDoesNotRunAndTheTurnStillEnds() = acp { client, notes ->
        val session = client.newSession()
        client.onPermission = { "reject_once" }
        assertEquals("end_turn", client.prompt(session, "Remember to buy milk").text("stopReason"))
        assertTrue(notes.isEmpty())
        assertEquals("Not saved.", client.said())
    }

    @Test fun aModeIsAnApprovalPolicy() = acp { client, notes ->
        val session = client.newSession()
        client.call("session/set_mode", buildJsonObject { put("sessionId", session); put("modeId", "approve_all") })
        assertEquals("end_turn", client.prompt(session, "Remember to buy milk").text("stopReason"))
        assertTrue(client.permissions.isEmpty())
        assertEquals(listOf("buy milk"), notes)
    }

    @Test fun cancellingWhileThePermissionWaitsEndsThePromptAsCancelled() = acp { client, notes ->
        val session = client.newSession()
        client.onPermission = {
            client.send(buildJsonObject { put("jsonrpc", "2.0"); put("method", "session/cancel"); put("params", buildJsonObject { put("sessionId", session) }) })
            null
        }
        assertEquals("cancelled", client.prompt(session, "Remember to buy milk").text("stopReason"))
        assertTrue(notes.isEmpty())
    }

    @Test fun aQuestionEndsTheTurnAndTheNextPromptAnswersIt() = acp { client, _ ->
        val session = client.newSession()
        assertEquals("end_turn", client.prompt(session, "ask me first").text("stopReason"))
        assertEquals("Which list?", client.said())
        client.clear()
        assertEquals("end_turn", client.prompt(session, "the shopping list").text("stopReason"))
        assertEquals("Answered: the shopping list", client.said())
    }

    @Test fun aLoadedSessionReplaysItsHistory() {
        val storage = Files.createTempDirectory("acp-load").toFile()
        var session = ""
        acp(storage) { client, _ ->
            session = client.newSession()
            client.onPermission = { "allow_once" }
            client.prompt(session, "Remember to buy milk")
        }
        acp(storage) { client, _ ->
            client.call("session/load", buildJsonObject { put("sessionId", session); put("cwd", "/"); put("mcpServers", JsonArray(emptyList())) })
            assertEquals("Remember to buy milk", client.updates("user_message_chunk").single().getValue("content").jsonObject.text("text"))
            assertEquals("Saved.", client.said())
            assertTrue(client.updates("tool_call").any { it.text("title") == "notes: save" })
            val missing = client.error("session/load", buildJsonObject { put("sessionId", "nope") })
            assertEquals(RESOURCE_NOT_FOUND, missing.getValue("code").jsonPrimitive.int)
        }
    }

    @Test fun whatTheAgentDoesNotTakeIsRefusedByItsCode() = acp { client, _ ->
        assertEquals(METHOD_NOT_FOUND, client.error("fs/read_text_file", JsonObject(emptyMap())).getValue("code").jsonPrimitive.int)
        val prompt = buildJsonObject { put("sessionId", "unknown"); put("prompt", buildJsonArray { add(textBlock("hi")) }) }
        assertEquals(INVALID_PARAMS, client.error("session/prompt", prompt).getValue("code").jsonPrimitive.int)
        val session = client.newSession()
        val audio = buildJsonObject {
            put("sessionId", session)
            put("prompt", buildJsonArray { add(buildJsonObject { put("type", "audio"); put("data", ""); put("mimeType", "audio/wav") }) })
        }
        assertEquals(INVALID_PARAMS, client.error("session/prompt", audio).getValue("code").jsonPrimitive.int)
        client.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", 999); put("result", JsonObject(emptyMap())) })
        assertNull(client.call("authenticate", JsonObject(emptyMap()))["error"])
        assertFalse(client.updates("tool_call").any())
    }
}

/** Saves a note behind an approval, or asks first when told to; finishes by what the save returned. */
private class NotesModel : ModelClient {
    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        val last = request.messages.last()
        val asked = request.messages.any { it.role == "tool" && it.toolName == "ask" }
        val call = when {
            last.role == "tool" && last.toolName == "search_tools" -> ModelEvent.ToolCall("c2", request.tools.first { it.name.endsWith("__save") }.name, """{"text":"buy milk"}""")
            last.role == "tool" && last.toolName?.endsWith("__save") == true ->
                ModelEvent.ToolCall("c3", "finish", if ("\"ok\":false" in last.content || "DENIED" in last.content) """{"response":"Not saved."}""" else """{"response":"Saved."}""")
            asked -> ModelEvent.ToolCall("c5", "finish", """{"response":"Answered: ${request.messages.last { it.role == "user" }.content.substringAfterLast("User reply:").trim()}"}""")
            "ask me first" in last.content -> ModelEvent.ToolCall("c4", "ask", """{"question":"Which list?"}""")
            else -> ModelEvent.ToolCall("c1", "search_tools", """{"query":"note"}""")
        }
        emit(call)
        emit(ModelEvent.Completed)
    }
}

private class ConfirmSaving : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
        if (context.approvedConfirmation) HookDecision.Allow() else HookDecision.RequireConfirmation("saving", "Save this note?")
}

private fun acp(storage: File? = null, body: suspend (AcpTestClient, List<String>) -> Unit) = runBlocking {
    val notes = mutableListOf<String>()
    val tools = functionTools("notes") {
        tool("save", "Saves a note.", inputSchema = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""") { args ->
            notes += args.getValue("text").jsonPrimitive.content; "saved"
        }
    }
    val builder = AgentRuntimeBuilder.create().model(NotesModel()).tools(tools).hook(ConfirmSaving(), ToolMatcher.toolName("mcp__notes__save"))
    storage?.let { builder.storage(it) }
    val runtime = builder.build()
    val scope = CoroutineScope(Dispatchers.Default)
    val client = AcpTestClient(scope)
    val serving = scope.launch { AcpAgent(runtime, AcpAgentConfig(name = "test")).serve(client.transport) }
    try {
        withTimeout(30_000) { body(client, notes) }
    } finally {
        client.toAgent.close()
        withTimeout(10_000) { serving.join() }
        scope.cancel()
        runtime.close()
    }
}

/** The client's end of an in-memory connection: calls, the updates it was sent, and its permission answers. */
private class AcpTestClient(scope: CoroutineScope) {
    val toAgent = Channel<String>(Channel.UNLIMITED)
    private val fromAgent = Channel<JsonObject>(Channel.UNLIMITED)
    private val answers = Channel<JsonObject>(Channel.UNLIMITED)
    private val received = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
    val permissions: MutableList<JsonObject> = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
    @Volatile var onPermission: suspend (JsonObject) -> String? = { "allow_once" }
    private var nextId = 0

    val transport = object : AcpTransport {
        override suspend fun receive(): String? = toAgent.receiveCatching().getOrNull()
        override suspend fun send(message: String) { fromAgent.send(Json.parseToJsonElement(message).jsonObject) }
        override fun close() = Unit
    }

    init {
        scope.launch {
            for (message in fromAgent) when {
                message.text("method") == "session/update" -> received += message.getValue("params").jsonObject.getValue("update").jsonObject
                message.text("method") == "session/request_permission" -> launch { permission(message) }
                else -> answers.send(message)
            }
        }
    }

    private suspend fun permission(request: JsonObject) {
        val params = request.getValue("params").jsonObject
        permissions += params
        val option = onPermission(params)
        val outcome = if (option == null) buildJsonObject { put("outcome", "cancelled") }
            else buildJsonObject { put("outcome", "selected"); put("optionId", option) }
        send(buildJsonObject { put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", buildJsonObject { put("outcome", outcome) }) })
    }

    fun send(message: JsonObject) { toAgent.trySend(message.toString()) }

    suspend fun exchange(method: String, params: JsonObject): JsonObject {
        val id = ++nextId
        send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
        val answer = answers.receive()
        assertEquals(JsonPrimitive(id), answer["id"])
        return answer
    }

    fun updates(kind: String): List<JsonObject> = synchronized(received) { received.filter { it.text("sessionUpdate") == kind } }

    fun clear() = received.clear()
}

private suspend fun AcpTestClient.call(method: String, params: JsonObject): JsonObject =
    exchange(method, params).let { it["result"] as? JsonObject ?: error("$method failed: ${it["error"]}") }

private suspend fun AcpTestClient.error(method: String, params: JsonObject): JsonObject =
    exchange(method, params).getValue("error").jsonObject

private suspend fun AcpTestClient.newSession(): String =
    call("session/new", buildJsonObject { put("cwd", "/"); put("mcpServers", JsonArray(emptyList())) }).requireText("sessionId")

private suspend fun AcpTestClient.prompt(session: String, text: String): JsonObject =
    call("session/prompt", buildJsonObject { put("sessionId", session); put("prompt", buildJsonArray { add(textBlock(text)) }) })

private fun AcpTestClient.said(): String =
    updates("agent_message_chunk").joinToString("") { it.getValue("content").jsonObject.text("text").orEmpty() }

@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.acp.AcpAgent
import cc.noharry.agent.acp.AcpAgentConfig
import cc.noharry.agent.acp.AcpTransport
import cc.noharry.agent.appserver.console.fixtureProfile
import cc.noharry.agent.appserver.console.fixtureProviderConfig
import cc.noharry.agent.appserver.console.noopDiagnostics
import cc.noharry.agent.appserver.model.ScriptedModel
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.appWorkspaceRoot
import cc.noharry.agent.appserver.runtime.buildRuntime
import cc.noharry.agent.appserver.trajectory.appSessionEventLogRoot
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ACP socket's sessions follow the server: each prompt runs on a runtime built when it starts, so a plugin switched
 * off between two prompts of one connection is gone from the second (its tool refused as `TOOL_UNAVAILABLE`) and back
 * in the third; a session loaded on a new connection replays what was said.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AcpTurnsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shelf = Shelf()
    private val plugin = PluginAdditions(tools = listOf(PluginTools(false) { _, _ -> listOf(shelf) }))
    private val script = ScriptedModel { on("count") { call("shelf", "count"); answer { it.single() } } }

    @Before
    fun fresh() {
        appSessionEventLogRoot(context, SESSIONS).deleteRecursively()
        AppHost.install(additions = plugin, simulatedModel = { script })
    }

    @After
    fun bare() = AppHost.install(additions = PluginAdditions.NONE, simulatedModel = null)

    @Test
    fun eachPromptSeesThePluginsOfItsMomentAndALoadedSessionReplays() = runBlocking {
        val session = connection { client ->
            val id = client.newSession()
            assertEquals(COUNTED, client.prompt(id))
            AppHost.install(additions = PluginAdditions.NONE)
            assertTrue(client.prompt(id).contains("\"code\":\"TOOL_UNAVAILABLE\""))
            AppHost.install(additions = plugin)
            assertEquals(COUNTED, client.prompt(id))
            id
        }
        assertEquals(2, shelf.calls)
        connection { client ->
            client.call("session/load", buildJsonObject { put("sessionId", session); put("cwd", "/"); put("mcpServers", JsonArray(emptyList())) })
            assertEquals(3, client.updates("user_message_chunk").size)
        }
    }

    private fun build(): AgentRuntime = buildRuntime(
        context, fixtureProviderConfig(), fixtureProfile(), false, true, appWorkspaceRoot(context),
        appSessionEventLogRoot(context, SESSIONS), "", null, noopDiagnostics(context), emptyList(),
    )

    /** One ACP connection to an agent over [RuntimePerTurn], as the socket serves one. */
    private suspend fun <T> connection(body: suspend (Client) -> T): T = coroutineScope {
        val client = Client(this)
        val runtime = RuntimePerTurn(::build)
        val serving = launch(Dispatchers.IO) { AcpAgent(runtime, AcpAgentConfig(name = "test")).serve(client.transport) }
        try {
            withTimeout(WAIT_MS) { body(client) }
        } finally {
            client.toAgent.close()
            serving.join()
            runtime.close()
        }
    }

    /** The client's end: requests and their answers, and the session updates it was sent. */
    private class Client(scope: CoroutineScope) {
        val toAgent = Channel<String>(Channel.UNLIMITED)
        private val fromAgent = Channel<JsonObject>(Channel.UNLIMITED)
        private val answers = Channel<JsonObject>(Channel.UNLIMITED)
        private val received: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
        private var nextId = 0

        val transport = object : AcpTransport {
            override suspend fun receive(): String? = toAgent.receiveCatching().getOrNull()
            override suspend fun send(message: String) { fromAgent.send(Json.parseToJsonElement(message).jsonObject) }
            override fun close() { fromAgent.close() }
        }

        init {
            scope.launch {
                for (message in fromAgent) {
                    val update = (message["params"] as? JsonObject)?.get("update") as? JsonObject
                    if (message.text("method") == "session/update" && update != null) received += update else answers.send(message)
                }
            }
        }

        suspend fun call(method: String, params: JsonObject): JsonObject {
            val id = ++nextId
            toAgent.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }.toString())
            val answer = answers.receive()
            return answer["result"] as? JsonObject ?: error("$method failed: ${answer["error"]}")
        }

        suspend fun newSession(): String =
            call("session/new", buildJsonObject { put("cwd", "/"); put("mcpServers", JsonArray(emptyList())) }).text("sessionId")!!

        /** A prompt that counts, and what the agent said in its turn. */
        suspend fun prompt(session: String): String {
            val before = updates("agent_message_chunk").size
            val prompt = buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "count them") }) }
            assertEquals("end_turn", call("session/prompt", buildJsonObject { put("sessionId", session); put("prompt", prompt) }).text("stopReason"))
            return updates("agent_message_chunk").drop(before).joinToString("") { (it["content"] as JsonObject).text("text").orEmpty() }
        }

        fun updates(kind: String): List<JsonObject> = synchronized(received) { received.filter { it.text("sessionUpdate") == kind } }
    }

    private class Shelf : ToolProvider {
        var calls = 0
        override val providerId = "shelf"

        override suspend fun listTools() = listOf(
            ToolDescriptor("count", "Counts the items on the shelf.", """{"type":"object","properties":{}}""", effectKind = "READ_ONLY"),
        )

        override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
            calls++
            return ToolCallResult(success = true, resultJson = COUNTED)
        }
    }

    private companion object {
        const val SESSIONS = "acp-turns-test"
        const val COUNTED = """{"items":3}"""
        const val WAIT_MS = 60_000L

        fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content
    }
}

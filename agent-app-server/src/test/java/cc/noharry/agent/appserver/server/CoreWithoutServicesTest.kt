package cc.noharry.agent.appserver.server

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.ModelPreset
import cc.noharry.agent.appserver.protocol.AppServerMethods
import cc.noharry.agent.appserver.protocol.ClientCapabilities
import cc.noharry.agent.appserver.protocol.ClientInfo
import cc.noharry.agent.appserver.protocol.ClientRequests
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.ModelFacts
import cc.noharry.agent.appserver.protocol.ModelSettingsParams
import cc.noharry.agent.appserver.protocol.ModelSettingsRequests
import cc.noharry.agent.appserver.protocol.RunOutcome
import cc.noharry.agent.appserver.protocol.ServerNotifications
import cc.noharry.agent.appserver.protocol.ServiceRequests
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.TurnCompleted
import cc.noharry.agent.appserver.protocol.TurnStartParams
import cc.noharry.agent.appserver.protocol.client.ServerClient
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The server with no model service's adapter (no `agent-app-models`): it names no service, knows no service, gives every
 * model the conservative defaults, and still runs a turn on any OpenAI-compatible service, here one on this machine.
 * (Its own SDK level: the server's singletons are fresh.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class CoreWithoutServicesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val service = MockWebServer().apply { dispatcher = Chat; start() }

    @After
    fun stop() = service.shutdown()

    @Test
    fun withoutAnAdapterTheServerNamesNoServiceAndRunsATurnOnAnyCompatibleService() {
        val names = FindingCode.entries.map { it.name } + AppServerMethods.all.map { it.name }
        assertEquals(emptyList<String>(), names.filter { name -> SERVICE_NAMES.any { name.contains(it, ignoreCase = true) } })

        AppServer.install(app, AppServerConfig(name = "core-test", model = ModelPreset(baseUrl = service.url("/v1").toString(), model = "any-model", apiKey = "sk-local")))
        val client = ServerClient(AppServer.connect(app), ClientInfo("core-test"), ClientCapabilities(), scope = scope)
        onMain { client.connect() }

        assertEquals(emptyList<Any>(), onMain { client.request(ServiceRequests.LIST, Empty) }.services)
        val editor = client.state.value.configEditor
        assertEquals("" to "any-model", editor.service to editor.model)
        val settings = onMain { client.request(ModelSettingsRequests.MODELS_READ, ModelSettingsParams(editor, listOf("any-model"))) }.models.single()
        assertEquals(128_000L to ModelFacts(), settings.defaultWindow to settings.facts)
        assertTrue(settings.imageInput)

        val ended = scope.async(start = CoroutineStart.UNDISPATCHED) { client.notifications.first { it.method == ServerNotifications.TURN_COMPLETED.name } }
        onMain { client.request(ClientRequests.TURN_START, TurnStartParams("Say hello.")) }
        val completed = onMain { Json { ignoreUnknownKeys = true }.decodeFromJsonElement(TurnCompleted.serializer(), ended.await().params!!) }
        assertEquals(RunOutcome.COMPLETED, completed.result?.outcome)
        val answer = client.state.value.items.filterIsInstance<ConversationItem.MessageItem>().last { it.role == TranscriptRole.ASSISTANT && it.final }
        assertEquals(ANSWER, answer.content)
        assertTrue(Chat.asked.isNotEmpty() && Chat.asked.all { it == "Bearer sk-local" })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> onMain(block: suspend () -> T): T {
        val running = scope.async { block() }
        val until = System.currentTimeMillis() + 20_000L
        while (!running.isCompleted && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5))
            Thread.sleep(5)
        }
        return running.getCompleted()
    }

    /** An OpenAI-compatible service that answers every chat with a finish, streamed or not, as asked. */
    private object Chat : Dispatcher() {
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path != "/v1/chat/completions") return MockResponse().setResponseCode(404)
            asked += request.getHeader("Authorization").orEmpty()
            val arguments = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive("""{"response":"$ANSWER","outcome":"success"}"""))
            val call = """{"index":0,"id":"call_1","type":"function","function":{"name":"finish","arguments":$arguments}}"""
            val usage = """"usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}"""
            return if (request.body.readUtf8().contains("\"stream\":true")) {
                MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[$call]}}]}\n\n" +
                        "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}],$usage}\n\n" +
                        "data: [DONE]\n\n",
                )
            } else {
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[$call]},"finish_reason":"tool_calls"}],$usage}""",
                )
            }
        }
    }

    private companion object {
        const val ANSWER = "Hello from a service on this machine."

        /** Names of services the protocol's own names never carry (the build's source rule has the full list). */
        val SERVICE_NAMES = listOf("deepseek", "moonshot", "kimi", "qwen", "zhipu", "doubao", "minimax", "gemini", "claude", "gpt")
    }
}

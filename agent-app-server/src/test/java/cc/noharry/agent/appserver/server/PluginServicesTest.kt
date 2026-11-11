package cc.noharry.agent.appserver.server

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.ScriptedModel
import cc.noharry.agent.appserver.protocol.ClientInfo
import cc.noharry.agent.appserver.protocol.ClientRequests
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.OverlayCommand
import cc.noharry.agent.appserver.protocol.PresentationRequests
import cc.noharry.agent.appserver.protocol.RunSettingsParams
import cc.noharry.agent.appserver.protocol.ServerNotifications
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.agent.appserver.protocol.TextParams
import cc.noharry.agent.appserver.protocol.TextResult
import cc.noharry.agent.appserver.protocol.TurnStartParams
import cc.noharry.agent.appserver.protocol.client.ServerClient
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What a plugin reaches of the server through its context, as a client would see it: requests answered by the
 * server's routes and another plugin's, turns started and recorded, words heard, the assistant on screen when no
 * client presents it, and lines of each run's context the model reads. (Its own SDK level: the server's singletons are
 * fresh. Its methods share one server.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PluginServicesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var client: ServerClient

    @Test
    fun aPluginReachesTheConversationThroughItsContext() {
        start()
        val ctx = Probe.ctx
        // A setting through the server's own route, and a request another plugin answers.
        onMain { ctx.request(SettingsRequests.RUN_SET, RunSettingsParams(confirmationMode = ConfirmationMode.DENY_ALL)) }
        onMain { client.state.first { it.confirmationMode == ConfirmationMode.DENY_ALL } }
        assertEquals("echo: hi", onMain { ctx.request(ECHO, TextParams("hi")) }.text)
        // A turn started by the plugin, followed through watch.
        var seen: ServerState? = null
        val stop = onMain { ctx.turns.watch { seen = it } }
        turn { ctx.turns.start("hello") }
        onMain { client.state.first { !it.isRunning } }
        assertFalse(ctx.turns.running)
        assertEquals("hello", seen?.items?.filterIsInstance<ConversationItem.TurnHeader>()?.last()?.fullText)
        assertEquals("You said hello", lastAnswer())
        onMain { stop() }
        // Words heard while nothing runs are a turn of their own.
        turn { ctx.speech.heard("again") }
        assertEquals("You said again", lastAnswer())
        // A turn something else answered is kept as one of the conversation's (written to it in the background).
        onMain { ctx.turns.record("how are you", "fine", "companion", speak = false) }
        val recent = onMain {
            var latest = ctx.turns.recentExchanges(1)
            while (latest.lastOrNull()?.first != "how are you") {
                delay(IDLE_MS)
                latest = ctx.turns.recentExchanges(1)
            }
            latest
        }
        assertEquals(listOf("how are you" to "fine"), recent)
        // A new conversation starts empty.
        onMain { ctx.turns.newConversation() }
        assertTrue(onMain { ctx.turns.recentExchanges() }.isEmpty())
        noAssistantOnScreen(ctx.assistant)
    }

    @Test
    fun aPluginsContextLinesReachTheModelAfterThePersonsWordsEveryRun() {
        start()
        turn { scope.launch { client.request(ClientRequests.TURN_START, TurnStartParams("what is noted")) } }
        val messages = Recording.requests.last().messages
        val said = messages.indexOfLast { it.role == "user" && it.content == "what is noted" }
        // The next message is the run's context, marked as the app's: the time, the app in front, the host's lines,
        // then the running plugins' lines.
        val context = messages[said + 1]
        assertEquals("user", context.role)
        assertTrue(context.content, context.content.startsWith("From the app, not typed by the user: context for the request above.\n"))
        assertTrue(context.content, context.content.lines().contains("Noted: 3 things."))
        // Never in the system prompt, which stays the same from run to run.
        assertFalse(Recording.requests.last().system.orEmpty().contains("Noted: 3 things."))
    }

    /** With no client presenting the assistant, its requests answer at once. */
    private fun noAssistantOnScreen(assistant: Assistant) {
        assertFalse(assistant.ready)
        assertFalse(assistant.inFront)
        assertNull(onMain { assistant.ask(PresentationRequests.ANDROID_USE_BEGIN, Empty) })
        assertEquals("no assistant on screen", onMain { assistant.card(OverlayCommand.SHOW) })
        assertFalse(onMain { suspendCoroutine { answer -> assistant.wake("test") { answer.resume(it) } } })
    }

    private fun start() {
        AppServer.install(
            app,
            AppServerConfig(name = "services-test", plugins = listOf(Probe, Echo, Lines), simulatedModel = { Recording }, simulatedBackend = true),
        )
        client = ServerClient(AppServer.connect(app), ClientInfo("services-test"), scope = scope)
        onMain { client.connect() }
    }

    /** [block] starts a turn; returns once it completed. */
    private fun turn(block: () -> Unit) = onMain {
        val ended = scope.async(start = CoroutineStart.UNDISPATCHED) {
            client.notifications.first { it.method == ServerNotifications.TURN_COMPLETED.name }
        }
        block()
        ended.await()
    }

    private fun lastAnswer(): String =
        client.state.value.items.filterIsInstance<ConversationItem.MessageItem>().last { it.final }.content

    /** [block] on the main thread, which the test is: the main looper is turned until it is done. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> onMain(block: suspend () -> T): T {
        val running = scope.async { block() }
        val until = System.currentTimeMillis() + WAIT_MS
        while (!running.isCompleted && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(IDLE_MS))
            Thread.sleep(IDLE_MS)
        }
        return running.getCompleted()
    }

    /** Keeps the context the server gave it. */
    private object Probe : AppServerPlugin {
        override val id = "probe"
        lateinit var ctx: PluginContext

        override fun apply(ctx: PluginContext) {
            this.ctx = ctx
        }
    }

    /** A request of its own. */
    private object Echo : AppServerPlugin {
        override val id = "echo"

        override fun apply(ctx: PluginContext) = ctx.routes { on(ECHO) { TextResult("echo: ${it.text}") } }
    }

    /** A line for each run's context. */
    private object Lines : AppServerPlugin {
        override val id = "lines"

        override fun apply(ctx: PluginContext) = ctx.contextLines { listOf("Noted: 3 things.") }
    }

    /** The script, keeping each request it was given. */
    private object Recording : ModelClient {
        val requests = mutableListOf<ModelRequest>()

        override fun complete(request: ModelRequest): Flow<ModelEvent> {
            requests += request
            return SCRIPT.complete(request)
        }
    }

    private companion object {
        val ECHO = RequestMethod("test/echo", TextParams.serializer(), TextResult.serializer())
        val SCRIPT = ScriptedModel { otherwise { answer("You said " + it.trim()) } }
        const val WAIT_MS = 20_000L
        const val IDLE_MS = 5L
    }
}

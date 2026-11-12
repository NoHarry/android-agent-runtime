package cc.noharry.agent.device.appserver

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.ScriptedModel
import cc.noharry.agent.appserver.protocol.ApprovalDecision
import cc.noharry.agent.appserver.protocol.ClientCapabilities
import cc.noharry.agent.appserver.protocol.ClientInfo
import cc.noharry.agent.appserver.protocol.ClientRequests
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.RunSettingsParams
import cc.noharry.agent.appserver.protocol.ServerNotifications
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.agent.appserver.protocol.TurnStartParams
import cc.noharry.agent.appserver.protocol.client.ServerClient
import cc.noharry.agent.appserver.server.AppServer
import cc.noharry.agent.appserver.server.AppServerConfig
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Which calls of the device's media volume ask, through the protocol as a host sees it: the runtime's `media.volume`
 * says it acts (it can set the volume), and the plugin says that a call with `action: "get"` only reads, so in "ask every
 * time" reading the volume runs unasked while turning it up, down or to a level asks; "deny all" refuses those and still
 * reads. (Its own SDK level: the server's singletons are fresh.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MediaVolumeAskingTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val asked = mutableListOf<String>()
    private lateinit var client: ServerClient

    @Test
    fun readingTheVolumeRunsUnaskedAndChangingItAsks() {
        start()
        assertEquals(ConfirmationMode.ASK_EVERY_TIME, client.state.value.confirmationMode)
        conversation()
        expect("volume get", asks = false, told = VOLUME)
        expect("volume set", asks = true, told = VOLUME)
        expect("volume up", asks = true)
        expect("volume down", asks = true)
        expect("volume get", asks = false, told = VOLUME)
        mode(ConfirmationMode.DENY_ALL)
        conversation()
        expect("volume get", asks = false, told = VOLUME)
        expect("volume set", asks = false, told = DENIED)
    }

    private fun start() {
        AppServer.install(
            app,
            AppServerConfig(name = "media-volume-test", plugins = listOf(DeviceToolsPlugin), simulatedModel = { SCRIPT }, simulatedBackend = true),
        )
        client = ServerClient(AppServer.connect(app), ClientInfo("media-volume-test"), ClientCapabilities(approvals = true), scope = scope)
        client.approvals = { request -> asked += request.toolName; ApprovalDecision.APPROVE }
        onMain { client.connect() }
    }

    /** A turn for [said]: whether its call of the volume [asks] first, and what the model was [told] when given. */
    private fun expect(said: String, asks: Boolean, told: String? = null) {
        val askedBefore = asked.size
        onMain {
            val ended = scope.async(start = CoroutineStart.UNDISPATCHED) {
                client.notifications.first { it.method == ServerNotifications.TURN_COMPLETED.name }
            }
            client.request(ClientRequests.TURN_START, TurnStartParams(said))
            ended.await()
        }
        val answer = client.state.value.items.filterIsInstance<ConversationItem.MessageItem>().last { it.final }.content
        assertEquals("asked for \"$said\" ($answer)", if (asks) listOf(VOLUME_TOOL) else emptyList(), asked.drop(askedBefore))
        if (told != null) assertTrue("told for \"$said\": $answer", answer.contains(told))
    }

    private fun mode(mode: ConfirmationMode) = onMain { client.request(SettingsRequests.RUN_SET, RunSettingsParams(confirmationMode = mode)) }

    private fun conversation() = onMain { client.request(ClientRequests.THREAD_START, Empty) }

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

    private companion object {
        /** The full name the runtime gives the tool, as an approval names it. */
        const val VOLUME_TOOL = "mcp__device_media__media_volume"
        const val VOLUME = "\"volumePercent\""
        const val DENIED = "\"code\":\"CONFIRMATION_DENIED\""
        const val WAIT_MS = 20_000L
        const val IDLE_MS = 5L
        val SCRIPT = ScriptedModel {
            on("volume get") { call(DEVICE_MEDIA_TOOL_SERVER, "media.volume", mapOf("action" to "get")); answer { it.single() } }
            on("volume set") { call(DEVICE_MEDIA_TOOL_SERVER, "media.volume", mapOf("action" to "set", "percent" to 30)); answer { it.single() } }
            on("volume up") { call(DEVICE_MEDIA_TOOL_SERVER, "media.volume", mapOf("action" to "up")); answer { it.single() } }
            on("volume down") { call(DEVICE_MEDIA_TOOL_SERVER, "media.volume", mapOf("action" to "down")); answer { it.single() } }
        }
    }
}

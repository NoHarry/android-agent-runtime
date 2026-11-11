package cc.noharry.agent.appserver.server

import android.app.Application
import android.os.Looper
import java.time.Duration
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
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.HookDecision
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.spi.ToolUse
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
 * Which calls ask, through the protocol as a host sees it: one of a host's tool that does not say it only reads
 * (`MUTATING`, or `UNKNOWN` by default) asks, and the approval mode answers ("ask every time" each time, "allow
 * confirmed domains" once per tool in a conversation, "allow all" runs it, "deny all" refuses it); one that only reads
 * never asks, nor one the host names in `unaskedTools`; a host's hook answering Deny refuses in every mode; a plugin's
 * tool that acts asks every time in every mode but "deny all", and its approval says so (`alwaysAsk`). A new
 * installation starts in the host's mode ("ask every time" here), and the host can forget the mode the person chose.
 * (Its own SDK level: the server's singletons are fresh.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HostApprovalsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val ran = mutableListOf<String>()
    private val asked = mutableListOf<String>()
    private var answer = ApprovalDecision.APPROVE
    private lateinit var client: ServerClient

    @Test
    fun aToolThatDoesNotSayItOnlyReadsAsksAndTheModeAnswers() {
        start()
        assertEquals(ConfirmationMode.ASK_EVERY_TIME, client.state.value.confirmationMode)
        askEveryTime()
        allowConfirmed()
        allowAll()
        denyAll()
        // The person chose "deny all", which is kept; the host forgets it and its own mode applies again.
        onMain {
            AppServer.resetApprovals()
            client.state.first { it.confirmationMode == ConfirmationMode.ASK_EVERY_TIME }
        }
    }

    private fun askEveryTime() {
        conversation()
        expect("open", asks = "open")
        expect("open", asks = "open")
        expect("shut", asks = "shut")
        expect("peek")
        expect("dust")
        expect("burn", runs = false, told = HOOK_DENIED)
        // A plugin's tool that acts: the approval says it asks every time.
        expect("lock", asks = "lock!")
        answer = ApprovalDecision.DENY
        expect("open", asks = "open", runs = false, told = DENIED)
        answer = ApprovalDecision.APPROVE
    }

    private fun allowConfirmed() {
        mode(ConfirmationMode.ALLOW_CONFIRMED_DOMAINS)
        conversation()
        expect("open", asks = "open")
        // Once per tool in a conversation.
        expect("open")
        expect("shut", asks = "shut")
        expect("lock", asks = "lock!")
        expect("lock", asks = "lock!")
        expect("peek")
        expect("dust")
        expect("burn", runs = false, told = HOOK_DENIED)
        conversation()
        expect("open", asks = "open")
    }

    private fun allowAll() {
        mode(ConfirmationMode.ALLOW_ALL_SESSION)
        conversation()
        expect("open")
        expect("shut")
        expect("lock", asks = "lock!")
        expect("peek")
        expect("dust")
        expect("burn", runs = false, told = HOOK_DENIED)
    }

    private fun denyAll() {
        mode(ConfirmationMode.DENY_ALL)
        conversation()
        expect("open", runs = false, told = DENIED)
        expect("shut", runs = false, told = DENIED)
        expect("peek")
        expect("dust")
        expect("burn", runs = false, told = HOOK_DENIED)
        expect("lock", runs = false, told = DENIED)
    }

    private fun start() {
        AppServer.install(
            app,
            AppServerConfig(
                name = "approvals-test",
                tools = { listOf(Drawer(ran)) },
                hooks = listOf(NoBurning),
                unaskedTools = setOf("mcp__drawer__dust"),
                plugins = listOf(SafePlugin(ran)),
                simulatedModel = { SCRIPT },
                simulatedBackend = true,
            ),
        )
        client = ServerClient(AppServer.connect(app), ClientInfo("approvals-test"), ClientCapabilities(approvals = true), scope = scope)
        client.approvals = { request -> asked += request.toolName.substringAfterLast("__") + if (request.alwaysAsk) "!" else ""; answer }
        onMain { client.connect() }
    }

    /** A turn for [tool]: whether it [asks] (its short name, `!` when every time), [runs], and what the model was [told]. */
    private fun expect(tool: String, asks: String? = null, runs: Boolean = true, told: String? = null) {
        val askedBefore = asked.size
        val ranBefore = ran.size
        onMain {
            val ended = scope.async(start = CoroutineStart.UNDISPATCHED) {
                client.notifications.first { it.method == ServerNotifications.TURN_COMPLETED.name }
            }
            client.request(ClientRequests.TURN_START, TurnStartParams(tool))
            ended.await()
        }
        assertEquals("asked for \"$tool\"", listOfNotNull(asks), asked.drop(askedBefore))
        assertEquals("ran for \"$tool\"", if (runs) listOf(tool) else emptyList(), ran.drop(ranBefore))
        // The script answers with what the model was given for the call.
        val answer = client.state.value.items.filterIsInstance<ConversationItem.MessageItem>().last { it.final }.content
        if (told != null) assertTrue(answer, answer.contains(told))
    }

    private fun mode(mode: ConfirmationMode) = onMain { client.request(SettingsRequests.RUN_SET, RunSettingsParams(confirmationMode = mode)) }

    /** A conversation of its own: what an earlier one approved is not remembered in it. */
    private fun conversation() = onMain { client.request(ClientRequests.THREAD_START, Empty) }

    /** [block] on the main thread, which the test is: the main looper is turned until it is done. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> onMain(block: suspend () -> T): T {
        val running = scope.async { block() }
        val until = System.currentTimeMillis() + WAIT_MS
        while (!running.isCompleted && System.currentTimeMillis() < until) {
            // Its clock moves only when told: a delay on the main thread waits for this.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(IDLE_MS))
            Thread.sleep(IDLE_MS)
        }
        return running.getCompleted()
    }

    private class Drawer(private val ran: MutableList<String>) : ToolProvider {
        override val providerId = "drawer"

        override suspend fun listTools() = listOf(
            ToolDescriptor("open", "Opens the drawer.", EMPTY, effectKind = "MUTATING"),
            ToolDescriptor("shut", "Shuts the drawer.", EMPTY),
            ToolDescriptor("peek", "Says what is in the drawer.", EMPTY, effectKind = "READ_ONLY"),
            ToolDescriptor("dust", "Dusts the drawer.", EMPTY, effectKind = "MUTATING"),
            ToolDescriptor("burn", "Burns the drawer.", EMPTY, effectKind = "MUTATING"),
        )

        override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
            ran += toolName
            return ToolCallResult(success = true, resultJson = "{}")
        }
    }

    /** A plugin whose tools come from outside the host: one that acts asks every time. */
    private class SafePlugin(private val ran: MutableList<String>) : AppServerPlugin {
        override val id = "safe"

        override fun apply(ctx: PluginContext) = ctx.tools(askBeforeActions = true) { _, _ ->
            listOf(object : ToolProvider {
                override val providerId = "safe"
                override suspend fun listTools() = listOf(ToolDescriptor("lock", "Locks the safe.", EMPTY, effectKind = "MUTATING"))
                override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
                    ran += toolName
                    return ToolCallResult(success = true, resultJson = "{}")
                }
            })
        }
    }

    private object NoBurning : RuntimeHook {
        override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
            if (call.toolName == "mcp__drawer__burn") HookDecision.Deny(reason = "Never.") else HookDecision.Allow()
    }

    private companion object {
        const val EMPTY = """{"type":"object","properties":{}}"""
        const val DENIED = "\"code\":\"CONFIRMATION_DENIED\""
        const val HOOK_DENIED = "\"code\":\"HOOK_DENIED\""
        const val WAIT_MS = 20_000L
        const val IDLE_MS = 5L
        val SCRIPT = ScriptedModel {
            listOf("open", "shut", "peek", "dust", "burn").forEach { tool -> on(tool) { call("drawer", tool); answer { it.single() } } }
            on("lock") { call("safe", "lock"); answer { it.single() } }
        }
    }
}

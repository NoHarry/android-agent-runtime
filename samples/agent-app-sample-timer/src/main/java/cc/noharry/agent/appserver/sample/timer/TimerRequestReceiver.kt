package cc.noharry.agent.appserver.sample.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cc.noharry.agent.appserver.protocol.ClientInfo
import cc.noharry.agent.appserver.protocol.ClientRequests
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.PackageRefusal
import cc.noharry.agent.appserver.protocol.PluginConfirmParams
import cc.noharry.agent.appserver.protocol.PluginInstallParams
import cc.noharry.agent.appserver.protocol.PluginRemoveParams
import cc.noharry.agent.appserver.protocol.PluginRequests
import cc.noharry.agent.appserver.protocol.ServerNotifications
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.TurnStartParams
import cc.noharry.agent.appserver.protocol.client.ServerClient
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.protocol.jsonrpc.ProtocolJson
import cc.noharry.agent.appserver.protocol.jsonrpc.paramsOf
import cc.noharry.agent.appserver.server.AppServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

// guide:start receiver
/**
 * The app's only way in:
 * `adb shell am broadcast --include-stopped-packages -n cc.noharry.agent.appserver.sample.timer/.TimerRequestReceiver -e text "…"`
 * (an app never opened is "stopped" and gets no broadcast without the flag).
 * Each intent runs one turn and logs under the tag TimerAgent each tool call as it ends, then the answer.
 *
 * A receiver rather than a service: an app with no Activity is always "in the background", so from API 26 nothing
 * outside can start a plain service in it (`am start-service` answers "app is in background"), and a foreground
 * service needs a notification and a permission this app has no use for. The cost is the receiver's time limit: the
 * turn must end inside the broadcast's window (about 60 s for a background broadcast), so [TimerAgent] gives up at 50.
 */
class TimerRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text")?.trim().orEmpty()
        if (text.isEmpty()) {
            Log.w(TAG, "no 'text' extra; nothing to do")
            return
        }
        val pending = goAsync()
        TimerAgent.scope.launch {
            try {
                Log.i(TAG, "answer: ${TimerAgent.ask(context.applicationContext, text)}")
            } finally {
                pending.finish()
            }
        }
    }
}
// guide:end receiver

private const val TAG = "TimerAgent"

/**
 * The process's one protocol client: connects on the first request, then runs turns one after another, and installs
 * and removes plugin packages between them.
 */
object TimerAgent {
    internal val scope = MainScope()
    private val turns = Mutex()
    private var client: Deferred<ServerClient>? = null

    /** Runs one turn for [text] and gives its answer, or what went wrong. */
    suspend fun ask(app: Context, text: String): String = turns.withLock {
        val client = connected(app)
        // Collecting before turn/start goes out, so the turn's end cannot pass unseen.
        val ended = scope.async(start = CoroutineStart.UNDISPATCHED) {
            client.notifications.first { notification -> logToolCall(notification); notification.method == ServerNotifications.TURN_COMPLETED.name }
        }
        client.request(ClientRequests.TURN_START, TurnStartParams(text))
        if (withTimeoutOrNull(TURN_TIMEOUT_MS) { ended.await() } == null) {
            ended.cancel()
            return@withLock "no answer within ${TURN_TIMEOUT_MS / 1000} s"
        }
        val items = client.state.value.items
        val messages = items.drop(items.indexOfLast { it is ConversationItem.TurnHeader }.coerceAtLeast(0))
            .filterIsInstance<ConversationItem.MessageItem>()
        messages.lastOrNull { it.role == TranscriptRole.ERROR || (it.role == TranscriptRole.ASSISTANT && it.final) }?.content ?: "no answer"
    }

    // guide:start install
    /**
     * Installs the plugin package [source] names (its bytes as base64, an HTTPS address or a file) in the protocol's
     * two steps: `plugins/install` fetches and checks it and says what it asks for, `plugins/confirm` installs it with
     * the secrets it asks for. A screen shows the person what it asks for in between; with no screen, Pip takes only a
     * package that needs no second yes (one from a publisher it trusts) and none that asks for a secret it was not
     * given. Its answer: what was installed, or why not.
     */
    suspend fun install(app: Context, source: PluginInstallParams, secrets: Map<String, String> = emptyMap()): String = turns.withLock {
        val client = connected(app)
        try {
            val checked = client.request(PluginRequests.INSTALL, source)
            val pkg = checked.pkg
            // An unknown publisher or an unsigned package: confirming needs acknowledgeUntrusted = true, the person's yes.
            if (checked.needsAcknowledgement) return@withLock "not installed: ${pkg.publisher.name} is not a publisher Pip trusts"
            // An update keeps the secrets set before (set = true); a value given here replaces one.
            val missing = pkg.permissions.secrets.filter { !it.set && it.name !in secrets }
            if (missing.isNotEmpty()) return@withLock "not installed: it needs ${missing.joinToString { it.label }}"
            val plugins = client.request(PluginRequests.CONFIRM, PluginConfirmParams(checked.installId, secrets))
            val installed = plugins.plugins.single { it.id == pkg.id }
            "installed ${pkg.id} ${pkg.version}" + if (installed.running) "" else " (not running)"
        } catch (refused: JsonRpcException) {
            // A refused package or step: the error's data says what the server found.
            val why = refused.data?.let { ProtocolJson.decodeFromJsonElement(PackageRefusal.serializer(), it) }
            "not installed: " + (why?.let { "${it.problem} ${it.detail}".trim() } ?: refused.message)
        }
    }

    /** Removes the installed package [id], its files and secrets with it. */
    suspend fun remove(app: Context, id: String) {
        turns.withLock { connected(app).request(PluginRequests.REMOVE, PluginRemoveParams(id)) }
    }
    // guide:end install

    private suspend fun connected(app: Context): ServerClient = (client ?: scope.async {
        ServerClient(AppServer.connect(app), ClientInfo(name = "agent-app-sample-timer", version = "0.1"), scope = scope)
            .also { it.connect() }
    }.also { client = it }).await()

    private fun logToolCall(notification: JsonRpcNotification) {
        if (notification.method != ServerNotifications.ITEM_UPDATED.name) return
        val call = notification.paramsOf(ServerNotifications.ITEM_UPDATED).item as? ConversationItem.ToolCallItem ?: return
        if (call.status != ToolCallStatus.RUNNING) Log.i(TAG, "tool call: ${call.toolName} ${call.status} args=${call.argumentsJson} result=${call.resultJson}")
    }

    private const val TURN_TIMEOUT_MS = 50_000L
}

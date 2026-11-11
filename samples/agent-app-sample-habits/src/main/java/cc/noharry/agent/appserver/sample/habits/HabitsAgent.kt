package cc.noharry.agent.appserver.sample.habits

// guide:start agent
import android.content.Context
import android.util.Log
import cc.noharry.agent.appserver.protocol.ApprovalDecision
import cc.noharry.agent.appserver.protocol.ApprovalRequest
import cc.noharry.agent.appserver.protocol.ClientCapabilities
import cc.noharry.agent.appserver.protocol.ClientInfo
import cc.noharry.agent.appserver.protocol.ClientRequests
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.PluginRequests
import cc.noharry.agent.appserver.protocol.PluginSetParams
import cc.noharry.agent.appserver.protocol.ServerNotifications
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.TurnStartParams
import cc.noharry.agent.appserver.protocol.client.ServerClient
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.server.AppServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tally's one client of the server, for the service and the test: one request at a time, each a turn (whose answer it
 * returns), a new conversation, or a plugin turned off or on. With no screen to ask the person on, it answers in code
 * the approvals a turn asks for: forgetting a habit only when the request said so, anything else no.
 */
object HabitsAgent {
    const val TAG = "HabitsAgent"

    /** Where requests run: the main thread, as the server's own work does. */
    val scope = MainScope()
    private val requests = Mutex()
    private var client: ServerClient? = null
    private var resetAllowed = false

    /** One turn for [text]: its answer, or what went wrong. A reset it asks for is allowed only when [allowReset]. */
    suspend fun ask(context: Context, text: String, allowReset: Boolean = false): String = requests.withLock {
        val client = connected(context)
        resetAllowed = allowReset
        // Listening before the turn starts, so its end cannot pass unseen.
        val ended = scope.async(start = CoroutineStart.UNDISPATCHED) {
            client.notifications.first { it.method == ServerNotifications.TURN_COMPLETED.name }
        }
        client.request(ClientRequests.TURN_START, TurnStartParams(text))
        if (withTimeoutOrNull(TURN_MS) { ended.await() } == null) {
            ended.cancel()
            return@withLock "no answer within ${TURN_MS / 1000} s"
        }
        client.state.value.lastAnswer() ?: "no answer"
    }

    /** Starts a new conversation: its turns see none of the earlier ones. */
    suspend fun newConversation(context: Context) {
        requests.withLock { connected(context).request(ClientRequests.THREAD_START, Empty) }
    }

    /** Turns plugin [id] on or off; the choice is kept on the device. */
    suspend fun setPlugin(context: Context, id: String, on: Boolean) {
        requests.withLock { connected(context).request(PluginRequests.SET, PluginSetParams(id, on)) }
    }

    private suspend fun connected(context: Context): ServerClient = client ?: ServerClient(
        AppServer.connect(context.applicationContext),
        ClientInfo(name = "habits", version = "0.1"),
        // It takes on approvals: a call that waits for a yes is asked here.
        ClientCapabilities(approvals = true),
        scope = scope,
    ).also {
        it.approvals = { request -> decide(request) }
        it.connect()
        client = it
    }

    private fun decide(request: ApprovalRequest): ApprovalDecision {
        val allowed = request.toolName == RESET_TOOL && resetAllowed
        Log.i(TAG, "approval: ${request.toolName} ${if (allowed) "allowed" else "refused"}")
        return if (allowed) ApprovalDecision.APPROVE else ApprovalDecision.DENY
    }

    private const val TURN_MS = 50_000L
}

/** The last turn's answer, or what went wrong in it; null before any. */
fun ServerState.lastAnswer(): String? = items.drop(items.indexOfLast { it is ConversationItem.TurnHeader }.coerceAtLeast(0))
    .filterIsInstance<ConversationItem.MessageItem>()
    .lastOrNull { it.role == TranscriptRole.ERROR || (it.role == TranscriptRole.ASSISTANT && it.final) }?.content
// guide:end agent

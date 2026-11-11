package cc.noharry.agent.appserver.server

import cc.noharry.agent.appserver.protocol.ApprovalRequest
import cc.noharry.agent.appserver.protocol.ClientCapabilities
import cc.noharry.agent.appserver.protocol.ClientRequests
import cc.noharry.agent.appserver.protocol.ConfirmationStatus
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.InitializeResult
import cc.noharry.agent.appserver.protocol.PROTOCOL_VERSION
import cc.noharry.agent.appserver.protocol.ServerRequests
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcErrorCodes
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcMessage
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcPeer
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcRequest
import cc.noharry.agent.appserver.protocol.jsonrpc.NotificationMethod
import cc.noharry.agent.appserver.protocol.jsonrpc.ProtocolJson
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.appserver.protocol.jsonrpc.paramsOf
import cc.noharry.agent.appserver.protocol.jsonrpc.resultOf
import cc.noharry.agent.appserver.protocol.transport.MessageChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * One client of [hub]: answers its requests through the hub's backend, and sends it what each publication holds for
 * it, in order. Until it has shaken hands it gets nothing and may ask for nothing else.
 */
internal class ServerConnection(
    private val hub: ServerHub,
    private val channel: MessageChannel,
    private val onError: (Throwable) -> Unit,
    /** How many messages may wait for a client that stopped reading before it is let go; unbounded in the process. */
    queueLimit: Int = Channel.UNLIMITED,
) {
    /** The threads beside the current one this client follows (`thread/subscribe`). */
    val followed: MutableSet<String> = java.util.concurrent.CopyOnWriteArraySet()

    /** What the client took on in its handshake; null until then. */
    @Volatile var capabilities: ClientCapabilities? = null
        private set

    /** The client went away; the hub no longer counts it, or never will. */
    @Volatile internal var isClosed = false
        private set
    private val outgoing = Channel<JsonRpcMessage>(queueLimit)
    private val peer = JsonRpcPeer(
        channel = channel,
        scope = hub.scope,
        onRequest = ::handle,
        onNotification = { notification -> withContext(hub.main) { hub.clientNotified(this@ServerConnection, notification) } },
        onClosed = ::closed,
        onError = onError,
    )

    fun start() {
        peer.start()
        hub.scope.launch {
            try {
                for (message in outgoing) channel.send(message)
            } catch (gone: ClosedSendChannelException) {
                // The client went away; what was left for it is dropped with it.
                onError(gone)
                closed()
            }
        }
    }

    /** Called on the hub's main dispatcher with each publication; a client that takes no state gets none of it. */
    internal fun deliver(publication: Publication) {
        val taken = capabilities?.takeIf { it.state } ?: return
        publication.notifications.forEach { item ->
            if (item.trajectory && !taken.trajectory) return@forEach
            if (item.thread != null && item.thread !in followed) return@forEach
            queue(item.notification)
        }
        if (taken.approvals) publication.asks.filter { it.threadId == publication.focusThreadId || it.threadId in followed }.forEach(::ask)
    }

    fun close() = peer.close()

    /** Asks this client something only it can do, like presenting the assistant; waits for its answer. */
    suspend fun <P, R> request(method: RequestMethod<P, R>, params: P): R = peer.request(method, params)

    /** Tells this client something, in order with the state it is sent. */
    internal fun <P> notify(method: NotificationMethod<P>, params: P) {
        queue(JsonRpcNotification(method.name, ProtocolJson.encodeToJsonElement(method.params, params)))
    }

    /** A client this far behind stopped reading (a socket client suspended in a terminal): it is let go, not grown. */
    private fun queue(message: JsonRpcMessage) {
        if (outgoing.trySend(message).isFailure && !outgoing.isClosedForSend) {
            onError(IllegalStateException("client stopped reading; disconnected"))
            close()
        }
    }

    private fun ask(request: ApprovalRequest) {
        hub.scope.launch {
            val answer = try {
                peer.request(ServerRequests.APPROVAL_REQUEST, request)
            } catch (error: JsonRpcException) {
                // Left unanswered, or answered elsewhere first: the item's own buttons and other clients still can.
                onError(error)
                return@launch
            }
            withContext(hub.main) {
                if (hub.published?.stillPending(request.pendingId) == true) hub.backend.respondApproval(request.pendingId, answer.decision)
            }
        }
    }

    private suspend fun handle(request: JsonRpcRequest): JsonElement? {
        if (request.method == ClientRequests.INITIALIZE.name) return initialize(request)
        if (capabilities == null) throw JsonRpcException(JsonRpcErrorCodes.NOT_INITIALIZED, "initialize first")
        return withContext(hub.main) {
            when (request.method) {
                ClientRequests.THREAD_SUBSCRIBE.name ->
                    resultOf(ClientRequests.THREAD_SUBSCRIBE, hub.follow(this@ServerConnection, request.paramsOf(ClientRequests.THREAD_SUBSCRIBE).threadId))
                ClientRequests.THREAD_UNSUBSCRIBE.name -> {
                    hub.unfollow(this@ServerConnection, request.paramsOf(ClientRequests.THREAD_UNSUBSCRIBE).threadId)
                    resultOf(ClientRequests.THREAD_UNSUBSCRIBE, Empty)
                }
                else -> hub.routes.handle(request)
            }
        }
    }

    private suspend fun initialize(request: JsonRpcRequest): JsonElement {
        val params = request.paramsOf(ClientRequests.INITIALIZE)
        val snapshot = withContext(hub.main) {
            capabilities = params.capabilities
            hub.subscribe(this@ServerConnection, params.capabilities)
        }
        return resultOf(ClientRequests.INITIALIZE, InitializeResult(PROTOCOL_VERSION, hub.serverInfo, snapshot))
    }

    private fun closed() {
        isClosed = true
        outgoing.close()
        channel.close()
        // On the hub's main thread, as every change to who is connected: a handshake or a follow still queued there
        // sees the client gone and does not count it back in.
        hub.scope.launch(hub.main) { hub.remove(this@ServerConnection) }
    }
}

private fun ServerState.stillPending(pendingId: String): Boolean = liveItems().values.any { items ->
    items.any { it is ConversationItem.ConfirmationItem && it.pendingId == pendingId && it.status == ConfirmationStatus.PENDING }
}

/** Requests a connection answers itself, beside the server's routes ([ServerRoutes]). */
internal val CONNECTION_METHODS = setOf(ClientRequests.INITIALIZE.name, ClientRequests.THREAD_SUBSCRIBE.name, ClientRequests.THREAD_UNSUBSCRIBE.name)

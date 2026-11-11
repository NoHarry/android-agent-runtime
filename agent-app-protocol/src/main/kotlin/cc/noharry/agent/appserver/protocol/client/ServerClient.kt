package cc.noharry.agent.appserver.protocol.client

import cc.noharry.agent.appserver.protocol.ApprovalDecision
import cc.noharry.agent.appserver.protocol.ApprovalRequest
import cc.noharry.agent.appserver.protocol.ApprovalResponse
import cc.noharry.agent.appserver.protocol.ClientCapabilities
import cc.noharry.agent.appserver.protocol.ClientInfo
import cc.noharry.agent.appserver.protocol.ClientNotifications
import cc.noharry.agent.appserver.protocol.ClientRequests
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi
import cc.noharry.agent.appserver.protocol.InitializeParams
import cc.noharry.agent.appserver.protocol.InitializeResult
import cc.noharry.agent.appserver.protocol.ServerNotifications
import cc.noharry.agent.appserver.protocol.ServerRequests
import cc.noharry.agent.appserver.protocol.ThreadIdParams
import cc.noharry.agent.appserver.protocol.ThreadSnapshot
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcErrorCodes
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcPeer
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcRequest
import cc.noharry.agent.appserver.protocol.jsonrpc.NotificationMethod
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.appserver.protocol.jsonrpc.paramsOf
import cc.noharry.agent.appserver.protocol.jsonrpc.resultOf
import cc.noharry.agent.appserver.protocol.transport.MessageChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * A front end's connection to the server: [connect] shakes hands and takes the snapshot, [state] follows every
 * notification after it, [request] calls a method. Notifications that arrive while the handshake's answer is still on
 * its way are held and applied over the snapshot, in order, so none is lost or applied twice.
 *
 * A client that sets [approvals] (and says so in its capabilities) is asked when a tool needs a yes; the first client
 * to answer decides, and an ask another client answered is called off. Other requests the server sends (such as
 * [cc.noharry.agent.appserver.protocol.PresentationRequests]) go to what [handle] registered for them.
 */
@ExperimentalAppServerApi
class ServerClient(
    channel: MessageChannel,
    private val clientInfo: ClientInfo,
    private val capabilities: ClientCapabilities = ClientCapabilities(),
    private val scope: CoroutineScope,
    onError: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private var ready = false
    private val early = ArrayDeque<JsonRpcNotification>()
    /** Follows being answered: the item notifications for each thread that came meanwhile, applied over its answer. */
    private val following = mutableMapOf<String, ArrayDeque<JsonRpcNotification>>()
    private val mutableState = MutableStateFlow(ServerState(connected = false))
    private val mutableNotifications = MutableSharedFlow<JsonRpcNotification>(extraBufferCapacity = NOTIFICATION_BUFFER)
    private val asking = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<ApprovalDecision?>>()
    private val handlers = ConcurrentHashMap<String, suspend (JsonRpcRequest) -> JsonElement>()
    private val peer = JsonRpcPeer(
        channel = channel,
        scope = scope,
        onRequest = ::answer,
        onNotification = ::receive,
        onClosed = { mutableState.update { it.copy(connected = false) } },
        onError = onError,
    )

    val state: StateFlow<ServerState> = mutableState.asStateFlow()

    /** Every notification as it came, for what a state does not hold, like a turn having ended. */
    val notifications: SharedFlow<JsonRpcNotification> = mutableNotifications.asSharedFlow()

    /** Answers an approval the server asks for; null leaves it to another client or to the item's buttons. */
    @Volatile
    var approvals: (suspend (ApprovalRequest) -> ApprovalDecision?)? = null

    suspend fun connect(): InitializeResult {
        peer.start()
        val result = peer.request(ClientRequests.INITIALIZE, InitializeParams(clientInfo, capabilities))
        synchronized(lock) {
            var state = ServerState.from(result.snapshot).copy(connected = true)
            while (early.isNotEmpty()) state = state.after(early.removeFirst())
            mutableState.value = state
            ready = true
        }
        peer.notify(ClientNotifications.INITIALIZED, Empty)
        return result
    }

    suspend fun <P, R> request(method: RequestMethod<P, R>, params: P): R = peer.request(method, params)

    /**
     * Follows [threadId] beside the current thread: its items go to [ServerState.threads] and stay live, a run in it
     * streaming as the current thread's does. Notifications for it that arrive before the answer are applied over it.
     */
    suspend fun follow(threadId: String): ThreadSnapshot {
        synchronized(lock) { following[threadId] = ArrayDeque() }
        val snapshot = try {
            peer.request(ClientRequests.THREAD_SUBSCRIBE, ThreadIdParams(threadId))
        } catch (refused: Exception) {
            synchronized(lock) { following.remove(threadId) }
            throw refused
        }
        synchronized(lock) {
            val missed = following.remove(threadId)
            val now = mutableState.value
            mutableState.value = if (threadId == now.activeSessionId) {
                // The current thread's items took every notification already, the ones the answer holds too.
                now.copy(threads = now.threads + (threadId to now.items))
            } else {
                var state = now.copy(threads = now.threads + (threadId to snapshot.items))
                missed?.forEach { state = state.afterInFollowed(it, threadId) }
                state
            }
        }
        return snapshot
    }

    suspend fun unfollow(threadId: String) {
        peer.request(ClientRequests.THREAD_UNSUBSCRIBE, ThreadIdParams(threadId))
        mutableState.update { it.copy(threads = it.threads - threadId) }
    }

    /** Tells the server something, like how what this client presents stands. */
    suspend fun <P> notify(method: NotificationMethod<P>, params: P) = peer.notify(method, params)

    /** Answers the server's [method] requests with [block]; registered before [connect]. */
    fun <P, R> handle(method: RequestMethod<P, R>, block: suspend (P) -> R) {
        handlers[method.name] = { request -> resultOf(method, block(request.paramsOf(method))) }
    }

    /** Opens or folds [itemId] for this client only. */
    fun setFolded(itemId: Long, folded: Boolean) {
        mutableState.update { it.copy(folded = it.folded + (itemId to folded)) }
    }

    fun close() = peer.close()

    private suspend fun receive(notification: JsonRpcNotification) {
        synchronized(lock) {
            if (ready) mutableState.update { it.after(notification) } else early.addLast(notification)
            if (following.isNotEmpty()) notification.threadOf()?.let { following[it]?.addLast(notification) }
        }
        if (notification.method == ServerNotifications.APPROVAL_RESOLVED.name) {
            asking.remove(notification.paramsOf(ServerNotifications.APPROVAL_RESOLVED).pendingId)?.cancel()
        }
        mutableNotifications.emit(notification)
    }

    private suspend fun answer(request: JsonRpcRequest): JsonElement? = when (request.method) {
        ServerRequests.APPROVAL_REQUEST.name -> {
            val ask = request.paramsOf(ServerRequests.APPROVAL_REQUEST)
            val handler = approvals ?: throw JsonRpcException(JsonRpcErrorCodes.METHOD_NOT_FOUND, "no approval handler")
            val pending = scope.async { handler(ask) }
            asking[ask.pendingId] = pending
            val decision = try {
                pending.await()
            } catch (calledOff: CancellationException) {
                // Answered elsewhere first: the server hears so and stops waiting on this client.
                if (pending.isCancelled && currentCoroutineContext().isActive) {
                    throw JsonRpcException(JsonRpcErrorCodes.REJECTED, "answered elsewhere")
                }
                throw calledOff
            } finally {
                asking.remove(ask.pendingId)
            } ?: throw JsonRpcException(JsonRpcErrorCodes.REJECTED, "left unanswered")
            resultOf(ServerRequests.APPROVAL_REQUEST, ApprovalResponse(decision))
        }
        else -> handlers[request.method]?.invoke(request) ?: throw JsonRpcException(JsonRpcErrorCodes.METHOD_NOT_FOUND, request.method)
    }

    private companion object {
        const val NOTIFICATION_BUFFER = 256
    }
}

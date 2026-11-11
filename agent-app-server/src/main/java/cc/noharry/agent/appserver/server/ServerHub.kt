package cc.noharry.agent.appserver.server

import cc.noharry.agent.appserver.protocol.ApprovalDecision
import cc.noharry.agent.appserver.protocol.ClientCapabilities
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.PresentationReports
import cc.noharry.agent.appserver.protocol.PresentationState
import cc.noharry.agent.appserver.protocol.ServerInfo
import cc.noharry.agent.appserver.protocol.ServerSnapshot
import cc.noharry.agent.appserver.protocol.ThreadSnapshot
import cc.noharry.agent.appserver.protocol.TrajectorySection
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcErrorCodes
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.protocol.jsonrpc.paramsOf
import cc.noharry.agent.appserver.protocol.transport.MessageChannel
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel

/** What the server does when a client asks; every call comes on the hub's main dispatcher. */
internal interface ServerBackend {
    /** A client joined: whatever the runtime refreshes when a screen comes up (models, context, trajectory). */
    fun clientJoined()

    /** Starts a new thread, which becomes the current one. */
    fun startThread()

    /** A thread's items now, current or not. */
    fun readThread(threadId: String): List<ConversationItem>

    /** A client follows [threadId]: it is kept live beside the current thread; its items now. */
    fun followThread(threadId: String): List<ConversationItem>

    /** No client follows [threadId] any more. */
    fun unfollowThread(threadId: String)

    fun selectThread(threadId: String)

    fun deleteThread(threadId: String)

    /** The person's words to the current thread. */
    fun startTurn(text: String)

    /** The person's words to [threadId], which stays beside the current thread. */
    fun startTurnIn(threadId: String, text: String)

    /** Words added to the run going on. */
    fun steer(text: String)

    /** Stops the run in [threadId], the current thread's when null. */
    fun interrupt(threadId: String?)

    fun respondApproval(pendingId: String, decision: ApprovalDecision)
}

/**
 * The server's side of every connection: [publish] takes each state the server reaches (on [main]) and hands what
 * changed to the connected clients; [connect] adds a client over a channel. A client starts from the state last
 * published, so a change made while it joins reaches it exactly once.
 */
internal class ServerHub(
    val backend: ServerBackend,
    val settings: SettingsBackend,
    val main: CoroutineDispatcher,
    val scope: CoroutineScope,
    serverInfo: ServerInfo,
    modules: List<RouteModule> = emptyList(),
    /** Starts what the state comes from; run once, when the first client that takes state joins ([ensureStarted]). */
    private val startSources: () -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    /** The server's features as they are now, when they can change while it runs (plugins start and stop). */
    private val features: (() -> Set<String>)? = null,
) {
    private val baseInfo = serverInfo

    /** The server as the handshake names it, with the features of this moment. */
    val serverInfo: ServerInfo get() = features?.let { baseInfo.copy(features = it()) } ?: baseInfo

    /** A plugin started or stopped: every client hears the features as they are now. */
    fun featuresChanged() = broadcast(
        JsonRpcNotification(
            cc.noharry.agent.appserver.protocol.PluginNotifications.FEATURES.name,
            cc.noharry.agent.appserver.protocol.jsonrpc.ProtocolJson.encodeToJsonElement(
                cc.noharry.agent.appserver.protocol.PluginNotifications.FEATURES.params,
                cc.noharry.agent.appserver.protocol.ServerFeaturesParams(serverInfo.features),
            ),
        ),
    )

    private val publisher = StatePublisher()
    private val connections = CopyOnWriteArraySet<ServerConnection>()
    private val started = AtomicBoolean(false)
    val routes = ServerRoutes(this, modules)

    /** The client presenting the assistant on this device, as it reported and as the server reaches it. */
    val presentation = PresentationLink(this)

    /** Starts the state's sources if nothing has yet: a client that takes state joined, or the server was woken. */
    internal fun ensureStarted() {
        if (started.compareAndSet(false, true)) startSources()
    }

    /** The state every subscribed client holds. */
    val published: ServerState? get() = publisher.published

    fun publish(state: ServerState) {
        val publication = publisher.publish(state)
        connections.forEach { it.deliver(publication) }
    }

    /** Sends [notification] to every client that shook hands, beside the state. */
    fun broadcast(notification: JsonRpcNotification) {
        connections.forEach { it.deliver(Publication(listOf(Outgoing(notification)), emptyList(), published?.activeSessionId.orEmpty())) }
    }

    /**
     * [connection] follows [threadId]: kept live, its items as they are now, then each change as a notification. The
     * thread is loaded (and published) before the client is counted in, so it hears each change after this exactly once.
     */
    fun follow(connection: ServerConnection, threadId: String): ThreadSnapshot {
        if (connection.isClosed) throw JsonRpcException(JsonRpcErrorCodes.CONNECTION_CLOSED, "connection closed")
        if (published?.sessions?.none { it.sessionId == threadId } != false) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "no thread $threadId")
        }
        val loaded = backend.followThread(threadId)
        val now = published
        val items = if (threadId == now?.activeSessionId) now.items else now?.threads?.get(threadId) ?: loaded
        connection.followed += threadId
        return ThreadSnapshot(threadId, items)
    }

    internal fun unfollow(connection: ServerConnection, threadId: String) {
        connection.followed -= threadId
        if (!isFollowed(threadId)) backend.unfollowThread(threadId)
    }

    /** Whether any client follows [threadId]. */
    internal fun isFollowed(threadId: String): Boolean = connections.any { threadId in it.followed }

    /**
     * A client on [channel], from inside the app or outside it alike: nothing the server publishes holds a secret. One
     * that stops reading is let go once [queueLimit] messages wait for it.
     */
    fun connect(channel: MessageChannel, queueLimit: Int = Channel.UNLIMITED): ServerConnection =
        ServerConnection(this, channel, onError, queueLimit).also { it.start() }

    /**
     * Called on [main]: the snapshot [connection] starts from, after which it gets every publication. A client that
     * takes no state (the app's presentation) starts nothing and gets an empty snapshot.
     */
    fun subscribe(connection: ServerConnection, capabilities: ClientCapabilities): ServerSnapshot {
        if (connection.isClosed) throw JsonRpcException(JsonRpcErrorCodes.CONNECTION_CLOSED, "connection closed")
        if (!capabilities.state) {
            connections += connection
            return ServerSnapshot()
        }
        ensureStarted()
        backend.clientJoined()
        val state = published ?: ServerState()
        // Joined after the snapshot is taken: what changes from here reaches it once, as a notification.
        connections += connection
        return state.snapshot().let { snapshot ->
            snapshot.copy(sections = snapshot.sections.filter { capabilities.trajectory || it !is TrajectorySection })
        }
    }

    /** Called on [main] when [connection] closed. */
    fun remove(connection: ServerConnection) {
        connections -= connection
        if (connection.capabilities?.presentation == true && presenter() == null) presentation.reported(PresentationState())
        connection.followed.filterNot(::isFollowed).forEach(backend::unfollowThread)
    }

    /** The latest client that presents the assistant, if one is connected. */
    internal fun presenter(): ServerConnection? = connections.lastOrNull { it.capabilities?.presentation == true }

    /** A notification from [connection]; on [main]. */
    internal fun clientNotified(connection: ServerConnection, notification: JsonRpcNotification) {
        if (notification.method == PresentationReports.STATE.name && connection == presenter()) {
            presentation.reported(notification.paramsOf(PresentationReports.STATE))
        }
    }

    internal val connectionCount: Int get() = connections.size
}

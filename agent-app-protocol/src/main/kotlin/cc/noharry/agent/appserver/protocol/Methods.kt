package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.jsonrpc.NotificationMethod
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Params or a result with nothing in them, `{}` on the wire. */
@Serializable
data object Empty

@Serializable
data class ClientInfo(val name: String, val version: String = "", val title: String = "")

/**
 * What a client takes on: answering approvals and questions the server asks for, following the trajectory (large and
 * changing often, so only a client that shows it should take it), and presenting the assistant on this device's screen
 * ([PresentationRequests]). A client that sets [state] false takes no state at all, so connecting it starts nothing.
 */
@Serializable
data class ClientCapabilities(
    val approvals: Boolean = false,
    val questions: Boolean = false,
    val trajectory: Boolean = false,
    val presentation: Boolean = false,
    val state: Boolean = true,
)

@Serializable
data class InitializeParams(val clientInfo: ClientInfo, val capabilities: ClientCapabilities = ClientCapabilities())

/** The server, and which of its own features its host turned on ([ServerFeatures]), for a client to offer only those. */
@Serializable
data class ServerInfo(val name: String, val version: String = "", val features: Set<String> = emptySet())

/**
 * The server's own features as [ServerInfo.features] names them. One left off has no requests of its own (asking one
 * gets "method not found").
 */
object ServerFeatures {
    /** Timed and watched plans: the `plans/` requests, and the plan sections, which stay empty while it is off. */
    const val PLANS = "plans"

    /** Operating the screen: `tools/setAndroidUse` and the presenting client's `androidUse/` requests. */
    const val ANDROID_USE = "androidUse"

    /** Playback and volume tools. */
    const val DEVICE_MEDIA = "deviceMedia"

    /** The option, panel and card tools, whose offers fill the offers section (the image tool's pictures fill it too). */
    const val CARDS = "cards"

    /** Writing the workspace: write_file and edit_file, and their rows among the basic tools. */
    const val FILE_WRITING = "fileWriting"

    /** The web: web_search and web_fetch (and a model service's own search), and their rows among the basic tools. */
    const val WEB = "web"

    /** Scripts the model writes: exec_js, and its row among the basic tools. */
    const val SCRIPTS = "scripts"

    /** Subagents: spawn_agent, wait_agent and cancel_agent, their rows, and the subagent settings and their requests. */
    const val SUBAGENTS = "subagents"

    /**
     * What the model remembers about the person: the user_memory tool, the memory section, and `memory/setEnabled`,
     * `memory/write`, `memory/delete` and `memory/clear`. `memory/lessons` (the runtime's own lessons) is there either way.
     */
    const val MEMORY = "memory"
}

/** The handshake's answer: the server, and the state to start from; notifications that follow are changes to it. */
@Serializable
data class InitializeResult(
    val protocolVersion: Int,
    val serverInfo: ServerInfo,
    val snapshot: ServerSnapshot,
)

@Serializable
data class ThreadListResult(val threads: List<SessionSummary>, val activeThreadId: String)

@Serializable
data class ThreadIdParams(val threadId: String)

@Serializable
data class ThreadStartResult(val threadId: String)

/**
 * The person's words to the current thread, or to [threadId]: after switching to it, or with [focus] false in it as it
 * is, the current thread staying current (a client following it sees the turn: `thread/subscribe`).
 */
@Serializable
data class TurnStartParams(val text: String, val threadId: String? = null, val focus: Boolean = true)

/** Stops the run in [threadId], the current thread's when null. */
@Serializable
data class TurnInterruptParams(val threadId: String? = null)

@Serializable
data class TextParams(val text: String)

@Serializable
enum class ApprovalDecision {
    @SerialName("approve")
    APPROVE,

    @SerialName("deny")
    DENY,
}

@Serializable
data class ApprovalRespondParams(val pendingId: String, val decision: ApprovalDecision)

@Serializable
data class ThreadListChanged(val threads: List<SessionSummary>, val activeThreadId: String)

@Serializable
data class TurnStarted(val threadId: String, val requestId: String, val startedAtEpochMillis: Long? = null)

@Serializable
data class TurnCompleted(val threadId: String, val requestId: String, val result: RunResult? = null)

@Serializable
data class ItemChanged(val threadId: String, val item: ConversationItem)

/** Text added to the end of a message item's content. */
@Serializable
data class ItemDelta(val threadId: String, val itemId: Long, val delta: String)

@Serializable
data class ItemRemoved(val threadId: String, val itemId: Long)

@Serializable
data class StateChanged(val section: StateSection)

/**
 * A tool waits for a yes: which call, and what it would do. The first client to answer decides. With [alwaysAsk] the
 * call asks every time whatever the [mode]: a yes covers it alone and is not remembered (offer no "always allow").
 */
@Serializable
data class ApprovalRequest(
    val threadId: String,
    val itemId: Long,
    val pendingId: String,
    val domain: String = "",
    val mode: ConfirmationMode,
    val toolName: String = "",
    val argumentsJson: String? = null,
    val alwaysAsk: Boolean = false,
)

@Serializable
data class ApprovalResponse(val decision: ApprovalDecision)

@Serializable
data class ApprovalResolved(val pendingId: String, val status: ConfirmationStatus)

/** Requests a client sends the server. */
object ClientRequests {
    val INITIALIZE = RequestMethod("initialize", InitializeParams.serializer(), InitializeResult.serializer())
    val THREAD_LIST = RequestMethod("thread/list", Empty.serializer(), ThreadListResult.serializer())
    val THREAD_START = RequestMethod("thread/start", Empty.serializer(), ThreadStartResult.serializer())
    val THREAD_SELECT = RequestMethod("thread/select", ThreadIdParams.serializer(), ThreadSnapshot.serializer())
    val THREAD_DELETE = RequestMethod("thread/delete", ThreadIdParams.serializer(), Empty.serializer())

    /** A thread's items now, any thread, current or not; nothing follows. */
    val THREAD_READ = RequestMethod("thread/read", ThreadIdParams.serializer(), ThreadSnapshot.serializer())

    /**
     * Follows a thread beside the current one: its items now, then its item, turn and approval notifications as they
     * come, live while a run goes on in it. Every client follows the current thread anyway.
     */
    val THREAD_SUBSCRIBE = RequestMethod("thread/subscribe", ThreadIdParams.serializer(), ThreadSnapshot.serializer())
    val THREAD_UNSUBSCRIBE = RequestMethod("thread/unsubscribe", ThreadIdParams.serializer(), Empty.serializer())
    val TURN_START = RequestMethod("turn/start", TurnStartParams.serializer(), Empty.serializer())
    val TURN_STEER = RequestMethod("turn/steer", TextParams.serializer(), Empty.serializer())
    val TURN_INTERRUPT = RequestMethod("turn/interrupt", TurnInterruptParams.serializer(), Empty.serializer())
    val APPROVAL_RESPOND = RequestMethod("approval/respond", ApprovalRespondParams.serializer(), Empty.serializer())
}

/** Notifications a client sends the server. */
object ClientNotifications {
    /** After [ClientRequests.INITIALIZE]'s answer: from now on the client wants notifications. */
    val INITIALIZED = NotificationMethod("initialized", Empty.serializer())
}

/** Notifications the server sends its clients. */
object ServerNotifications {
    val THREAD_LIST_CHANGED = NotificationMethod("thread/list/changed", ThreadListChanged.serializer())
    val THREAD_SELECTED = NotificationMethod("thread/selected", ThreadSnapshot.serializer())
    val TURN_STARTED = NotificationMethod("turn/started", TurnStarted.serializer())
    val TURN_COMPLETED = NotificationMethod("turn/completed", TurnCompleted.serializer())
    val ITEM_STARTED = NotificationMethod("item/started", ItemChanged.serializer())
    val ITEM_UPDATED = NotificationMethod("item/updated", ItemChanged.serializer())
    val ITEM_DELTA = NotificationMethod("item/delta", ItemDelta.serializer())
    val ITEM_REMOVED = NotificationMethod("item/removed", ItemRemoved.serializer())
    val STATE_CHANGED = NotificationMethod("state/changed", StateChanged.serializer())
    val APPROVAL_RESOLVED = NotificationMethod("approval/resolved", ApprovalResolved.serializer())
}

/** Requests the server sends a client that took them on in [ClientCapabilities]. */
object ServerRequests {
    val APPROVAL_REQUEST = RequestMethod("approval/request", ApprovalRequest.serializer(), ApprovalResponse.serializer())
}

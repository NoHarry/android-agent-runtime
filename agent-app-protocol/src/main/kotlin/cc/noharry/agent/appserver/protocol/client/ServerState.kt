package cc.noharry.agent.appserver.protocol.client

import kotlinx.serialization.KSerializer
import cc.noharry.agent.appserver.protocol.A2aClientSection
import cc.noharry.agent.appserver.protocol.A2aClientState
import cc.noharry.agent.appserver.protocol.A2aModuleState
import cc.noharry.agent.appserver.protocol.A2aSection
import cc.noharry.agent.appserver.protocol.ArtifactPreview
import cc.noharry.agent.appserver.protocol.ArtifactSection
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ContextPanelState
import cc.noharry.agent.appserver.protocol.ContextSection
import cc.noharry.agent.appserver.protocol.ContextUsage
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.CurrentRequest
import cc.noharry.agent.appserver.protocol.DEFAULT_MAX_ACTIVE_SESSIONS
import cc.noharry.agent.appserver.protocol.DelegationView
import cc.noharry.agent.appserver.protocol.DelegationsSection
import cc.noharry.agent.appserver.protocol.DemoSection
import cc.noharry.agent.appserver.protocol.GeneratedPicture
import cc.noharry.agent.appserver.protocol.McpProviderState
import cc.noharry.agent.appserver.protocol.McpSection
import cc.noharry.agent.appserver.protocol.ModelSection
import cc.noharry.agent.appserver.protocol.Plan
import cc.noharry.agent.appserver.protocol.PlanFiring
import cc.noharry.agent.appserver.protocol.PlansSection
import cc.noharry.agent.appserver.protocol.Preferences
import cc.noharry.agent.appserver.protocol.PreferencesSection
import cc.noharry.agent.appserver.protocol.MemorySection
import cc.noharry.agent.appserver.protocol.MemoryView
import cc.noharry.agent.appserver.protocol.UsageSection
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.protocol.ExtensionSection
import cc.noharry.agent.appserver.protocol.RunActivity
import cc.noharry.agent.appserver.protocol.RunResult
import cc.noharry.agent.appserver.protocol.RunSection
import cc.noharry.agent.appserver.protocol.RuntimeDemoState
import cc.noharry.agent.appserver.protocol.ServerNotifications
import cc.noharry.agent.appserver.protocol.ServerSnapshot
import cc.noharry.agent.appserver.protocol.SessionBoardState
import cc.noharry.agent.appserver.protocol.SessionSummary
import cc.noharry.agent.appserver.protocol.StateChanged
import cc.noharry.agent.appserver.protocol.StateSection
import cc.noharry.agent.appserver.protocol.SubscriptionState
import cc.noharry.agent.appserver.protocol.ThreadListChanged
import cc.noharry.agent.appserver.protocol.ThreadSnapshot
import cc.noharry.agent.appserver.protocol.TokenUsageState
import cc.noharry.agent.appserver.protocol.TrajectorySection
import cc.noharry.agent.appserver.protocol.TrajectorySnapshot
import cc.noharry.agent.appserver.protocol.WorkspaceSection
import cc.noharry.agent.appserver.protocol.WorkspaceSelectionState
import cc.noharry.agent.appserver.protocol.foldedByDefault
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.protocol.jsonrpc.paramsOf
import cc.noharry.agent.appserver.protocol.SubagentsSection
import cc.noharry.agent.appserver.protocol.SubagentLane
import cc.noharry.agent.appserver.protocol.OffersSection
import cc.noharry.agent.appserver.protocol.view.Offer

/**
 * Everything a front end shows, in one value: the threads ([sessions]), the current one's [items], and the fields of
 * each [StateSection]. The server builds it and sends it as a snapshot and sections; a client folds those back into
 * the same value ([from], [after]). [folded] is the client's own: which items the person opened or closed, over
 * [ConversationItem.foldedByDefault].
 */
data class ServerState(
    /** What the current thread's run is doing, and the tool when one runs ([RunSection]). */
    val activity: RunActivity = RunActivity.IDLE,
    val activityTool: String = "",
    /** The runtime's own status line, for debug views only. */
    val runtimeStatus: String = "",
    val providerLabel: String = "",
    val stream: Boolean = false,
    val simulated: Boolean = false,
    val confirmationMode: ConfirmationMode = ConfirmationMode.ALLOW_ALL_SESSION,
    val isRunning: Boolean = false,
    val configSource: String = "",
    val configEditor: ProviderEditState = ModelSection().configEditor,
    val sessions: List<SessionSummary> = emptyList(),
    val activeSessionId: String = "",
    val items: List<ConversationItem> = emptyList(),
    val contextPanel: ContextPanelState = ContextSection().contextPanel,
    val board: SessionBoardState = ContextSection().board,
    val tokenUsage: TokenUsageState = TokenUsageState(),
    val runtimeDemo: RuntimeDemoState = RuntimeDemoState(),
    val mcpProviders: List<McpProviderState> = emptyList(),
    val a2aClients: A2aModuleState = A2aModuleState(),
    val workspaceSelection: WorkspaceSelectionState = WorkspaceSelectionState(),
    val currentRequest: CurrentRequest? = null,
    val artifactPreview: ArtifactPreview? = null,
    /** Read-only run trajectory for the selected session; empty until the first refresh lands. */
    val trajectory: TrajectorySnapshot = TrajectorySnapshot(sessionId = ""),
    /** Wall-clock start of the active run; null when idle. */
    val runStartedAtEpochMillis: Long? = null,
    /** How the latest run ended. */
    val lastRun: RunResult? = null,
    /** Count of sessions that currently own an active run. Selected-session UI only. */
    val activeSessionCount: Int = 0,
    val maxActiveSessions: Int = DEFAULT_MAX_ACTIVE_SESSIONS,
    /** Every thread with a run going on, with the run's request id. */
    val runningThreads: Map<String, String> = emptyMap(),
    /**
     * Threads beside the current one and their items: on a client, those it follows (`thread/subscribe`); on the
     * server, every one it keeps live (followed, or a run going on in it).
     */
    val threads: Map<String, List<ConversationItem>> = emptyMap(),
    val a2aClient: A2aClientState = A2aClientState(),
    val plans: List<Plan> = emptyList(),
    val planHistory: List<PlanFiring> = emptyList(),
    val planFeeds: Map<String, SubscriptionState> = emptyMap(),
    val delegations: List<DelegationView> = emptyList(),
    /** The latest offer per session and the pictures drawn for A2A tasks ([OffersSection]). */
    val offers: Map<String, Offer> = emptyMap(),
    val offerPictures: Map<String, GeneratedPicture> = emptyMap(),
    /** Subagents by parent session, and their reports on the way ([SubagentsSection]). */
    val subagents: Map<String, List<SubagentLane>> = emptyMap(),
    val subagentDeliveries: Map<String, Long> = emptyMap(),
    val preferences: Preferences = Preferences(),
    val memory: MemoryView = MemoryView(),
    /** Changes whenever the usage ledger or prices do; read them with UsageRequests.READ. */
    val usageRevision: Long = 0,
    /** How full the model's context window is, as of the latest request. */
    val contextUsage: ContextUsage = ContextUsage(),
    /** Sections an app's server adds, by name ([ExtensionSection]); read them with [extension]. */
    val extensions: Map<String, ExtensionSection> = emptyMap(),
    /** Items the person opened or folded, over [ConversationItem.foldedByDefault]. */
    val folded: Map<Long, Boolean> = emptyMap(),
    /** Whether a client's connection is up; a state the server builds for itself is always connected. */
    val connected: Boolean = true,
) {
    fun isFolded(item: ConversationItem): Boolean = folded[item.id] ?: item.foldedByDefault

    /** This state's sections, as the server sends them. */
    fun sections(): List<StateSection> = listOf(
        RunSection(
            activity, activityTool, runtimeStatus, isRunning, currentRequest, runStartedAtEpochMillis, lastRun,
            activeSessionCount, maxActiveSessions, runningThreads,
        ),
        ModelSection(providerLabel, stream, simulated, configSource, confirmationMode, configEditor),
        ContextSection(contextPanel, board, tokenUsage, contextUsage),
        DemoSection(runtimeDemo),
        McpSection(mcpProviders),
        A2aSection(a2aClients),
        WorkspaceSection(workspaceSelection),
        ArtifactSection(artifactPreview),
        TrajectorySection(trajectory),
        A2aClientSection(a2aClient),
        PlansSection(plans, planHistory, planFeeds),
        DelegationsSection(delegations),
        OffersSection(offers, offerPictures),
        SubagentsSection(subagents, subagentDeliveries),
        PreferencesSection(preferences),
        MemorySection(memory),
        UsageSection(usageRevision),
    ) + extensions.values

    /** [this] with [section]'s fields. */
    fun with(section: StateSection): ServerState = when (section) {
        is RunSection -> copy(
            activity = section.activity,
            activityTool = section.tool,
            runtimeStatus = section.runtimeStatus,
            isRunning = section.isRunning,
            currentRequest = section.currentRequest,
            runStartedAtEpochMillis = section.runStartedAtEpochMillis,
            lastRun = section.lastRun,
            activeSessionCount = section.activeSessionCount,
            maxActiveSessions = section.maxActiveSessions,
            runningThreads = section.runningThreads,
        )
        is ModelSection -> copy(
            providerLabel = section.providerLabel,
            stream = section.stream,
            simulated = section.simulated,
            configSource = section.configSource,
            confirmationMode = section.confirmationMode,
            configEditor = section.configEditor,
        )
        is ContextSection -> copy(contextPanel = section.contextPanel, board = section.board, tokenUsage = section.tokenUsage, contextUsage = section.contextUsage)
        is DemoSection -> copy(runtimeDemo = section.runtimeDemo)
        is McpSection -> copy(mcpProviders = section.mcpProviders)
        is A2aSection -> copy(a2aClients = section.a2aClients)
        is WorkspaceSection -> copy(workspaceSelection = section.workspaceSelection)
        is ArtifactSection -> copy(artifactPreview = section.artifactPreview)
        is TrajectorySection -> copy(trajectory = section.trajectory)
        is A2aClientSection -> copy(a2aClient = section.a2aClient)
        is PlansSection -> copy(plans = section.plans, planHistory = section.planHistory, planFeeds = section.planFeeds)
        is DelegationsSection -> copy(delegations = section.delegations)
        is OffersSection -> copy(offers = section.offers, offerPictures = section.pictures)
        is SubagentsSection -> copy(subagents = section.board, subagentDeliveries = section.deliveries)
        is PreferencesSection -> copy(preferences = section.preferences)
        is MemorySection -> copy(memory = section.memory)
        is UsageSection -> copy(usageRevision = section.revision)
        is ExtensionSection -> copy(extensions = extensions + (section.name to section))
    }

    /** What the server sends a client to start from. */
    fun snapshot(): ServerSnapshot = ServerSnapshot(
        threads = sessions,
        activeThreadId = activeSessionId,
        thread = ThreadSnapshot(activeSessionId, items),
        sections = sections(),
    )

    companion object {
        fun from(snapshot: ServerSnapshot): ServerState = snapshot.sections.fold(
            ServerState(
                sessions = snapshot.threads,
                activeSessionId = snapshot.activeThreadId,
                items = snapshot.thread.items.takeIf { snapshot.thread.threadId == snapshot.activeThreadId }.orEmpty(),
            ),
        ) { state, section -> state.with(section) }
    }
}

/** The extension section [name] as [serializer] reads it; null when the server sent none. */
fun <T : Any> ServerState.extension(name: String, serializer: KSerializer<T>): T? = extensions[name]?.value(serializer)

/** [this] with [value] as the extension section [name]. */
fun <T> ServerState.withExtension(name: String, serializer: KSerializer<T>, value: T): ServerState =
    copy(extensions = extensions + (name to ExtensionSection.of(name, serializer, value)))

/** [this] after [notification]; notifications the state does not hold (turn events, resolutions) leave it as it is. */
fun ServerState.after(notification: JsonRpcNotification): ServerState = when (notification.method) {
    ServerNotifications.THREAD_SELECTED.name -> withThread(notification.paramsOf(ServerNotifications.THREAD_SELECTED))
    else -> notification.threadOf()?.let { thread -> inThread(thread) { items -> items.after(notification) }.forgetFolds(notification) }
        ?: afterOthers(notification)
}

/** A removed item's fold choice goes with it. */
private fun ServerState.forgetFolds(notification: JsonRpcNotification): ServerState =
    if (notification.method == ServerNotifications.ITEM_REMOVED.name) copy(folded = folded - notification.paramsOf(ServerNotifications.ITEM_REMOVED).itemId) else this

/** [this] after [notification] in its followed thread only: what a client missed while its follow was answered. */
fun ServerState.afterInFollowed(notification: JsonRpcNotification, threadId: String): ServerState {
    val items = threads[threadId] ?: return this
    if (notification.threadOf() != threadId) return this
    return copy(threads = threads + (threadId to items.after(notification)))
}

/** The thread an item notification is about; null for every other notification. */
fun JsonRpcNotification.threadOf(): String? = when (method) {
    ServerNotifications.ITEM_STARTED.name, ServerNotifications.ITEM_UPDATED.name -> paramsOf(ServerNotifications.ITEM_STARTED).threadId
    ServerNotifications.ITEM_DELTA.name -> paramsOf(ServerNotifications.ITEM_DELTA).threadId
    ServerNotifications.ITEM_REMOVED.name -> paramsOf(ServerNotifications.ITEM_REMOVED).threadId
    else -> null
}

/** [change] applied to [threadId]'s items: the current thread's, a followed thread's, or both when they are one. */
private fun ServerState.inThread(threadId: String, change: (List<ConversationItem>) -> List<ConversationItem>): ServerState {
    val followed = threads[threadId]
    val current = threadId == activeSessionId
    if (followed == null && !current) return this
    return copy(
        items = if (current) change(items) else items,
        threads = if (followed != null) threads + (threadId to change(followed)) else threads,
    )
}

/** Item notifications change one list of items; any other leaves it. */
private fun List<ConversationItem>.after(notification: JsonRpcNotification): List<ConversationItem> = when (notification.method) {
    ServerNotifications.ITEM_STARTED.name -> withItem(notification.paramsOf(ServerNotifications.ITEM_STARTED).item)
    ServerNotifications.ITEM_UPDATED.name -> withItem(notification.paramsOf(ServerNotifications.ITEM_UPDATED).item)
    ServerNotifications.ITEM_DELTA.name -> notification.paramsOf(ServerNotifications.ITEM_DELTA).let { withDelta(it.itemId, it.delta) }
    ServerNotifications.ITEM_REMOVED.name -> notification.paramsOf(ServerNotifications.ITEM_REMOVED).let { removed -> filterNot { it.id == removed.itemId } }
    else -> this
}

/** What is not about one thread's items. */
private fun ServerState.afterOthers(notification: JsonRpcNotification): ServerState = when (notification.method) {
    ServerNotifications.THREAD_LIST_CHANGED.name -> withThreads(notification.paramsOf(ServerNotifications.THREAD_LIST_CHANGED))
    ServerNotifications.STATE_CHANGED.name -> with(notification.paramsOf<StateChanged>(ServerNotifications.STATE_CHANGED).section)
    else -> this
}

private fun ServerState.withThreads(changed: ThreadListChanged): ServerState {
    val switched = changed.activeThreadId != activeSessionId
    return copy(
        sessions = changed.threads,
        activeSessionId = changed.activeThreadId,
        // Another thread's items never show under this one; `thread/selected` brings the new thread's.
        items = if (switched) emptyList() else items,
        folded = if (switched) emptyMap() else folded,
    )
}

/** The current thread switched: its items whole, and so the followed copy when it is followed too. */
private fun ServerState.withThread(thread: ThreadSnapshot): ServerState = copy(
    activeSessionId = thread.threadId,
    items = thread.items,
    folded = emptyMap(),
    threads = if (thread.threadId in threads) threads + (thread.threadId to thread.items) else threads,
)

/** An item started or changed: it takes its old place, or goes last when it is new. */
private fun List<ConversationItem>.withItem(item: ConversationItem): List<ConversationItem> {
    val at = indexOfFirst { it.id == item.id }
    return if (at < 0) this + item else toMutableList().apply { set(at, item) }
}

private fun List<ConversationItem>.withDelta(itemId: Long, delta: String): List<ConversationItem> {
    val at = indexOfFirst { it.id == itemId }
    val message = getOrNull(at) as? ConversationItem.MessageItem ?: return this
    return toMutableList().apply { set(at, message.copy(content = message.content + delta)) }
}

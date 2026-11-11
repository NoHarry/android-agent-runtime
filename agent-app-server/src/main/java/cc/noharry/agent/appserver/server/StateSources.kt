@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.server

import android.content.Context
import android.content.SharedPreferences
import cc.noharry.agent.appserver.model.CONTEXT_WINDOW_PREFS
import cc.noharry.agent.appserver.model.loadContextWindow
import cc.noharry.agent.appserver.protocol.ArtifactSection
import cc.noharry.agent.appserver.protocol.ContextSection
import cc.noharry.agent.appserver.protocol.ContextUsage
import cc.noharry.agent.appserver.protocol.DemoSection
import cc.noharry.agent.appserver.protocol.ExtensionSection
import cc.noharry.agent.appserver.protocol.McpSection
import cc.noharry.agent.appserver.protocol.ModelSection
import cc.noharry.agent.appserver.protocol.RunSection
import cc.noharry.agent.appserver.protocol.TrajectorySection
import cc.noharry.agent.appserver.protocol.WorkspaceSection
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.RuntimeSession
import cc.noharry.agent.appserver.session.SessionSettings
import cc.noharry.agent.appserver.skills.AppSubagents
import cc.noharry.agent.appserver.usage.UsageStore
import cc.noharry.agent.appserver.usage.contextUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Everything the server publishes, gathered on the main thread: the runtime's state ([RuntimeSession]), with the
 * server's own sections and the running plugins' beside it, taken by section without naming them. The server lives as
 * long as the process, so none of these is ever let go.
 */
internal class StateSources(
    private val app: Context,
    private val hub: ServerHub,
    private val plugins: PluginHost?,
) : PublishedState {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runtime: ServerState? = null
    private var extras = ServerState()

    // The subagent board is the runtime's experimental API; the lanes sent are the protocol's own.
    @OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)
    fun start() {
        RuntimeSession.attach(app) { state -> runtime = state; publish() }
        follow(SessionSettings.policy) { withPreferences(app) }
        follow(AppSubagents.settings) { withPreferences(app) }
        follow(UsageStore.usage) { copy(usageRevision = usageRevision + 1) }
        follow(UsageStore.prices) { copy(usageRevision = usageRevision + 1) }
        main.launch { PreferencesSource.changes.collect { extras = extras.withPreferences(app); publish() } }
        follow(AppSubagents.board) { copy(subagents = it.lanes()) }
        follow(AppSubagents.deliveries) { copy(subagentDeliveries = it) }
        watchContextWindows()
        plugins?.publishInto(this)
    }

    override val current: ServerState get() = extras

    override fun update(change: ServerState.() -> ServerState) {
        extras = extras.change()
        publish()
    }

    override fun broadcast(notification: JsonRpcNotification) = hub.broadcast(notification)


    /** A context window set in the provider settings changes the ring at once. */
    private fun watchContextWindows() {
        val prefs = app.getSharedPreferences(CONTEXT_WINDOW_PREFS, Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(windowListener)
    }

    private val windowListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        contextUse.forget()
        publish()
    }

    private val contextUse = ContextUseCache()


    override fun <T> follow(flow: Flow<T>, into: ServerState.(T) -> ServerState) {
        main.launch {
            flow.collect { value ->
                extras = extras.into(value)
                publish()
            }
        }
    }

    private fun publish() {
        val base = runtime ?: return
        // The runtime's own sections from its state; every other one (the server's own extras, the plugins') as
        // published, whatever it is, and the sections of an app's own from both.
        val published = extras.sections().filter { it::class !in RUNTIME_SECTIONS && it !is ExtensionSection }
            .fold(base) { state, section -> state.with(section) }
        hub.publish(published.copy(contextUsage = contextUse.of(base, app), extensions = base.extensions + extras.extensions))
    }
}

/** The sections the runtime's state fills ([RuntimeSession]); the context section's usage is worked out here. */
private val RUNTIME_SECTIONS = setOf(
    RunSection::class, ModelSection::class, ContextSection::class, DemoSection::class, McpSection::class,
    WorkspaceSection::class, ArtifactSection::class, TrajectorySection::class,
)

/**
 * How full the context window is, worked out again only when what it reads changed: the trajectory, the run, the
 * model, or (after [forget]) the window set for it. Main thread.
 */
private class ContextUseCache {
    private var key: List<Any?>? = null
    private var value = ContextUsage()

    fun of(state: ServerState, context: Context): ContextUsage {
        val profile = state.configEditor
        val next = listOf(state.trajectory.sessionId, state.trajectory.revision, state.isRunning, state.currentRequest?.requestId,
            profile.profileId, profile.provider, profile.endpoint, profile.baseUrl, profile.model, profile.variant)
        if (next != key) {
            key = next
            value = contextUsage(state, loadContextWindow(context, profile))
        }
        return value
    }

    fun forget() {
        key = null
    }
}


/** [this] with what the person set; operating the screen's part as its plugin published it. */
private fun ServerState.withPreferences(app: Context): ServerState =
    copy(preferences = PreferencesSource.read(app).copy(androidUse = preferences.androidUse))

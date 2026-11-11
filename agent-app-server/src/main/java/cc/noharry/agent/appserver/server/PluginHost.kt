package cc.noharry.agent.appserver.server

import cc.noharry.agent.appserver.InternalAppServerApi

import android.app.Application
import android.content.Context
import cc.noharry.agent.appserver.AppServerPluginApi
import cc.noharry.agent.appserver.model.ProviderAdapter
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.PluginView
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.PreviousExchange
import cc.noharry.agent.appserver.runtime.ExecutionEvents
import cc.noharry.agent.appserver.runtime.ExecutionSubscription
import cc.noharry.agent.appserver.runtime.RunFacts
import cc.noharry.agent.appserver.runtime.RunIntake
import cc.noharry.agent.appserver.runtime.RuntimeSession
import cc.noharry.agent.appserver.runtime.RuntimeVoice
import cc.noharry.agent.appserver.runtime.VoicePort
import cc.noharry.agent.appserver.runtime.toolServerName
import cc.noharry.agent.appserver.session.ApprovalSettings
import cc.noharry.agent.appserver.tools.isCallFailure
import cc.noharry.agent.SteerResult
import cc.noharry.agent.spi.RuntimeContextProvider
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * What the running plugins add to the server, as its own code reads it: one value, replaced whole when a plugin starts
 * or stops, so a run or a request sees the plugins of one moment.
 */
@InternalAppServerApi
class PluginAdditions(
    val offers: Set<String> = emptySet(),
    val providerAdapters: List<ProviderAdapter> = emptyList(),
    val tools: List<PluginTools> = emptyList(),
    val toolWrappers: List<(ToolProvider) -> ToolProvider> = emptyList(),
    val inputs: List<(String, String, PreviousExchange?) -> String?> = emptyList(),
    val routes: Map<String, PluginRoute> = emptyMap(),
    /** Each name once: the first plugin's, in the order they started. */
    val skills: List<PluginSkill> = emptyList(),
    val rules: List<String> = emptyList(),
    val mcpServers: List<PluginMcpServer> = emptyList(),
    /** Each id once: the first plugin's. */
    val toolServers: List<PluginToolServer> = emptyList(),
    val runTools: List<(RunFacts) -> List<ToolProvider>> = emptyList(),
    val contextLines: List<(RunFacts) -> List<String>> = emptyList(),
    val contextProviders: List<(RunFacts) -> List<RuntimeContextProvider>> = emptyList(),
    /**
     * The tool servers running plugins name as their own ([PluginContext.ownToolServer]; one ending in `*` names those
     * whose id starts with the rest), each with what the plugin says of it.
     */
    val ownToolServers: Map<String, OwnToolServer> = emptyMap(),
    /** Every listed plugin's: a running one's [PluginContext.onClearData], one that is off its [AppServerPlugin.clearWhileOff]. */
    val dataClearers: List<(DataClearing) -> Boolean> = emptyList(),
    /** What each running plugin that asked to start after boot does when the clock is set ([PluginContext.startOnBoot]). */
    val clockChanged: List<() -> Unit> = emptyList(),
    val runHooks: List<(RunFacts) -> List<RuntimeHook>> = emptyList(),
    val skillSections: List<(Context) -> Set<String>> = emptyList(),
    val turnLabels: List<(String) -> String?> = emptyList(),
    val localTurns: List<() -> Unit> = emptyList(),
    /** What running plugins offer the server ([PluginContext.provide]), by type; the first plugin's of each. */
    private val services: Map<Class<*>, Any> = emptyMap(),
) {
    /** Whether a running plugin asked that the app start after a reboot ([PluginContext.startOnBoot]). */
    val startsOnBoot: Boolean get() = clockChanged.isNotEmpty()

    /** Whether a call of [tool] of the plugin tool server [server] acts, when its plugin decides it; null leaves it to its `effectKind`. */
    fun acts(server: String, tool: String, arguments: String): Boolean? = own(server)?.acts?.invoke(tool, arguments)

    /** Whether [server] is a tool server a running plugin names as its own. */
    fun ownsToolServer(server: String): Boolean = own(server) != null

    /** Whether [server]'s tools are the main agent's alone ([PluginContext.ownToolServer]'s `mainAgentOnly`). */
    fun mainAgentOnly(server: String): Boolean = own(server)?.mainAgentOnly == true

    /** Whether a call of [server]'s tools counts as using a service: false for a plugin's own that says it does not. */
    internal fun isService(server: String): Boolean = own(server)?.let { it.service && !it.mainAgentOnly } ?: true

    /** The tool servers whose tools work things around the person: the plugins' own and listed ones that say so. */
    internal val deviceServers: Set<String>
        get() = ownToolServers.filter { (id, own) -> own.worksDevice && !id.endsWith(ANY_REST) }.keys + toolServers.filter { it.worksDevice }.map { it.id }

    private fun own(server: String): OwnToolServer? = ownToolServers.entries.firstOrNull { (id, _) -> names(id, server) }?.value

    /** Whether a running plugin names every tool server whose id starts as [server]'s does (`id*`) as its own. */
    fun ownsByPrefix(server: String): Boolean = ownToolServers.keys.any { it.endsWith(ANY_REST) && names(it, server) }

    /** The keys of skills' conditional sections the running plugins say hold now ([PluginContext.skillSections]). */
    fun skillSections(context: Context): Set<String> = skillSections.flatMapTo(linkedSetOf()) { it(context) }

    /** Who opened a turn with [text], as a running plugin recognizes it ([PluginContext.turnLabel]); null for the person. */
    fun turnLabel(text: String): String? = turnLabels.firstNotNullOfOrNull { it(text) }

    /** A turn started on the device itself: the running plugins hear it ([PluginContext.onLocalTurn]). */
    fun localTurnStarted() = localTurns.forEach { it() }

    /** Has every running plugin delete what it keeps of [clearing]; false when one of them left something. */
    fun clearData(clearing: DataClearing): Boolean = dataClearers.map { clear ->
        runCatching { clear(clearing) }.onFailure { android.util.Log.w("AppServer", "a plugin did not clear its data", it) }.getOrDefault(false)
    }.all { it }

    /** The service of [type] a running plugin offers, or null. */
    fun <T : Any> service(type: Class<T>): T? = services[type]?.let(type::cast)

    companion object {
        val NONE = PluginAdditions()

        /** Whether the own tool server [id] ([PluginContext.ownToolServer]) names [server]. */
        private fun names(id: String, server: String): Boolean =
            if (id.endsWith(ANY_REST)) server.startsWith(id.dropLast(1)) else id == server

        private const val ANY_REST = "*"
    }
}

/** What a running plugin says of a tool server of its own ([PluginContext.ownToolServer]). */
@InternalAppServerApi
class OwnToolServer(val mainAgentOnly: Boolean = false, val service: Boolean = true, val worksDevice: Boolean = false, val acts: ToolActs? = null)

/** Tools a running plugin gives each run ([PluginContext.tools]): [provide] makes its providers for one run. */
@InternalAppServerApi
class PluginTools(val askBeforeActions: Boolean, val provide: (Context, List<ToolProvider>) -> List<ToolProvider>)

@InternalAppServerApi
typealias PluginRoute = suspend (cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcRequest) -> kotlinx.serialization.json.JsonElement

/** Whether a call of a tool (its name, its arguments as JSON) acts ([PluginContext.ownToolServer]); null: as its `effectKind` says. */
internal typealias ToolActs = (tool: String, arguments: String) -> Boolean?

/**
 * The host's plugins: which are on (kept in [store]), which run, and starting and stopping them. A plugin runs when it
 * is on and every plugin it needs runs; stopping one stops those that need it first. Plugins can be added and removed
 * while the server runs ([add], [remove]); one that failed to start is not tried again until it is turned on or added
 * again. All of it on the main thread.
 */
@InternalAppServerApi
class PluginHost(
    private val app: Application,
    plugins: List<AppServerPlugin>,
    private val store: PluginSwitches = PreferenceSwitches(app),
    private val main: kotlin.coroutines.CoroutineContext = Dispatchers.Main.immediate,
    /** Where a plugin's deliveries go ([PluginContext.deliver]): the server's conversation; tests keep their own. */
    private val conversation: (Context, Delivery) -> Unit = { context, delivery -> RuntimeSession.deliver(context, delivery) },
) {
    private val plugins = plugins.toMutableList()
    private val running = LinkedHashMap<String, RunningPlugin>()
    private val failed = HashSet<String>()
    private var state: PublishedState? = null

    /** Told after the running plugins changed. */
    internal var onChange: (PluginAdditions) -> Unit = {}

    @Volatile
    var additions: PluginAdditions = PluginAdditions.NONE
        private set

    init {
        val ids = plugins.map { it.id }
        require(ids.size == ids.toSet().size) { "Two plugins share an id: $ids" }
        plugins.forEach { plugin -> require(ids.containsAll(plugin.needs)) { "${plugin.id} needs a plugin the host does not list: ${plugin.needs - ids.toSet()}" } }
    }

    /** Starts every plugin that is on, those others need first. */
    fun startAll() {
        try {
            settle()
        } finally {
            refresh()
        }
    }

    internal fun views(): List<PluginView> = plugins.map { plugin ->
        val view = PluginView(plugin.id, isOn(plugin), plugin.id in running, plugin.required, plugin.needs.sorted())
        (plugin as? DescribedPlugin)?.describe(view) ?: view
    }

    /** The plugin registered as [id], or null. */
    internal fun plugin(id: String): AppServerPlugin? = plugins.firstOrNull { it.id == id }

    /** Every plugin registered, running or not. */
    internal fun plugins(): List<AppServerPlugin> = plugins.toList()

    /**
     * The names the tool servers of the running plugins that [included] picks go by in a run: their MCP servers', their
     * tool servers' and their tool providers' (each provider made once here, for its id; one that fails to be made is
     * left out).
     */
    internal fun runningToolServerNames(context: Context, included: (AppServerPlugin) -> Boolean): Set<String> =
        running.filterKeys { id -> plugin(id)?.let(included) == true }.values.flatMapTo(hashSetOf()) { plugin ->
            plugin.mcpServers.map { toolServerName(it.name) } + plugin.toolServers.map { it.id } + plugin.tools.flatMap { registration ->
                try {
                    registration.provide(context, emptyList()).map { it.providerId }
                } catch (failed: Throwable) {
                    if (!failed.isCallFailure()) throw failed
                    emptyList()
                }
            }
        }

    /**
     * Registers [plugin] while the server runs, and starts it (and what was waiting for it) when it is on and what it
     * needs runs. Its id must be new and what it needs registered. When it fails to start it stays registered but
     * stopped, and the failure is thrown.
     */
    internal fun add(plugin: AppServerPlugin) {
        require(plugins.none { it.id == plugin.id }) { "Two plugins share an id: ${plugin.id}" }
        val ids = plugins.map { it.id }.toSet()
        require(ids.containsAll(plugin.needs)) { "${plugin.id} needs a plugin the host does not list: ${plugin.needs - ids}" }
        plugins += plugin
        failed -= plugin.id
        startAll()
    }

    /**
     * Puts [plugin] in place of the one registered under its id, keeping the person's switch: the old one stops (those
     * that need it with it), then [plugin] starts as [add] starts one. Returns the one it replaced, so a caller can put
     * it back when [plugin] fails.
     */
    internal fun replace(plugin: AppServerPlugin): AppServerPlugin {
        val index = plugins.indexOfFirst { it.id == plugin.id }
        require(index >= 0) { "no plugin ${plugin.id}" }
        val ids = plugins.map { it.id }.toSet()
        require(ids.containsAll(plugin.needs)) { "${plugin.id} needs a plugin the host does not list: ${plugin.needs - ids}" }
        val old = plugins[index]
        require(plugin.required == old.required) { "${plugin.id} would change whether it can be turned off" }
        try {
            stop(plugin.id)
        } finally {
            plugins[index] = plugin
            failed -= plugin.id
        }
        startAll()
        return old
    }

    /**
     * Stops [id] and forgets it, its switch included. Refused for a required plugin and for one another plugin needs:
     * that one is removed first.
     */
    internal fun remove(id: String) {
        val plugin = plugin(id) ?: throw IllegalArgumentException("no plugin $id")
        require(!plugin.required) { "$id cannot be removed" }
        val dependents = plugins.filter { id in it.needs }.map { it.id }
        require(dependents.isEmpty()) { "$dependents need $id" }
        try {
            stop(id)
        } finally {
            plugins.remove(plugin)
            failed -= id
            store.forget(id)
            refresh()
        }
    }

    /** Turns [id] on or off, keeps the choice, and starts or stops what follows from it. */
    fun setEnabled(id: String, enabled: Boolean): List<PluginView> {
        val plugin = plugin(id) ?: throw IllegalArgumentException("no plugin $id")
        require(enabled || !plugin.required) { "$id cannot be turned off" }
        store.set(id, enabled)
        if (enabled) failed -= id else stop(id)
        startAll()
        return views()
    }

    /** The server publishes state from now on: the running plugins' sections start. */
    fun publishInto(published: PublishedState) {
        state = published
        running.values.forEach { it.startPublishing(published) }
    }

    internal fun appInFront(inFront: Boolean) = running.values.toList().forEach { plugin -> plugin.front.forEach { it(inFront) } }

    internal fun presenterReady(ready: Boolean) = running.values.toList().forEach { plugin -> plugin.presenter.forEach { it(ready) } }

    private fun isOn(plugin: AppServerPlugin): Boolean = plugin.required || store.isOn(plugin.id)

    /** Starts what can run and does not yet, until nothing more can. */
    private fun settle() {
        do {
            val next = plugins.firstOrNull { it.id !in running && it.id !in failed && isOn(it) && running.keys.containsAll(it.needs) }
            next?.let(::start)
        } while (next != null)
    }

    private fun start(plugin: AppServerPlugin) {
        val context = RunningPlugin(plugin.id, app, CoroutineScope(SupervisorJob() + main), ::serviceOf, conversation)
        running[plugin.id] = context
        try {
            plugin.apply(context)
        } catch (failure: Throwable) {
            stop(plugin.id)
            failed += plugin.id
            throw failure
        }
        state?.let(context::startPublishing)
    }

    private fun stop(id: String) {
        if (id !in running) return
        plugins.filter { id in it.needs }.forEach { stop(it.id) }
        running.remove(id)?.stop()
    }

    private fun serviceOf(type: Class<*>): Any? = running.values.firstNotNullOfOrNull { it.services[type] }

    private fun refresh() {
        val all = running.values.toList()
        additions = PluginAdditions(
            offers = all.flatMapTo(linkedSetOf()) { it.offers },
            providerAdapters = all.flatMap { it.adapters },
            tools = all.flatMap { it.tools },
            toolWrappers = all.flatMap { it.wrappers },
            inputs = all.flatMap { it.inputs },
            routes = all.flatMap { it.routes.entries }.associate { it.key to it.value },
            skills = all.flatMap { it.skills }.distinctBy { it.name },
            rules = all.flatMap { it.rules },
            mcpServers = all.flatMap { it.mcpServers },
            toolServers = all.flatMap { it.toolServers }.distinctBy { it.id },
            runTools = all.flatMap { it.runTools },
            contextLines = all.flatMap { it.contextLines },
            contextProviders = all.flatMap { it.contextProviders },
            ownToolServers = all.flatMap { it.ownToolServers.entries }.associate { it.key to it.value },
            // A plugin that is off still deletes what it kept, so a clearing leaves nothing of it behind.
            dataClearers = all.flatMap { it.clearers } + plugins.filter { it.id !in running }.map { plugin -> { clearing: DataClearing -> plugin.clearWhileOff(app, clearing) } },
            clockChanged = all.flatMap { it.clocks },
            runHooks = all.flatMap { it.runHooks },
            skillSections = all.flatMap { it.sections },
            turnLabels = all.flatMap { it.turnLabels },
            localTurns = all.flatMap { it.localTurns },
            services = all.asReversed().fold(emptyMap<Class<*>, Any>()) { found, plugin -> found + plugin.services },
        )
        onChange(additions)
    }
}

/** A plugin that says more of itself than the host's list does (where it came from, its version). */
internal interface DescribedPlugin {
    fun describe(view: PluginView): PluginView
}

/** Which plugins the person left on; one never chosen is on. */
@InternalAppServerApi
interface PluginSwitches {
    fun isOn(id: String): Boolean

    fun set(id: String, on: Boolean)

    /** The plugin is gone: a new one under its id starts on. */
    fun forget(id: String) = set(id, true)
}

private class PreferenceSwitches(context: Context) : PluginSwitches {
    private val preferences = context.getSharedPreferences("app_server_plugins", Context.MODE_PRIVATE)

    override fun isOn(id: String): Boolean = preferences.getBoolean(id, true)

    override fun set(id: String, on: Boolean) {
        preferences.edit().putBoolean(id, on).apply()
    }

    override fun forget(id: String) {
        preferences.edit().remove(id).apply()
    }
}

/** One running plugin's context: what it added, kept so it can be taken back. */
private class RunningPlugin(
    private val id: String,
    override val app: Application,
    override val scope: CoroutineScope,
    private val lookup: (Class<*>) -> Any?,
    private val conversation: (Context, Delivery) -> Unit,
) : PluginContext {
    val offers = linkedSetOf<String>()
    val adapters = mutableListOf<ProviderAdapter>()
    val tools = mutableListOf<PluginTools>()
    val wrappers = mutableListOf<(ToolProvider) -> ToolProvider>()
    val inputs = mutableListOf<(String, String, PreviousExchange?) -> String?>()
    val skills = mutableListOf<PluginSkill>()
    val rules = mutableListOf<String>()
    val mcpServers = mutableListOf<PluginMcpServer>()
    val toolServers = mutableListOf<PluginToolServer>()
    val runTools = mutableListOf<(RunFacts) -> List<ToolProvider>>()
    val contextLines = mutableListOf<(RunFacts) -> List<String>>()
    val contextProviders = mutableListOf<(RunFacts) -> List<RuntimeContextProvider>>()
    val ownToolServers = LinkedHashMap<String, OwnToolServer>()
    val clearers = mutableListOf<(DataClearing) -> Boolean>()
    val clocks = mutableListOf<() -> Unit>()
    val runHooks = mutableListOf<(RunFacts) -> List<RuntimeHook>>()
    val sections = mutableListOf<(Context) -> Set<String>>()
    val turnLabels = mutableListOf<(String) -> String?>()
    val localTurns = mutableListOf<() -> Unit>()
    val routes = RouteMap()
    val front = mutableListOf<(Boolean) -> Unit>()
    val presenter = mutableListOf<(Boolean) -> Unit>()
    val services = HashMap<Class<*>, Any>()
    private val publishers = mutableListOf<PublishedState.() -> Unit>()
    private val stops = mutableListOf<() -> Unit>()
    private var published: PublishedState? = null

    override fun onStop(block: () -> Unit) { stops += block }

    override fun routes(module: RouteModule) = with(module) { routes.install() }

    override fun offer(feature: String) { offers += feature }

    override fun providerAdapter(adapter: ProviderAdapter) {
        adapters += adapter
        adapter.routes(app).forEach(::routes)
    }

    override fun tools(askBeforeActions: Boolean, provide: (context: Context, mounted: List<ToolProvider>) -> List<ToolProvider>) {
        tools += PluginTools(askBeforeActions, provide)
    }

    override fun wrapTools(wrap: (ToolProvider) -> ToolProvider) { wrappers += wrap }

    override fun skills(skills: List<PluginSkill>) { this.skills += skills }

    override fun rules(rules: List<String>) { this.rules += rules }

    override fun mcpServers(servers: List<PluginMcpServer>) { mcpServers += servers }

    override fun toolServers(servers: List<PluginToolServer>) { toolServers += servers }

    override fun runTools(provide: (RunFacts) -> List<ToolProvider>) { runTools += provide }

    override fun contextLines(lines: (RunFacts) -> List<String>) { contextLines += lines }

    override fun contextProviders(provide: (RunFacts) -> List<RuntimeContextProvider>) { contextProviders += provide }

    override fun ownToolServer(id: String, mainAgentOnly: Boolean, service: Boolean, worksDevice: Boolean, acts: ToolActs?) {
        ownToolServers[id] = OwnToolServer(mainAgentOnly, service, worksDevice, acts)
    }

    override fun describeTool(tool: ToolDescriptor): ToolDescriptor = AppHost.runs.describeTool(tool)

    override fun startOnBoot(clockChanged: () -> Unit) { clocks += clockChanged }

    override val approvalMode: ConfirmationMode get() = ApprovalSettings.mode

    override val voice: VoicePort get() = RuntimeVoice.port

    override fun deliver(delivery: Delivery) = conversation(app, delivery)

    override val outsideTurns: OutsideTurns = ServerOutsideTurns(app)

    override val turns: Turns = ServerTurns(app)

    override val speech: Speech = ServerSpeech(app)

    override val assistant: Assistant get() = ServerAssistant

    override suspend fun <P, R> request(method: RequestMethod<P, R>, params: P): R = AppServer.request(app, method, params)

    override fun runHooks(provide: (RunFacts) -> List<RuntimeHook>) { runHooks += provide }

    override fun skillSections(on: (Context) -> Set<String>) { sections += on }

    override fun turnLabel(label: (text: String) -> String?) { turnLabels += label }

    override fun onLocalTurn(block: () -> Unit) { localTurns += block }

    override fun onClearData(clear: (DataClearing) -> Boolean) { clearers += clear }

    override fun offers(feature: String): Boolean = AppHost.offers(feature)

    @AppServerPluginApi
    override fun prepareInput(prepare: (sessionId: String, text: String, previous: PreviousExchange?) -> String?) { inputs += prepare }

    override fun publish(block: PublishedState.() -> Unit) {
        publishers += block
        published?.let { ScopedState(it, scope).block() }
    }

    override fun onAppInFront(block: (inFront: Boolean) -> Unit) { front += block }

    override fun onPresenterReady(block: (ready: Boolean) -> Unit) { presenter += block }

    override fun <T : Any> provide(type: Class<T>, service: T) { services[type] = service }

    override fun <T : Any> service(type: Class<T>): T? = type.cast(lookup(type))

    fun startPublishing(state: PublishedState) {
        if (published != null) return
        published = state
        val scoped = ScopedState(state, scope)
        publishers.toList().forEach { scoped.it() }
    }

    fun stop() {
        scope.cancel()
        stops.asReversed().forEach { block -> runCatching(block).onFailure { android.util.Log.w("AppServer", "plugin $id did not stop cleanly", it) } }
    }
}

/** The published state as one plugin sees it: what it follows ends with the plugin. */
private class ScopedState(private val state: PublishedState, private val scope: CoroutineScope) : PublishedState {
    override val current: ServerState get() = state.current

    override fun update(change: ServerState.() -> ServerState) = state.update(change)

    override fun <T> follow(flow: Flow<T>, into: ServerState.(T) -> ServerState) {
        scope.launch { flow.collect { value -> state.update { into(value) } } }
    }

    override fun broadcast(notification: JsonRpcNotification) = state.broadcast(notification)
}

/** Another agent's turns, run through the server's runtime ([OutsideTurns]). */
private class ServerOutsideTurns(private val app: Context) : OutsideTurns {
    override fun follow(runId: String): ExecutionSubscription = ExecutionEvents.subscribe(runId)

    override suspend fun run(text: String, runId: String, sessionId: String) =
        RuntimeSession.submitExternal(app, text, RunIntake(runId, sessionId, external = true))

    override suspend fun input(sessionId: String, runId: String, messageId: String, text: String, questionId: String?): SteerResult =
        RuntimeSession.postExternalInput(sessionId, runId, messageId, text, questionId)

    override suspend fun cancel(sessionId: String, runId: String) = RuntimeSession.cancelExternal(sessionId, runId)
}

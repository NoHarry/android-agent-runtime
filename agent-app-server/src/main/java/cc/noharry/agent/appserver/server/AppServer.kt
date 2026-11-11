package cc.noharry.agent.appserver.server

import android.app.Application
import android.content.Context
import androidx.annotation.RawRes
import cc.noharry.agent.appserver.BootReceiver
import cc.noharry.agent.appserver.mcp.PluginMcpServers
import cc.noharry.agent.appserver.model.ProviderAdapter
import cc.noharry.agent.appserver.packages.JsSandbox
import cc.noharry.agent.appserver.packages.PackageInstaller
import cc.noharry.agent.appserver.packages.PackageSecrets
import cc.noharry.agent.appserver.packages.PackageStore
import cc.noharry.agent.appserver.packages.PackageTrust
import cc.noharry.agent.appserver.model.ModelPreset
import cc.noharry.agent.appserver.model.ScriptedModel
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi
import cc.noharry.agent.appserver.protocol.ServerInfo
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.protocol.transport.InProcessChannels
import cc.noharry.agent.appserver.protocol.transport.MessageChannel
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.BuiltInFeatures
import cc.noharry.agent.appserver.runtime.DEFAULT_SCENE
import cc.noharry.agent.appserver.runtime.HostModels
import cc.noharry.agent.appserver.runtime.Persona
import cc.noharry.agent.appserver.runtime.otherToolServerNames
import cc.noharry.agent.appserver.runtime.PreviousExchange
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.appserver.runtime.ProviderPresets
import cc.noharry.agent.appserver.runtime.ReplayCheck
import cc.noharry.agent.appserver.runtime.RunAddOns
import cc.noharry.agent.appserver.runtime.RuntimeSession
import cc.noharry.agent.appserver.runtime.VoicePort
import cc.noharry.agent.appserver.session.ApprovalSettings
import cc.noharry.agent.appserver.skills.RuntimeSkill
import cc.noharry.agent.appserver.skills.forgetRuntimeSkillChoice
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.model.ModelClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcRequest
import cc.noharry.agent.appserver.protocol.jsonrpc.ProtocolJson
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod

/** What the server publishes, as a plugin adds to it: each change goes out at once. Main thread. */
interface PublishedState {
    /** What features published so far (their sections). */
    val current: ServerState

    fun update(change: ServerState.() -> ServerState)

    /** Every value of [flow] into what is published. */
    fun <T> follow(flow: Flow<T>, into: ServerState.(T) -> ServerState)

    /** A notification beside the state, to every client. */
    fun broadcast(notification: JsonRpcNotification)
}

/**
 * Everything a host app says about its server, as values. A simple host needs only the first fields: its [name] (the
 * server's name to clients, and of its debug sockets), the [persona] the assistant is, its own [tools] (tool providers
 * built for each run), [rules] (lines for the system prompt, in the persona's language), [skills] it ships (SKILL.md in
 * its assets), [hooks] (gates on every tool call), the [model] service its build carries (the profile a new
 * installation answers with; the person's later choice wins) and the [approvals] mode a new installation starts in
 * ([ConfirmationMode.ASK_EVERY_TIME] by default; a host with no screen to answer on wants
 * [ConfirmationMode.ALLOW_ALL_SESSION]): a mode a client sets (`run/set`) is the person's choice, kept across restarts
 * (in the preferences file `app_server_approvals`) until [AppServer.resetApprovals].
 *
 * Which calls ask: one of a tool that does not say it only reads (its `effectKind` is not `READ_ONLY`, `UNKNOWN` by
 * default; an MCP server's tool without `readOnlyHint`), whoever brought it, and `write_file`, `edit_file` and
 * `exec_js`; the approval mode answers. [unaskedTools] names those of them that need no asking although they act, by
 * full name (`mcp__<providerId>__<tool>`, or `write_file`, `edit_file`, `exec_js`): their `effectKind` stays what it is.
 * A plugin package's tool that acts asks every time and cannot be named here. A hook of [hooks] that answers `Deny`
 * still refuses a call, and one that answers `RequireConfirmation` makes any call ask.
 *
 * For tests and demos, [simulatedModel] makes a new model for each run of the simulated backend (a [ScriptedModel];
 * null for the server's own, which only says what it heard), and [simulatedBackend] starts the server on that backend
 * rather than on the selected profile (a client switches with `run/set`; picking a profile switches to it).
 *
 * The server's own features beyond what its protocol needs are off until [builtIns] turns them on, and plugins add more
 * ([plugins]: modules of their own, such as cards, a model service's extras, operating the screen, the device's tools,
 * plans or A2A; which are on is kept on the device). [debugSockets], for the host's developers and off by default: the server also
 * listens on two abstract local sockets of this device, `<name>` (the protocol, one JSON message per line) and
 * `<name>-acp` (the Agent Client Protocol, for an editor), which only this app, adb's shell and root may connect to; a
 * host turns it on in its debug builds only (the server does not check that the build is debuggable), since the shell
 * then has every request a client has, installing a plugin package included. The server opens no other socket of its own; a plugin may (the A2A
 * plugin's local endpoint, while it runs and its host asks for it). [diagnostics], off by default: the server keeps files on what each run did,
 * which only grow until runtime data is cleared: each model call's request and reply (`files/runtime-debug-logs/`), each
 * run's events and replay check (`files/debug-run-evidence/`), the simulated backend's tool search
 * (`files/mcp-progressive-evidence/`), the probes of the bench prompt (`files/agent-reliability/`) and hosted search
 * checks (`files/web-search-evidence/`). What the server needs to work (conversations and their event logs, the
 * workspace) is kept either way.
 *
 * A host that goes deeper (experimental): the model services its build carries as a profiles file ([presets]), a
 * voice ([voice]), models it runs itself ([models]), what depends on
 * each run ([runs]), checks of finished runs for a bench ([replayCheck]), [extraTrustRoots] (a raw resource of PEM certificates trusted beside the system's for every HTTPS call the
 * server makes), the [scene] its turns run in (the runtime files the lessons it learns, the skills that name scenes and
 * its workspace context under it), and which plugin packages a person may install: those of [trustedPublishers]
 * (public keys, hex of their DER encoding) with one confirmation, any other signed one after a second warning, and
 * unsigned ones only with [allowUnsignedPackages] (a developer's own device). [maxScriptToolSeconds] is the most one
 * call of a package's tool written as a script may run, host calls included: a tool asks for its own time (10 s unless
 * it says, 120 at most) and runs with the lesser of the two (30 by default; at least 1).
 */
@ExperimentalAppServerApi
class AppServerConfig(
    val name: String,
    val persona: Persona = Persona.GENERIC,
    val tools: (Context) -> List<ToolProvider> = { emptyList() },
    val rules: List<String> = emptyList(),
    val skills: (Context) -> List<RuntimeSkill> = { emptyList() },
    val hooks: List<RuntimeHook> = emptyList(),
    val model: ModelPreset? = null,
    val approvals: ConfirmationMode = ConfirmationMode.ASK_EVERY_TIME,
    val unaskedTools: Set<String> = emptySet(),
    val simulatedModel: (() -> ModelClient)? = null,
    val simulatedBackend: Boolean = false,
    val builtIns: BuiltInFeatures = BuiltInFeatures.NONE,
    val plugins: List<AppServerPlugin> = emptyList(),
    val debugSockets: Boolean = false,
    val diagnostics: Boolean = false,
    val presets: ProviderPresets = ProviderPresets.NONE,
    val voice: VoicePort = VoicePort.NONE,
    val models: HostModels = HostModels.NONE,
    val runs: RunAddOns = RunAddOns.NONE,
    val replayCheck: ReplayCheck = ReplayCheck.NONE,
    @RawRes val extraTrustRoots: Int? = null,
    val scene: String = DEFAULT_SCENE,
    val trustedPublishers: Set<String> = emptySet(),
    val allowUnsignedPackages: Boolean = false,
    val maxScriptToolSeconds: Int = 30,
) {
    init {
        require(maxScriptToolSeconds >= 1) { "maxScriptToolSeconds must be at least 1." }
    }
}

/**
 * The app server, as Codex's app-server is to its TUI and desktop app: one runtime ([RuntimeSession]) behind the
 * protocol, which every front end connects to as a client. A host app [install]s it with its [AppServerConfig] when
 * its process starts; [connect] gives a client its end of a new in-process connection, and the server starts with the
 * first one.
 */
@ExperimentalAppServerApi
object AppServer {
    @Volatile private var config = AppServerConfig(name = "app")

    @Volatile private var hub: ServerHub? = null

    /** The host's plugins: which are on, which run. */
    @Volatile
    internal var pluginHost: PluginHost? = null
        private set

    /** Installing and removing the packages a person downloads. */
    @Volatile
    internal var packages: PackageInstaller? = null
        private set

    fun install(app: Application, config: AppServerConfig) {
        check(!config.builtIns.scripts || JsSandbox.available) {
            "builtIns.scripts: ${JsSandbox.MISSING}; add cc.noharry:agent-exec-js to the app's dependencies"
        }
        this.config = config
        installHost(config)
        ApprovalSettings.init(app, config.approvals)
        RuntimeSession.startWith(simulated = config.simulatedBackend)
        val plugins = PluginHost(app, config.plugins)
        plugins.onChange = { additions ->
            AppHost.install(additions = additions)
            // Started after a reboot only while a running plugin asks (plans, a host's own).
            BootReceiver.follow(app, additions.startsOnBoot)
            hub?.featuresChanged()
            // The skills listed with their switches include the plugins'.
            PreferencesSource.changed()
            PluginMcpServers.retain(additions.mcpServers)
            // A plugin's tool servers are rows of the MCP list: it is read again once the server runs.
            if (hub != null) RuntimeSession.reloadMcpProviders(app)
        }
        pluginHost = plugins
        val trust = PackageTrust(config.trustedPublishers, config.allowUnsignedPackages)
        val installer = PackageInstaller(
            plugins, PackageStore(app), PackageSecrets(app), trust,
            forgetSkills = { names -> names.forEach { forgetRuntimeSkillChoice(app, it) } },
            maxScriptSeconds = config.maxScriptToolSeconds,
            otherToolServers = { otherToolServerNames(app, plugins) },
        )
        packages = installer
        installServer(app, plugins, installer)
    }

    /**
     * Forgets the approval mode the person chose: the server answers in [AppServerConfig.approvals] again, now (a turn
     * running included) and after restarts, until the person chooses another. On the main thread, after [install].
     */
    fun resetApprovals() {
        ApprovalSettings.forget()
        RuntimeSession.selectConfirmationMode(ApprovalSettings.mode, chosen = false)
    }

    fun connect(context: Context): MessageChannel {
        val (client, server) = InProcessChannels.pair()
        hub(context).connect(server)
        return client
    }

    /** Starts the server without a front end (a wake word listens, plans run); nothing when it runs already. */
    fun wakeUp(context: Context) {
        hub(context).ensureStarted()
    }

    /**
     * [method] with [params] as a client's request, answered on the main thread by the routes of the server (started
     * when it is not) and its running plugins ([PluginContext.request]).
     */
    internal suspend fun <P, R> request(context: Context, method: RequestMethod<P, R>, params: P): R =
        withContext(Dispatchers.Main.immediate) {
            val request = JsonRpcRequest(JsonPrimitive(ownRequests.incrementAndGet()), method.name, ProtocolJson.encodeToJsonElement(method.params, params))
            ProtocolJson.decodeFromJsonElement(method.result, hub(context).routes.handle(request))
        }

    private val ownRequests = java.util.concurrent.atomic.AtomicLong()

    private fun hub(context: Context): ServerHub = hub ?: synchronized(this) {
        hub ?: start(context.applicationContext).also { hub = it }
    }

    private fun start(app: Context): ServerHub {
        val runtime = RuntimeBackend(app)
        lateinit var created: ServerHub
        created = ServerHub(
            backend = runtime,
            settings = runtime,
            main = Dispatchers.Main.immediate,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            serverInfo = ServerInfo(config.name, app.versionName()),
            features = { config.builtIns.offered() + AppHost.additions.offers },
            modules = coreModules(app),
            // The state's sources start with the first client that takes state, not with the app's presentation.
            startSources = { StateSources(app, created, pluginHost).start() },
        )
        RuntimeSession.threads.followed = created::isFollowed
        Presenter.link = created.presentation.apply {
            changed = { ready -> pluginHost?.presenterReady(ready) }
        }
        // From outside the app (a PC over adb) only when the host asks; the build being debuggable is not checked.
        if (config.debugSockets) {
            LocalSocketServer(created, config.name).start()
            AcpSocketServer(app, "${config.name}-acp").start()
        }
        return created
    }

    private fun Context.versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
}

/**
 * The modules of requests every app server answers beside the core routes ([ServerRoutes]), and the requests of the
 * plugin switches; a plugin's own requests come and go with it ([PluginHost]).
 */
@cc.noharry.agent.appserver.AppServerPluginApi
fun coreModules(app: Context): List<RouteModule> = listOf(
    LessonRoutes(app),
    McpRoutes(app),
    UsageRoutes(app),
    ModelSettingsRoutes(app),
    AppRoutes(app),
    FileRoutes(app),
    PluginRoutes(app, { AppServer.pluginHost }, { AppServer.packages }),
)

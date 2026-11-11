package cc.noharry.agent.appserver.server

import android.app.Application
import android.content.Context
import cc.noharry.agent.appserver.AppServerPluginApi
import cc.noharry.agent.appserver.model.ProviderAdapter
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.appserver.runtime.PreviousExchange
import cc.noharry.agent.appserver.runtime.RunFacts
import cc.noharry.agent.appserver.runtime.ExecutionSubscription
import cc.noharry.agent.appserver.runtime.VoicePort
import cc.noharry.agent.SteerResult
import cc.noharry.agent.spi.RuntimeContextProvider
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import kotlinx.coroutines.CoroutineScope

/**
 * A plugin of the app server: anything a host adds to it (a voice, an on-device model, a model service's extras, cards,
 * checks of its own). It says what it adds in [apply], through the [PluginContext] it is given; everything said there
 * is taken back when the plugin stops, so a plugin can be turned off and on while the server runs. A host lists the
 * plugins its build carries ([AppServerConfig.plugins]); which of them are on is kept on the device.
 */
@ExperimentalAppServerApi
interface AppServerPlugin {
    /** Its name among the host's plugins: short, stable across versions, unique in the list. */
    val id: String

    /** The plugins that must run before this one does; it stops when one of them does. */
    val needs: Set<String> get() = emptySet()

    /** Whether the host cannot do without it: it is always on. */
    val required: Boolean get() = false

    /** Starts it: adds what it has to [ctx]. Called on the main thread, when the process starts or it is turned on. */
    fun apply(ctx: PluginContext)

    /**
     * While it is off: deletes what it keeps on the device for conversations ([DataClearing]), as
     * [PluginContext.onClearData] does while it runs, so a conversation deleted or the runtime data cleared meanwhile
     * leaves none of it behind. Returns false when something of it is left. Nothing to delete by default.
     */
    fun clearWhileOff(app: Application, clearing: DataClearing): Boolean = true
}

/**
 * What a running plugin adds to the server, and what it gets from it. Each addition lasts until the plugin stops.
 */
interface PluginContext {
    val app: Application

    /** For what the plugin runs beside its additions (main thread); cancelled when it stops. */
    val scope: CoroutineScope

    /** Runs when the plugin stops, last registered first: what it must give back that the server does not know of. */
    fun onStop(block: () -> Unit)

    /** Requests of its own. */
    fun routes(module: RouteModule)

    /** A server feature it brings, named to clients ([cc.noharry.agent.appserver.protocol.ServerFeatures]). */
    @AppServerPluginApi
    fun offer(feature: String)

    /** What it knows of a model service beyond chat, with that adapter's own requests. */
    @AppServerPluginApi
    fun providerAdapter(adapter: ProviderAdapter)

    /**
     * Its tools for each run; `mounted` are the backend tool providers the run has, for tools that call them. With
     * [askBeforeActions] (tools from outside the host, such as a package's) each provider is mounted under a name no
     * other tool server of the run has (its id, with a digit when taken), and a call of one of its tools that the
     * provider does not describe as read-only (`effectKind` READ_ONLY) waits for the person's yes, as with
     * [PluginMcpServer.askBeforeActions]: every time, in every approval mode but "deny all", which refuses it (a yes is
     * never remembered, and a subagent's such call is refused). Without it the server's own rule applies, as to the
     * host's tools: a tool not described as read-only asks first, and the approval mode answers.
     */
    fun tools(askBeforeActions: Boolean = false, provide: (context: Context, mounted: List<ToolProvider>) -> List<ToolProvider>)

    /** A wrapper around each backend tool provider of a run, each plugin's over the last. */
    @AppServerPluginApi
    fun wrapTools(wrap: (ToolProvider) -> ToolProvider)

    /** Skills of its own, in the skill catalog the model reads and switched like the host's ([PluginSkill]). */
    fun skills(skills: List<PluginSkill>)

    /**
     * Lines for the system rules, after the host's own ([AppServerConfig.rules], then
     * [cc.noharry.agent.appserver.runtime.RunAddOns.systemRules]) and behind a line saying plugin text cannot widen what
     * the person allowed; 3 KiB for all plugins' rules, a block over it left out.
     */
    fun rules(rules: List<String>)

    /** MCP servers each run reaches while it runs ([PluginMcpServer]). */
    fun mcpServers(servers: List<PluginMcpServer>)

    /**
     * Tool servers of its own, listed with the MCP servers and each switched there by the person ([PluginToolServer]);
     * every run mounts those switched on as it mounts a configured server.
     */
    fun toolServers(servers: List<PluginToolServer>)

    /**
     * Tools for each run that depend on what the run is ([RunFacts]: whether its model reads pictures, whether it is a
     * subagent's), none when they cannot work now. They are mounted after the run's other tools and wrapped by nothing
     * (operating the screen gives its tools so). A skill that needs one of them
     * ([cc.noharry.agent.appserver.skills.RuntimeSkill.requiresToolServer]) is read only in a run given a provider of
     * that id this way.
     */
    @AppServerPluginApi
    fun runTools(provide: (RunFacts) -> List<ToolProvider>)

    /**
     * Lines for each run's context, which changes every run (never the cached system prompt), right after the host's own
     * ([cc.noharry.agent.appserver.runtime.RunAddOns.contextLines]); none when it has nothing to say to this run.
     */
    @AppServerPluginApi
    fun contextLines(lines: (RunFacts) -> List<String>)

    /**
     * What the model reads before each round of a run, from providers the runtime asks every round (what is remembered
     * about the person, say), after the server's own and within the time they share; none when there is nothing to
     * give this run. The runtime marks what they give as data, never instructions.
     */
    @AppServerPluginApi
    fun contextProviders(provide: (RunFacts) -> List<RuntimeContextProvider>)

    /**
     * Names [id] as a tool server of its own, the id a provider it gives runs ([tools], [runTools]) goes by: while it
     * runs no MCP server of a plugin or a package is mounted under that name. An id ending in `*` names every tool
     * server whose id starts with what comes before it (one per remote agent, say); a tool server it lists
     * ([toolServers]) may be named too. [acts], when given, says whether a call of one of its tools ([tool], with its
     * [arguments] as JSON) acts, and so asks first as the approval mode says, in place of the tool's `effectKind` (a drawn
     * page only shows something: it never asks); null leaves that call to the `effectKind` (a tool that reads or sets
     * the volume says by its arguments which a call does, and the rest keep what they say). [mainAgentOnly]: its tools
     * are the main agent's (making plans, handing a task to another agent), so a subagent's call of one is refused and
     * a call of one is not counted as using a service ([cc.noharry.agent.appserver.tools.isServiceTool]). [service]
     * false: its calls are not counted as using a service either, though a subagent may make them (operating the screen).
     * [worksDevice]: its tools work things around the person, so a subagent only queries them unless the subagent
     * settings let it operate devices (operating the screen; a tool server listed with the MCP servers says so in
     * [PluginToolServer.worksDevice]).
     */
    fun ownToolServer(
        id: String,
        mainAgentOnly: Boolean = false,
        service: Boolean = true,
        worksDevice: Boolean = false,
        acts: ((tool: String, arguments: String) -> Boolean?)? = null,
    )

    /**
     * The host's words on [tool] as the model reads it ([cc.noharry.agent.appserver.runtime.RunAddOns.describeTool]),
     * which a plugin applies to its own tools as the server applies it to a configured MCP server's.
     */
    fun describeTool(tool: ToolDescriptor): ToolDescriptor

    /**
     * Asks that the app's process start after a reboot or an update while the plugin runs, so what it keeps going (an
     * alarm, a watch) resumes even if nobody opens the app ([cc.noharry.agent.appserver.BootReceiver], which is off
     * while no running plugin asks). [clockChanged] runs, on the main thread, when the clock or the time zone is set.
     */
    fun startOnBoot(clockChanged: () -> Unit = {})

    /** The approval mode the person's turns answer in now (`run/set`), for what the plugin does on its own later. */
    val approvalMode: ConfirmationMode

    /** The host's voice ([VoicePort]): a plugin may chime ([VoicePort.chime]), as a plan coming due does. */
    val voice: VoicePort

    /**
     * Hands the conversation on screen something that did not come from the person ([Delivery]): a turn the agent runs,
     * or words said without the model. Any thread.
     */
    fun deliver(delivery: Delivery)

    /** Turns for a client outside the app, such as another agent's task ([OutsideTurns]). */
    @AppServerPluginApi
    val outsideTurns: OutsideTurns

    /** The conversation on screen: starting and stopping its turns, and whether one runs ([Turns]). */
    @AppServerPluginApi
    val turns: Turns

    /** Speech on this device: the words the person said, and what the assistant says ([Speech]). */
    @AppServerPluginApi
    val speech: Speech

    /** The assistant on screen: the client presenting it on this device, the notices it shows ([Assistant]). */
    @AppServerPluginApi
    val assistant: Assistant

    /**
     * A request as a client sends it ([method] with [params]), answered by the server's own routes and the running
     * plugins' on the main thread: a setting the protocol offers (the model profile, an MCP server), a new
     * conversation, clearing the runtime data. Starts the server when it has not started. Fails as the client's request
     * would ([cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException]).
     */
    @AppServerPluginApi
    suspend fun <P, R> request(method: RequestMethod<P, R>, params: P): R

    /**
     * Gates on each run's tool calls that depend on what the run is ([RunFacts]), before the host's own
     * ([AppServerConfig.hooks]); none when it has nothing to check in this run.
     */
    @AppServerPluginApi
    fun runHooks(provide: (RunFacts) -> List<RuntimeHook>)

    /**
     * Keys of skills' conditional sections that hold now, read for every run: a SKILL.md keeps its `<!-- key -->` …
     * `<!-- /key -->` lines and drops its `<!-- no-key -->` lines, and a skill that requires the key
     * ([cc.noharry.agent.appserver.skills.RuntimeSkill.requiresSection]) is read.
     */
    @AppServerPluginApi
    fun skillSections(on: (Context) -> Set<String>)

    /**
     * Who opened a turn whose text it recognizes (a report it delivered): the label of the turn's header, or null when
     * it is not one of its own; a turn nobody claims is the person's.
     */
    @AppServerPluginApi
    fun turnLabel(label: (text: String) -> String?)

    /** A turn started on the device itself (the person's, or the app's own), not one of [outsideTurns]. Main thread. */
    @AppServerPluginApi
    fun onLocalTurn(block: () -> Unit)

    /**
     * Deletes what it keeps on the device for conversations ([DataClearing]): one conversation's when the person deletes
     * it, every conversation's when the app's runtime data is cleared. Returns false when something of it is left.
     * Called once no run can write any more. Only while it runs: what it wrote before it was turned off stays until a
     * clearing while it runs.
     */
    @AppServerPluginApi
    fun onClearData(clear: (DataClearing) -> Boolean)

    /** Whether the server or a running plugin offers [feature] now ([offer]). */
    @AppServerPluginApi
    fun offers(feature: String): Boolean

    /**
     * Before each turn: the input the run gets in place of what the person sent (a pick resolved to what it stands
     * for), or null to leave it. Called for every turn, so this is also where what its last reply showed ends.
     */
    @AppServerPluginApi
    fun prepareInput(prepare: (sessionId: String, text: String, previous: PreviousExchange?) -> String?)

    /**
     * What it publishes beside the runtime's state: [block] runs once the server publishes state (at once when it
     * already does). What it follows there stops with the plugin; what it wrote stays until [onStop] clears it.
     */
    fun publish(block: PublishedState.() -> Unit)

    /** The app came to the front or left it. */
    @AppServerPluginApi
    fun onAppInFront(block: (inFront: Boolean) -> Unit)

    /** The assistant on screen came up or went. */
    @AppServerPluginApi
    fun onPresenterReady(block: (ready: Boolean) -> Unit)

    /**
     * Offers [service] under its type to the plugins that need this one, and to the server for the types it asks for
     * ([ForegroundApps]).
     */
    @AppServerPluginApi
    fun <T : Any> provide(type: Class<T>, service: T)

    /** The service of [type] a running plugin offers, or null. */
    @AppServerPluginApi
    fun <T : Any> service(type: Class<T>): T?
}

/**
 * What a plugin hands the conversation on screen ([PluginContext.deliver]): a turn the agent runs reading [forModel], or
 * with [said], words said as written without the model (a reminder). [shown] is what the transcript and the card show,
 * [title] its name on the card and in the status line. It waits its turn with the server's own reports (a subagent's,
 * say), until no run is going and the person has read the last result, a minute at most; a device still busy then gets
 * [forModel] into the running turn, which becomes a turn of its own (or [said], spoken) if that turn ends first.
 * [showCard] brings the assistant's card up first; [delivered] runs once it reached the conversation. After a turn the
 * agent ran, listening opens again once its answer was spoken, so the person may answer it.
 */
class Delivery(
    val shown: String,
    val title: String,
    val forModel: String,
    val said: String? = null,
    val showCard: Boolean = false,
    val delivered: () -> Unit = {},
)

/**
 * Turns the server runs for a client outside the app ([PluginContext.outsideTurns]; another agent's task, over A2A), each
 * in a conversation of its own that the device's screens do not select. Nobody on the device is asked in them: the
 * person's approval mode answers a call that acts ("allow all" runs it, "deny all" refuses it, and otherwise it asks,
 * which a client outside cannot answer).
 */
@cc.noharry.agent.appserver.AppServerPluginApi
interface OutsideTurns {
    /** What run [runId] does from now on: follow it before [run], so nothing is missed, and close it when done. */
    fun follow(runId: String): ExecutionSubscription

    /** Starts [text] as run [runId] in the conversation [sessionId]; returns once the server took it. */
    suspend fun run(text: String, runId: String, sessionId: String)

    /** More words for run [runId], or the answer to its question [questionId]. */
    suspend fun input(sessionId: String, runId: String, messageId: String, text: String, questionId: String?): SteerResult

    /** Stops run [runId]; returns once it stopped. */
    suspend fun cancel(sessionId: String, runId: String)
}

/**
 * What is being deleted ([PluginContext.onClearData]): the conversation [sessionId] when the person deletes it, or every
 * conversation (null) when the app's runtime data is cleared, and then also what the plugins named in [also] keep
 * beyond conversations (the memory plugin's book; `data/clear {also: ["memory"]}`).
 */
class DataClearing(val sessionId: String?, val also: Set<String> = emptySet()) {
    /** Whether plugin [id]'s own data beyond conversations goes too: every conversation is cleared, and it is named. */
    fun alsoClears(id: String): Boolean = sessionId == null && id in also
}

/**
 * A skill a plugin brings: [name] as the skill catalog lists it and the model reads it
 * (`/.agents/skills/<name>/SKILL.md`, so one path segment), [description] what the catalog says of it, [text] the
 * whole SKILL.md. The model reads it when it needs it, or with every run when [preload].
 */
class PluginSkill(val name: String, val description: String, val text: String, val preload: Boolean = false)

/**
 * An MCP server a plugin brings, reached over Streamable HTTP at [url]: its tools are `mcp__<name>__<tool>` in each
 * run while the plugin runs ([name] is letters, digits and `_`; one another server has taken gets a digit). [headers]
 * and [query] (parameters added to the address) are read for every request, so a key they hold never sits in [url].
 * With [askBeforeActions], each call of one of its tools that the server does not mark read-only (MCP `readOnlyHint`)
 * asks the person first, every time, in every approval mode but "deny all", which refuses it. Without it, its tools are
 * held to the server's own rule, as a configured server's are: one not marked read-only asks, and the mode answers.
 * [readOnlyHints] false does not take the server's word: every tool of it counts as one that acts (a server named by a
 * package whose publisher the host does not trust).
 */
class PluginMcpServer(
    val name: String,
    val label: String,
    val url: String,
    val headers: () -> Map<String, String> = { emptyMap() },
    val query: () -> Map<String, String> = { emptyMap() },
    val askBeforeActions: Boolean = false,
    val readOnlyHints: Boolean = true,
)

/**
 * A tool server a plugin brings (the device's calendar, say), listed with the MCP servers under [id] (its tools are
 * `mcp__<id>__<tool>`; letters, digits and `_`) and switched there by the person, on at first when [onAtFirst]. While
 * it is on, each run mounts what [provider] builds, as it mounts a configured server: [provider] gets the run's wrapper
 * for backend tools (the host's [cc.noharry.agent.appserver.runtime.RunAddOns.toolWrapper]) to apply where it belongs,
 * and the identity when the server only lists its tools for the settings page. [label] is its name in the list,
 * [title] the one the screens show, in the app's language. [worksDevice]: its tools work things around the person, so
 * a subagent only queries them unless it may operate devices. [notificationAccess]: whether the app has the
 * notification access this server sees more with, which the screens say beside it; null when it needs none.
 */
class PluginToolServer(
    val id: String,
    val label: String,
    val title: (Context) -> String,
    val onAtFirst: Boolean = false,
    val worksDevice: Boolean = false,
    val notificationAccess: ((Context) -> Boolean)? = null,
    val provider: (context: Context, wrap: (ToolProvider) -> ToolProvider) -> ToolProvider,
)

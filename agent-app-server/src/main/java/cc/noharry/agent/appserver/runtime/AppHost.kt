@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime

import android.content.Context
import cc.noharry.agent.appserver.evidence.AppDiagnostics
import cc.noharry.agent.appserver.model.FixtureModelClient
import cc.noharry.agent.appserver.model.ModelPreset
import cc.noharry.agent.appserver.model.ProviderAdapter
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.ModelFacts
import cc.noharry.agent.appserver.protocol.ModelPrice
import cc.noharry.agent.appserver.protocol.ServerFeatures
import cc.noharry.agent.appserver.skills.RuntimeSkill
import cc.noharry.agent.appserver.skills.SubagentKind
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.spi.model.ModelClient

/**
 * Models the host app runs itself (an on-device model), beside the providers': their profiles, whether one can run,
 * its client and its model list. An app without any keeps [NONE].
 */
interface HostModels {
    /** [profiles] with the host's own added. */
    fun withProfiles(context: Context, profiles: List<ProviderProfile>): List<ProviderProfile> = profiles

    /** Whether [profileId] names one of the host's own profiles. */
    fun owns(profileId: String): Boolean = false

    fun isReady(profile: ProviderProfile): Boolean = false

    fun client(profile: ProviderProfile, stream: Boolean, diagnostics: AppDiagnostics?): ModelClient =
        error("${profile.id} is not one of the host's models")

    /** What [profile] lacks before it can run, for 检查 and for the transcript. */
    fun missing(profile: ProviderProfile): Finding = error("${profile.id} is not one of the host's models")

    fun missingMessage(context: Context, profile: ProviderProfile): String = error("${profile.id} is not one of the host's models")

    /** The models [profileId] offers. */
    fun models(profileId: String): List<String> = emptyList()

    /** The context window [model] runs with when it is one of the host's (an on-device server's -c), else null. */
    fun contextTokens(model: String): Long? = null

    /** How long its first event may take (a local model may still be loading); null for the usual watchdog. */
    fun firstEventTimeoutMs(profile: ProviderProfile): Long? = null

    /** [profile] became the selected one. */
    fun selected(profile: ProviderProfile) = Unit

    /** Values the host saved itself, read with the providers' and winning over local.properties (its TTS settings). */
    fun savedValues(context: Context): Map<String, String> = emptyMap()

    companion object {
        val NONE: HostModels = object : HostModels {}
    }
}

/**
 * What the host adds to each run beyond [cc.noharry.agent.appserver.server.AppServerConfig]'s plain fields (its tools,
 * rules, skills and hooks): what depends on the run (lines for its context, rules and gates that read [RunFacts]), the
 * runtime's loop settings and protocol rules, a wrapper for every mounted tool provider (a tool search picking tools
 * before the first model call), how a remote tool reads, a field on tool calls for words to the person, whether the
 * bundled tools.json catalog is mounted, subagent types of its own and the tool servers a subagent may only query. An
 * app without any keeps [NONE].
 */
interface RunAddOns {
    /** Host-specific language and memory policy, applied after the app server's execution settings. */
    @ExperimentalAgentRuntimeApi
    fun loopSettings(defaults: cc.noharry.agent.runtime.LoopSettings): cc.noharry.agent.runtime.LoopSettings = defaults

    /** Host search-site preferences appended to the runtime's neutral protocol. */
    fun protocolRules(): cc.noharry.agent.runtime.ProtocolRules = cc.noharry.agent.runtime.ProtocolRules.DEFAULT

    /** Lines for this run's context, which changes every run (never the cached system prompt). */
    fun contextLines(facts: RunFacts): List<String> = emptyList()

    /**
     * Rules for the system prompt beside the server's own, in the persona's language. The prompt is cached across runs,
     * so they may depend only on what stays the same from run to run ([RunFacts.webSearch], [RunFacts.subagentTypes]).
     */
    fun systemRules(facts: RunFacts): List<String> = emptyList()

    /** Gates on this run's tool calls (a yes before an order, say), after the server's own. */
    @ExperimentalAgentRuntimeApi
    fun runHooks(facts: RunFacts): List<RuntimeHook> = emptyList()

    /**
     * A tool as the model reads it, each time its list is read: a configured MCP server's, and a plugin's that asks for
     * the host's words ([cc.noharry.agent.appserver.server.PluginContext.describeTool], the plan tool); a host adds a note
     * on what one does or when to offer it.
     */
    fun describeTool(tool: ToolDescriptor): ToolDescriptor = tool

    /**
     * A field the model may fill on a tool call with words for the user as the work goes, which the host's voice says;
     * null for none. The model reads it on every tool [ToolNote.applies] to; no tool receives it ([ToolNotes] has it).
     */
    fun toolNote(): ToolNote? = null

    /** How each mounted tool provider is wrapped in a run with [sessionContext]; [simulated] for the simulated backend. */
    fun toolWrapper(context: Context, sessionContext: String, simulated: Boolean): (ToolProvider) -> ToolProvider = { it }

    /**
     * Whether this run mounts the tools.json catalog the host bundles (`assets/mcp/tools.json`, the `tools_json` row of
     * the MCP list) while the person has that row switched on; true by default.
     */
    fun mountsBundledTools(): Boolean = true

    /** The subagent types the host ships (AGENT.md in its assets), offered by spawn_agent. */
    fun subagentKinds(): List<SubagentKind> = emptyList()

    /**
     * The tool servers a subagent may only query unless the subagent settings let it operate devices: the host's own
     * that work things around the person (a car, a home; its bundled tools.json catalog, if it does), besides the
     * screen and media every app has. Each is the `<id>` of its tools' `mcp__<id>__<tool>`; a tool whose name has a
     * query word (get, status, search, …) and no action word (set, play, open, …) is a query. Read each time a subagent
     * starts.
     */
    fun queryOnlyInSubagents(context: Context): Set<String> = emptySet()

    companion object {
        val NONE: RunAddOns = object : RunAddOns {}
    }
}

/**
 * What the server offers beyond the conversation and the tools its protocol needs (search_tools and call_tool, finish,
 * ask and ask_async, update_todo, and reading the workspace), each off until the host turns it on: [fileWriting]
 * (write_file and edit_file), [web] (web_search, web_fetch and a model service's own search), [scripts] (exec_js, still
 * switched off at first on the basic tools page) and [subagents] (spawn_agent, wait_agent and cancel_agent, and the
 * subagent settings). One left off is not offered to the model, has no row on the basic tools page and no requests or
 * state of its own, so a client cannot turn it on. Plans, operating the screen, the device's calendar, notifications and
 * media, cards, drawn pages, pictures and what the model remembers about the person are plugins in modules of their
 * own, which a host lists ([cc.noharry.agent.appserver.server.AppServerConfig.plugins]); a plugin asks for the app to
 * start after a reboot ([cc.noharry.agent.appserver.server.PluginContext.startOnBoot]).
 */
class BuiltInFeatures(
    val fileWriting: Boolean = false,
    val web: Boolean = false,
    val scripts: Boolean = false,
    val subagents: Boolean = false,
) {
    /** Those a client can see, as the handshake names them ([ServerFeatures]). */
    internal fun offered(): Set<String> = buildSet {
        if (fileWriting) add(ServerFeatures.FILE_WRITING)
        if (web) add(ServerFeatures.WEB)
        if (scripts) add(ServerFeatures.SCRIPTS)
        if (subagents) add(ServerFeatures.SUBAGENTS)
    }

    companion object {
        val NONE = BuiltInFeatures()
    }
}

/** What a run is, for the host's lines, rules and gates ([RunAddOns]) and a plugin's ([cc.noharry.agent.appserver.server.PluginContext]). */
class RunFacts(
    val context: Context,
    /** The request as the model reads it this run: a short reply framed with what it answers, a pick's frame. */
    val userInput: String,
    /** A subagent's run: its input is the task the main agent wrote, never the user's words. */
    val child: Boolean,
    /** The main agent searches the web itself (a web_search tool or the provider's own search); false in a subagent's run. */
    val webSearch: Boolean,
    /** The subagent types the main agent can start now ([SubagentKind.id]); none in a child or with spawn_agent off. */
    val subagentTypes: Set<String>,
    /** The latest answerable assistant question, supplied as host metadata rather than quoted user input. */
    val previousQuestion: String? = null,
    /** The run's model reads pictures (a screenshot, say). */
    val imageInput: Boolean = false,
    /**
     * Whether the app's own screens were in front when the run started, as the app last said (`app/inFront`): then the
     * person looks at a front end's conversation. Null when the app has never said (a host without screens).
     */
    val appInFront: Boolean? = null,
    /** The run's model is one the host runs itself ([HostModels.owns]), such as an on-device one. */
    val hostModel: Boolean = false,
    /** An editor's run over the Agent Client Protocol socket, in a conversation of its own rather than the app's. */
    val editor: Boolean = false,
)

/**
 * A host's own checks of finished runs, for a bench that replays requests: whether a request needed a tool that acts,
 * and what is wrong with an answer. Each run's replay check (REPLAY_FAILED in its evidence) applies them; a host
 * without a bench keeps [NONE].
 */
interface ReplayCheck {
    /**
     * Whether [request] asks for something a service must do (navigate, play, set): a run that ends without any such tool
     * call fails its replay check. None does by default.
     */
    fun requiresAction(request: String): Boolean = false

    /**
     * What the host finds wrong with a run's [answer] that passed otherwise; failures fail its replay. [request] is the run's
     * input as its evidence keeps it: what the model read (a short reply framed with what it answers), spaces collapsed,
     * at most 120 characters.
     */
    fun answerProblems(request: String, answer: String): AnswerProblems = AnswerProblems.NONE

    companion object {
        val NONE: ReplayCheck = object : ReplayCheck {}
    }
}

/** What a host's check found in a run's answer: [failures] fail its replay, [warnings] are only logged. */
class AnswerProblems(val failures: List<String> = emptyList(), val warnings: List<String> = emptyList()) {
    companion object {
        val NONE = AnswerProblems()
    }
}

/**
 * Who the assistant is: its [name] as the user calls it, the [identity] line its system prompt opens with (who it is, in
 * which app), and the [language] it answers in (a BCP 47 tag such as `zh-CN`), which the server's own lines to the model
 * are in too; null answers in the user's own language, with those lines in English. Text the model reads about itself
 * says "you" and needs no name.
 */
class Persona(val name: String, val identity: String, val language: String? = null) {
    companion object {
        /** "Assistant", the assistant of this app on the person's device, answering in the person's language. */
        val GENERIC = Persona(
            name = "Assistant",
            identity = "You are the assistant of this app, running on the user's device. Help with the user's tasks through " +
                "tools; do not present yourself as a generic cloud chatbot or model provider. Do not expose API keys or secrets.",
        )
    }
}

/**
 * The model services a host build carries (from its local.properties, say): the profiles file's text, local overrides
 * of single profiles, fixed values, and where they came from (for the settings page). [sections] are the config file's
 * objects of the host's own, by name, each with the prefix its fields' keys get (`"tts" to "VOICE_"` reads
 * `"tts": {"apiKey": …}` as `VOICE_API_KEY`). A host without any keeps [NONE].
 */
class ProviderPresets(
    val configText: String = "",
    val localOverridesText: String = "",
    val values: Map<String, String> = emptyMap(),
    val source: String = "",
    val sections: Map<String, String> = emptyMap(),
) {
    companion object {
        val NONE = ProviderPresets()
    }
}

/**
 * What the host app plugs into the runtime (its tools, rules, skills and hooks, [HostModels], [RunAddOns], its
 * [Persona], its [ProviderPresets] and [ModelPreset]); none until it installs them.
 */
internal object AppHost {
    @Volatile
    var models: HostModels = HostModels.NONE
        private set

    /** The host's own tool providers for one run ([cc.noharry.agent.appserver.server.AppServerConfig.tools]). */
    @Volatile
    var tools: (Context) -> List<ToolProvider> = NO_TOOLS
        private set

    /** The host's lines for the system rules, before [RunAddOns.systemRules]'s. */
    @Volatile
    var rules: List<String> = emptyList()
        private set

    /** The skills the host ships ([cc.noharry.agent.appserver.server.AppServerConfig.skills]). */
    @Volatile
    var skills: (Context) -> List<RuntimeSkill> = NO_SKILLS
        private set

    /** Gates on every run's tool calls, before [RunAddOns.runHooks]'. */
    @Volatile
    var hooks: List<RuntimeHook> = emptyList()
        private set

    /** Tools that act but run unasked ([cc.noharry.agent.appserver.server.AppServerConfig.unaskedTools]). */
    @Volatile
    var unaskedTools: Set<String> = emptySet()
        private set

    /** The model service the host's build carries, its default profile until the person picks another; null for none. */
    @Volatile
    var modelPreset: ModelPreset? = null
        private set

    /** A new model for each run of the simulated backend; null for the server's own ([FixtureModelClient]). */
    @Volatile
    var simulatedModel: (() -> ModelClient)? = null
        private set

    @Volatile
    var replayCheck: ReplayCheck = ReplayCheck.NONE
        private set

    @Volatile
    var runs: RunAddOns = RunAddOns.NONE
        private set

    @Volatile
    var persona: Persona = Persona.GENERIC
        private set

    @Volatile
    private var wordsFor: Pair<String?, ModelWords>? = null

    /** What the server's own code tells the model, in [persona]'s language. */
    val words: ModelWords
        get() {
            val language = persona.language
            wordsFor?.takeIf { it.first == language }?.let { return it.second }
            return ModelWords.forLanguage(language).also { wordsFor = language to it }
        }

    @Volatile
    var presets: ProviderPresets = ProviderPresets.NONE
        private set

    @Volatile
    var builtIns: BuiltInFeatures = BuiltInFeatures.NONE
        private set

    /** The host's extra trusted roots, a raw resource of PEM certificates; null for the system's alone. */
    @Volatile
    var extraTrustRoots: Int? = null
        private set

    @Volatile
    private var ownAdapters: List<ProviderAdapter> = emptyList()

    /** The host's facts of models, by id, over their services' ([cc.noharry.agent.appserver.server.AppServerConfig.modelFacts]). */
    @Volatile
    var modelFacts: Map<String, ModelFacts> = emptyMap()
        private set

    /** The host's prices of models, by id, over their services' ([cc.noharry.agent.appserver.server.AppServerConfig.modelPrices]). */
    @Volatile
    var modelPrices: Map<String, ModelPrice> = emptyMap()
        private set

    /** What the host and the running plugins know of model services ([ProviderAdapter]); none by default. */
    val providerAdapters: List<ProviderAdapter> get() = ownAdapters + additions.providerAdapters

    /** What the running plugins add ([cc.noharry.agent.appserver.server.PluginHost]). */
    @Volatile
    var additions: cc.noharry.agent.appserver.server.PluginAdditions = cc.noharry.agent.appserver.server.PluginAdditions.NONE
        private set

    /** Whether the server or one of its running plugins offers [feature] ([cc.noharry.agent.appserver.protocol.ServerFeatures]). */
    fun offers(feature: String): Boolean = feature in builtIns.offered() || feature in additions.offers

    /**
     * Whether the server keeps its diagnostic files ([cc.noharry.agent.appserver.server.AppServerConfig.diagnostics]):
     * model calls, run evidence, the simulated backend's tool search and the bench prompt's probes.
     */
    @Volatile
    var diagnostics: Boolean = false
        private set

    /** The scene the person's turns run in ([cc.noharry.agent.appserver.server.AppServerConfig.scene]). */
    @Volatile
    var scene: String = DEFAULT_SCENE
        private set

    fun install(
        models: HostModels = this.models,
        runs: RunAddOns = this.runs,
        persona: Persona = this.persona,
        presets: ProviderPresets = this.presets,
        builtIns: BuiltInFeatures = this.builtIns,
        extraTrustRoots: Int? = this.extraTrustRoots,
        providerAdapters: List<ProviderAdapter> = this.ownAdapters,
        scene: String = this.scene,
        additions: cc.noharry.agent.appserver.server.PluginAdditions = this.additions,
        diagnostics: Boolean = this.diagnostics,
        tools: (Context) -> List<ToolProvider> = this.tools,
        rules: List<String> = this.rules,
        skills: (Context) -> List<RuntimeSkill> = this.skills,
        hooks: List<RuntimeHook> = this.hooks,
        modelPreset: ModelPreset? = this.modelPreset,
        simulatedModel: (() -> ModelClient)? = this.simulatedModel,
        replayCheck: ReplayCheck = this.replayCheck,
        unaskedTools: Set<String> = this.unaskedTools,
        modelFacts: Map<String, ModelFacts> = this.modelFacts,
        modelPrices: Map<String, ModelPrice> = this.modelPrices,
    ) {
        this.modelFacts = modelFacts
        this.modelPrices = modelPrices
        this.unaskedTools = unaskedTools
        this.tools = tools
        this.rules = rules
        this.skills = skills
        this.hooks = hooks
        this.modelPreset = modelPreset
        this.simulatedModel = simulatedModel
        this.replayCheck = replayCheck
        this.additions = additions
        this.diagnostics = diagnostics
        this.ownAdapters = providerAdapters
        this.scene = scene
        this.builtIns = builtIns
        this.extraTrustRoots = extraTrustRoots
        this.models = models
        this.runs = runs
        this.persona = persona
        this.presets = presets
    }
}

/** The scene a host's turns run in unless it names its own. */
internal const val DEFAULT_SCENE = "app"

private val NO_TOOLS: (Context) -> List<ToolProvider> = { emptyList() }
private val NO_SKILLS: (Context) -> List<RuntimeSkill> = { emptyList() }

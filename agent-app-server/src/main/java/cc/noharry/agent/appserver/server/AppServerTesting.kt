@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.server

import android.content.Context
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.mcp.McpSaveResult
import cc.noharry.agent.appserver.mcp.deleteMcpServerConfig
import cc.noharry.agent.appserver.mcp.saveMcpProviderEnabled
import cc.noharry.agent.appserver.mcp.saveMcpServerDraft
import cc.noharry.agent.appserver.protocol.McpServerDraft
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.RunFacts
import cc.noharry.agent.appserver.runtime.RuntimePrompt
import cc.noharry.agent.appserver.runtime.RuntimeVoice
import cc.noharry.agent.appserver.runtime.ToolNotes
import cc.noharry.agent.appserver.runtime.hostTurnOutcome
import cc.noharry.agent.appserver.runtime.loadBasicTools
import cc.noharry.agent.appserver.runtime.pluginRuleLines
import cc.noharry.agent.appserver.runtime.runtimePrompt
import cc.noharry.agent.appserver.skills.RuntimeSkill
import cc.noharry.agent.appserver.skills.loadRuntimeSkills
import cc.noharry.agent.appserver.skills.preloadedSkillBodies
import cc.noharry.agent.appserver.skills.systemPromptIds
import cc.noharry.agent.spi.reliability.RuntimeResult
import cc.noharry.agent.appserver.protocol.ServerInfo
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

/** What [AppServer.install] takes from [config] before anything starts: the host's state, which runs read. */
internal fun installHost(config: AppServerConfig) {
    RuntimeVoice.install(config.voice)
    AppHost.install(
        config.models, config.runs, config.persona, config.presets, builtIns = config.builtIns,
        config.extraTrustRoots, scene = config.scene, diagnostics = config.diagnostics, tools = config.tools,
        rules = config.rules, skills = config.skills, hooks = config.hooks, modelPreset = config.model,
        simulatedModel = config.simulatedModel, replayCheck = config.replayCheck, unaskedTools = config.unaskedTools,
        modelFacts = config.modelFacts, modelPrices = config.modelPrices,
    )
}

/**
 * For another module's tests only (a host's or a plugin module's), the one way they put the server into a state
 * without starting it:
 *
 * - [install]: a host's [AppServerConfig] as [AppServer.install] puts it in place before anything starts (its persona,
 *   tools, rules, skills, hooks, model preset, scripted model, built-in features, presets, voice, host models, run
 *   add-ons, replay check, scene and diagnostics), with nothing started: no runtime, plugins, approval settings or
 *   sockets. [reset] puts a bare host back, with no plugin running.
 * - [running]: what running plugins add, as the server takes it from its plugin host. A test starts a plugin on a
 *   [PluginHost] and passes its [PluginHost.additions] here.
 * - [completedTurn] and [noteToolCall]: a turn's events as the server hands them to a host's voice
 *   ([cc.noharry.agent.appserver.runtime.VoicePort]), so a voice is tested on what it receives.
 * - [prompt]: what the model of a run started now reads of the host (the server's lines, the host's and the running
 *   plugins' rules, the skills it carries), so a host checks its own skills and rules where the model reads them.
 * - [saveMcpServer], [deleteMcpServer] and [switchMcpProvider]: the MCP list as the person sets it up (a server added
 *   or edited, deleted, a row switched).
 * - [requestsAnswered]: the requests a server answers, so a host checks its protocol's list against them.
 * - [models]: a profile's model list as the server fetches it, through the running adapters
 *   ([cc.noharry.agent.appserver.model.ProviderAdapter]), so an adapter's module checks its services' lists.
 *
 * Process-wide, as the server is: a test that installs puts the bare host back when it ends.
 */
@InternalAppServerApi
object AppServerTesting {
    fun install(config: AppServerConfig) = installHost(config)

    fun running(additions: PluginAdditions) = AppHost.install(additions = additions)

    fun reset() {
        install(AppServerConfig(name = "app"))
        running(PluginAdditions.NONE)
    }

    /**
     * [event] as the voice receives it once the server has read the run's [result]: a turn that ended without the outcome
     * it reports (partly done, blocked, unverified) is not a completed one.
     */
    fun completedTurn(event: ThreadEvent.TurnCompleted, result: RuntimeResult?): ThreadEvent.TurnCompleted =
        hostTurnOutcome(event, result)

    /** The words the model wrote for the person on its call of [toolName] with [argumentsJson], as the voice reads them. */
    fun noteToolCall(toolName: String, argumentsJson: String, note: String) = ToolNotes.put(toolName, argumentsJson, note)

    /**
     * The prompt of a run for [input] started now, as the server composes it (without the clock, the app in front and
     * the latest exchange, which change every run): the installed host's skills and the running plugins', as the
     * person's switches and the plugins' tools and sections allow, or [skills] in their place, each switched as it
     * says; [history] as earlier turns would fill the run's context.
     */
    fun prompt(context: Context, input: String, skills: List<RuntimeSkill>? = null, history: String = ""): Prompt {
        val facts = RunFacts(context, input, child = false, webSearch = false, subagentTypes = emptySet())
        val plugins = AppHost.additions
        val carried = skills ?: loadRuntimeSkills(context)
        val toolServers = plugins.runTools.flatMap { it(facts) }.mapTo(hashSetOf()) { it.providerId }
        val bodies = preloadedSkillBodies(context, carried, input, toolServers)
        val rules = AppHost.rules + AppHost.runs.systemRules(facts) + pluginRuleLines(plugins)
        val composed = runtimePrompt(history, loadBasicTools(context), bodies, hostRules = rules, systemSkills = carried.systemPromptIds())
        return Prompt(composed.system, composed.runContext, bodies)
    }

    /** A configured MCP server as the person adds or edits one ([draft]): its id, or why it was refused. */
    fun saveMcpServer(context: Context, draft: McpServerDraft): Result<String> =
        when (val saved = saveMcpServerDraft(context, draft)) {
            is McpSaveResult.Saved -> Result.success(saved.id)
            is McpSaveResult.Rejected -> Result.failure(IllegalArgumentException(saved.reason))
        }

    /** The configured MCP server [id] deleted, with its secrets; false when there was none. */
    fun deleteMcpServer(context: Context, id: String): Boolean = deleteMcpServerConfig(context, id)

    /** A row of the MCP list (a configured server, the bundled catalog, a running plugin's tool server) switched [enabled]. */
    fun switchMcpProvider(context: Context, id: String, enabled: Boolean) {
        saveMcpProviderEnabled(context, id, enabled)
    }

    /**
     * The requests a server answers with [modules] beside its own routes and its connection's, with the built-in
     * features installed: listed, never called (the backends behind them only stand in).
     */
    fun requestsAnswered(modules: List<RouteModule>): Set<String> {
        val hub = ServerHub(
            standIn(), standIn(), Dispatchers.Unconfined, CoroutineScope(Job()), ServerInfo("test"), modules,
        )
        return hub.routes.methodNames + CONNECTION_METHODS
    }

    /** The models [profile] offers with [apiKey], as the server lists them for the settings page. */
    suspend fun models(context: Context, profile: cc.noharry.agent.appserver.model.ProviderProfile, apiKey: String): List<String> =
        cc.noharry.agent.appserver.model.listModels(context, cc.noharry.agent.transport.OkHttpHttpTransport(), profile, apiKey)

    private inline fun <reified T> standIn(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("${method.name} is not answered while requests are only listed")
    } as T

    /**
     * A run's prompt: the [system] part, cached across runs, and the [runContext] of this run; [skills] are those it
     * carries, by id with their text, each in full in one of the two or named as deferred, to be read when needed.
     */
    class Prompt(val system: String, val runContext: String, val skills: List<Pair<String, String>>) {
        /** Both parts, as the model reads them and as the runtime checks their size. */
        val text: String get() = RuntimePrompt(system, runContext).text
    }
}

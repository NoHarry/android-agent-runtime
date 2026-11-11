@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import android.content.Context
import cc.noharry.agent.appserver.evidence.AppDiagnostics
import cc.noharry.agent.appserver.evidence.caseEvidenceConfig
import cc.noharry.agent.appserver.mcp.DEBUG_MCP_PROVIDER_TOOLS_JSON
import cc.noharry.agent.appserver.mcp.McpProgressiveEvidenceRecorder
import cc.noharry.agent.appserver.mcp.PluginMcpServers
import cc.noharry.agent.appserver.mcp.ToolsJsonMcpProvider
import cc.noharry.agent.appserver.mcp.httpMcpToolProvider
import cc.noharry.agent.appserver.mcp.isProviderEnabled
import cc.noharry.agent.appserver.mcp.pluginToolServerOn
import cc.noharry.agent.appserver.model.AppModelClient
import cc.noharry.agent.appserver.model.FIRST_EVENT_TIMEOUT_MS
import cc.noharry.agent.appserver.model.FixtureModelClient
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.model.appWebFetchFallback
import cc.noharry.agent.appserver.model.appWebSearch
import cc.noharry.agent.appserver.model.hasWebLookup
import cc.noharry.agent.appserver.model.nativeUpdates
import cc.noharry.agent.appserver.model.loadContextWindow
import cc.noharry.agent.appserver.model.RetryPolicy
import cc.noharry.agent.appserver.model.serviceAt
import cc.noharry.agent.appserver.model.loadModelImageInput
import cc.noharry.agent.appserver.protocol.McpProviderState
import cc.noharry.agent.appserver.server.AppScreen
import cc.noharry.agent.appserver.server.PluginMcpServer
import cc.noharry.agent.appserver.skills.AppSubagents
import cc.noharry.agent.appserver.skills.SkillToolProvider
import cc.noharry.agent.appserver.skills.SubagentKind
import cc.noharry.agent.appserver.skills.WorkflowSkill
import cc.noharry.agent.appserver.skills.loadRuntimeSkills
import cc.noharry.agent.appserver.skills.loadSubagentSettings
import cc.noharry.agent.appserver.skills.preloadedSkillBodies
import cc.noharry.agent.appserver.skills.selectedWorkflowSkills
import cc.noharry.agent.appserver.skills.systemPromptIds
import cc.noharry.agent.appserver.skills.withSubagentSwitch
import cc.noharry.agent.appserver.packages.JsSandbox
import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.ToolCatalogCacheIdentity
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.model.ServerSideSearchCapability
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import cc.noharry.agent.runtime.LoopSettings
import cc.noharry.agent.runtime.LoopLimits
import cc.noharry.agent.runtime.BackendTools
import cc.noharry.agent.runtime.NetworkToolConfirmationPolicy
import cc.noharry.agent.runtime.SessionHistory
import cc.noharry.agent.runtime.SessionToolActivations
import java.io.File

/**
 * @param sessionEventLogRoot app-private no-backup root for this session's durable EventLog.
 *   This is the lowest-priority EventLog source, so the sample must not also install a
 *   packaged or custom `SESSION_EVENT_LOG` capability; doing so silently wins over the root.
 * @param userInput text that will be sent as [cc.noharry.agent.appserver.runtime.RuntimeTurnRequest.input].
 * @param caseBound production flag; default **false**. When **true**, enables experimental
 *   [runtimeEvidence] for sample.debug.v1 using a hash of **[userInput]** (must be the bench
 *   prompt). Ordinary runs leave this false and do not call [runtimeEvidence].
 * @param sessionId the production session; when set, the run carries the session's earlier runs (see
 *   [RuntimeHistories]) and the run context no longer restates them. Blank keeps each run on its own.
 */
/**
 * One per process. The server builds a runtime per run, so the tools a session's runs found are kept here, and the next
 * run of that session starts with them: the model calls them without searching again, and the tool list stays the same
 * from run to run, so the provider's prompt cache over it holds.
 */
private val appSessionToolActivations = SessionToolActivations(maxToolsPerSession = 64)

/**
 * One per process, like the tools: each session's earlier runs as the model saw them. A run's requests start with them
 * and the run is appended when it ends, so the conversation grows like one chat and the prompt cache covers it.
 * Compaction at 80% of the context window keeps it short; the default byte cap only guards memory.
 */

/** Forgets the tools [sessionId]'s runs found and keeps its history, as the process ending does. For tests. */
internal fun forgetSessionTools(sessionId: String) = appSessionToolActivations.clearSession(sessionId)

/** When a session is deleted; with no session id, when all runtime data is cleared. */
internal fun forgetSessionContinuity(sessionId: String? = null) {
    AppSubagents.forget(sessionId)
    if (sessionId == null) {
        appSessionToolActivations.clearAll()
        RuntimeHistories.forget(null)
    } else {
        appSessionToolActivations.clearSession(sessionId)
        RuntimeHistories.forget(sessionId)
    }
}

internal fun buildRuntime(
    context: Context,
    config: ProviderConfig,
    requestedProfile: ProviderProfile,
    streamEnabled: Boolean,
    useSimulatedModel: Boolean,
    workspaceRoot: File,
    sessionEventLogRoot: File,
    sessionContext: String,
    evidenceRecorder: McpProgressiveEvidenceRecorder?,
    diagnostics: AppDiagnostics,
    mcpProviders: List<McpProviderState>,
    networkConfirmationPolicy: NetworkToolConfirmationPolicy = NetworkToolConfirmationPolicy.REQUIRE_PER_HOST,
    userInput: String = "",
    caseBound: Boolean = false,
    hostHook: RuntimeHook? = null,
    /**
     * A subagent's runtime: it opens pages for their pictures even when hosted search hides web_fetch from the main
     * agent, and leaves out the main agent's workflows (a child given a trip task tried to spawn three of its own).
     */
    subagentChild: Boolean = false,
    /** A typed child's kind: its own search setting ([SubagentKind.searchApi]) over the subagent setting. */
    subagentKind: SubagentKind? = null,
    previousExchange: PreviousExchange? = null,
    sessionId: String = "",
    /** The mode the run's turns answer in, when the server sets it ([ActingToolGate]); null for an ACP session's. */
    approvalMode: (() -> cc.noharry.agent.appserver.protocol.ConfirmationMode)? = null,
    /** In another agent's task, which nobody can be asked in: the person's mode, answering the tools that act there. */
    outsideAnswer: cc.noharry.agent.appserver.protocol.ConfirmationMode? = null,
): AgentRuntime {
    // A service's search API ([ProviderAdapter.lookup]) in place of the provider's server-side search, when the profile (or, for a subagent, the
    // subagent settings) say so.
    val childSearchApi = subagentChild && (subagentKind?.searchApi ?: loadSubagentSettings(context).searchApi)
    // Without the web ([BuiltInFeatures.web]) nothing searches or fetches: no web tools, no service's own search.
    val web = AppHost.builtIns.web
    // A type that needs a lookup API ([SubagentKind.requiresWebLookup]) is offered only while some profile has one.
    if (!subagentChild) AppSubagents.refreshTypes(context, web && config.hasWebLookup(context))
    // A type without 联网搜索 gets neither web_search nor the provider's own search (the guard cannot stop the latter).
    val searches = web && (subagentKind?.searches ?: true)
    val webSearch = if (searches) appWebSearch(context, config, requestedProfile, forceApi = childSearchApi) else null
    // A service's fetch API for pages that refuse the device: its own switch, for the main agent and subagents alike.
    val fetchFallback = if (web) appWebFetchFallback(context, config, requestedProfile) else null
    val profile = if (webSearch != null || !searches) requestedProfile.copy(serverSideSearch = false) else requestedProfile
    val basicTools = loadBasicTools(context).withSubagentSwitch(loadSubagentSettings(context).enabled)
    val skills = loadRuntimeSkills(context).filterNot { subagentChild && it.mainAgentOnly }
    val workflows = selectedWorkflowSkills(context, skills, userInput)
    val history = RuntimeHistories.forRoot(sessionEventLogRoot)
    val carried = if (sessionId.isBlank()) emptyList() else history.messages(sessionId)
    val nativeUpdates = config.nativeUpdates(context, profile)
    // SDK history owns agent turns. Only the latest companion/reminder turn needs explicit context;
    // it is the same previousExchange that supplies the host action gate below.
    // The time, the app in front and where delegations stand, all per run (the run context, never the cached system prompt).
    val spawns = !subagentChild && basicTools.any { it.name == "spawn_agent" && it.enabled }
    val imageInput = loadModelImageInput(context, config.editState(profile))
    val plugins = AppHost.additions
    val facts = RunFacts(
        context = context,
        userInput = userInput,
        child = subagentChild,
        webSearch = !subagentChild && (webSearch != null || profile.usesVisibleWebSearchTool()),
        subagentTypes = if (spawns) AppSubagents.offeredTypeIds() else emptySet(),
        previousQuestion = previousExchange?.takeIf { it.status in ANSWERABLE_STATUSES }?.assistantText,
        imageInput = imageInput,
        appInFront = AppScreen.inFront.takeIf { AppScreen.reports },
        hostModel = AppHost.models.owns(profile.id),
        // The server sets the mode for the app's own turns and their subagents; an editor's session over ACP sets its own.
        editor = approvalMode == null,
    )
    // The running plugins' tools that depend on what the run is (operating the screen), mounted after all the others.
    val runTools = plugins.runTools.flatMap { it(facts) }
    // A skill that describes a plugin's tools (operating the screen) is read only in a run that has them.
    val runToolServers = runTools.mapTo(hashSetOf()) { it.providerId }
    // The host's lines, then the running plugins' (where delegations stand, whether a page may be drawn now).
    val clock = (listOf(clockLine(System.currentTimeMillis()), appForegroundLine(context)) + AppHost.runs.contextLines(facts) +
        plugins.contextLines.flatMap { it(facts) })
        .filter(String::isNotBlank).joinToString("\n")
    // The host's own rules first, then those that depend on what the run can do, then the plugins' behind their marker.
    val hostRules = AppHost.rules + AppHost.runs.systemRules(facts) + pluginRuleLines(plugins)
    val hostTools = AppHost.tools(context)
    val pluginServers = namedPluginServers(plugins.mcpServers, mcpProviders.mapTo(hashSetOf()) { it.id } + hostTools.map { it.providerId })
    // Filled as the plugins' tools are mounted: those that ask before they act, by the name they are mounted under.
    val gatedPluginTools = linkedMapOf<String, ToolProvider>()
    val systemSkills = skills.systemPromptIds()
    val prompt = if (carried.isEmpty()) {
        runtimePrompt(
            "", basicTools, preloadedSkillBodies(context, skills, userInput, runToolServers), previousExchange, nativeUpdates, clock,
            hostRules, systemSkills,
        )
    } else {
        val bodies = preloadedSkillBodies(context, skills, userInput, runToolServers).filterNot { (id, _) -> carried.carriesSkill(id) }
        runtimePrompt("", basicTools, bodies, previousExchange, nativeTools = nativeUpdates, clock = clock, hostRules = hostRules, systemSkills = systemSkills)
    }
    val wrapTools = AppHost.runs.toolWrapper(context, sessionContext, useSimulatedModel)
    // Every tool provider the run mounts, as the run has it, for the gate on the tools that act.
    val servers = mutableListOf<ToolProvider>()
    return runtimeAssembly(
    model =
        AppModelClient(
            delegate = if (useSimulatedModel) {
                AppHost.simulatedModel?.invoke() ?: FixtureModelClient()
            } else {
                config.client(profile, streamEnabled, diagnostics)
            },
            // Only a stream sends its first event early; a whole reply can take longer than the watchdog.
            // The on-device server may still be loading the model when the first call goes out.
            firstEventMillis = (AppHost.models.firstEventTimeoutMs(profile) ?: FIRST_EVENT_TIMEOUT_MS)
                .takeIf { streamEnabled && !useSimulatedModel },
            onModelRequestTools = evidenceRecorder?.let { recorder ->
                { names -> recorder.recordFirstRoundTools(names) }
            },
            onPromptPrefix = diagnostics::recordPromptPrefix,
        ),
    prompt = prompt.system,
    workspace = workspaceVfs(context, workspaceRoot, skills),
    eventLogRoot = sessionEventLogRoot,
    )
    // Install host instructions before NativeRoundRunner builds and records ModelRequest.
    // AppModelClient must not enrich after SessionEventLog capture or Raw would diverge
    // from the provider-visible request.
    .applyMcpProviders(context, mcpProviders, wrapTools, workflows, pluginServers, hostTools, gatedPluginTools, servers)
    .register(servers, runTools)
    // The running plugins' gates that read the run (a hand-off only when the person named the agent), before the host's.
    .apply { plugins.runHooks.flatMap { it(facts) }.forEach(::hook) }
    // What the running plugins give each round (today's date and the plans going, what is remembered about the person).
    .apply { plugins.contextProviders.flatMap { it(facts) }.forEach(::contextProvider) }
    // Semantic VFS stays the workspace; the EventLog gets its own no-backup root so the
    // sample keeps a durable lossless v2 stream instead of falling back to in-memory.
    // Hide web_fetch only when the provider exposes a model-visible web_search server tool and no fetch fallback is on
    // (with one, the main agent opens pages that refuse the device, as subagents do).
    // Chat Completions' search options are request-side and opaque, so web_fetch remains the fallback.
    .limits(runLimits(loadContextWindow(context, config.editState(profile)), serviceAt(profile.baseUrl)?.second?.retry))
    .settings(AppHost.runs.loopSettings(LoopSettings(
        imageInputEnabled = imageInput,
        nativeWebFetchEnabled = subagentChild || fetchFallback != null || !profile.usesVisibleWebSearchTool(),
        disabledNativeTools = runtimeDisabledTools(basicTools),
        networkConfirmationPolicy = networkConfirmationPolicy,
        backendTools = if (nativeUpdates) BackendTools.NATIVE_DEFERRED else BackendTools.SEARCH,
        runContextAsSystem = nativeUpdates,
    )))
    .protocolRules(AppHost.runs.protocolRules())
    .webSearch(webSearch)
    .webFetchFallback(fetchFallback)
    .sessionToolActivations(appSessionToolActivations)
    // Backend tools through call_tool: the tool list stays the same all session, so the history stays cached. A service
    // with native updates ([ProviderAdapter.nativeUpdates]: a service's own endpoint that takes them, or a relay its
    // check found passing them on) loads deferred tools natively instead (schema-checked calls) and takes the context as a system
    // update; gateways drop those fields, so they keep call_tool and a user-role context.
    .runContext(prompt.runContext)
    .apply { if (sessionId.isNotBlank()) sessionHistory(history) }
    // A child's runtime gets the same instance, so its tool list matches its parent's; depth 1 refuses its spawns.
    .subagents(AppSubagents.instance)
    .apply {
        hostHook?.let(::hook)
        // The host's gates: its own on every call, then those that read the run (an order waiting for the user's yes).
        AppHost.hooks.forEach(::hook)
        AppHost.runs.runHooks(facts).forEach(::hook)
        // A plugin's tools that act wait for the person's yes, every time; the others that act, as the mode says.
        val pluginGate = pluginToolGate(pluginServers, gatedPluginTools)?.also { hook(it) }
        hook(ActingToolGate(servers, AppHost.unaskedTools, pluginGate?.asking.orEmpty(), approvalMode, outsideAnswer, plugins::acts))
        // The scripts built-in needs agent-exec-js in the app, which AppServer.install checks.
        if (basicTools.any { it.name == "exec_js" && it.enabled } && JsSandbox.available) execEngine(JsSandbox.execEngine())
        // Production entry: only when host explicitly sets caseBound=true.
        // Hash [userInput] as-is (same bytes as RuntimeTurnRequest.input) — no trim/normalize.
        if (caseBound) {
            runtimeEvidence(caseEvidenceConfig(context, userInput))
        }
    }
    .build()
}

internal fun ProviderProfile.usesVisibleWebSearchTool(): Boolean {
    if (!serverSideSearch) return false
    return when (ServerSideSearchCapability.fromWireName(searchCapability)) {
        ServerSideSearchCapability.ANTHROPIC_SERVER_TOOL,
        ServerSideSearchCapability.OPENAI_RESPONSES_TOOL,
        -> true
        ServerSideSearchCapability.OPENAI_CHAT_OPTIONS,
        ServerSideSearchCapability.NONE,
        -> false
    }
}

private fun AgentRuntimeBuilder.applyMcpProviders(
    context: Context,
    providers: List<McpProviderState>,
    configure: (ToolProvider) -> ToolProvider,
    workflows: List<WorkflowSkill>,
    pluginServers: List<Pair<String, PluginMcpServer>>,
    hostTools: List<ToolProvider>,
    gatedPluginTools: MutableMap<String, ToolProvider>,
    servers: MutableList<ToolProvider>,
): AgentRuntimeBuilder = apply {
    val mounted = mutableListOf<ToolProvider>()
    // Outermost, so a picked option's tool is offered first; a cache-identified catalog keeps its identity.
    val mount: (ToolProvider) -> Unit = { provider ->
        mounted += provider
        register(servers, listOf(if (provider is ToolCatalogCacheIdentity) provider else AppHost.additions.toolWrappers.fold(provider) { wrapped, wrap -> wrap(wrapped) }))
    }
    // The host's bundled tools.json catalog (assets/mcp/tools.json), if any; it may hold it back ([RunAddOns.mountsBundledTools]).
    if (providers.isProviderEnabled(DEBUG_MCP_PROVIDER_TOOLS_JSON) && AppHost.runs.mountsBundledTools()) {
        mount(configure(ToolsJsonMcpProvider.fromAssets(context)))
    }
    // The configured MCP servers, in their order; the workflow skills ride on the tools they bind.
    providers.filter { it.enabled }.mapNotNull { it.server }.forEach { server ->
        val provider = configure(httpMcpToolProvider(context, server))
        mount(if (workflows.isEmpty()) provider else SkillToolProvider(provider, workflows))
    }
    // The running plugins' servers, as the configured ones.
    pluginServers.forEach { (name, server) ->
        val provider = configure(PluginMcpServers.provider(context, name, server))
        mount(if (workflows.isEmpty()) provider else SkillToolProvider(provider, workflows))
    }
    // The running plugins' tool servers the person switched on (the device's calendar, say), after the servers.
    AppHost.additions.toolServers.filter { pluginToolServerOn(context, providers, it) }.forEach { mount(it.provider(context, configure)) }
    val taken = providers.mapTo(hashSetOf()) { it.id } + pluginServers.map { it.first } + hostTools.map { it.providerId } + mounted.map { it.providerId }
    register(servers, pluginToolProviders(AppHost.additions.tools, context, mounted, taken, gatedPluginTools))
    register(servers, hostTools)
}

/** Registers [providers] with the run, noted in [servers]. */
private fun AgentRuntimeBuilder.register(servers: MutableList<ToolProvider>, providers: List<ToolProvider>): AgentRuntimeBuilder =
    tools(providers.also { servers += it })

/** A run's limits: the model's context [window], and how a failed model call is tried again when its service says ([retry]). */
private fun runLimits(window: Long, retry: RetryPolicy?): LoopLimits =
    if (retry == null) {
        LoopLimits(contextWindowTokens = window)
    } else {
        LoopLimits(
            contextWindowTokens = window,
            modelRetryAttempts = retry.attempts,
            retryInitialBackoffMillis = retry.initialBackoffMillis,
            retryMaxBackoffMillis = retry.maxBackoffMillis,
        )
    }

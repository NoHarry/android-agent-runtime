@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.spi.AgentPlugin
import cc.noharry.agent.spi.AgentProfile
import cc.noharry.agent.spi.AgentRuntime as EngineRuntime
import cc.noharry.agent.spi.ExecEngine
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.HookFailurePolicy
import cc.noharry.agent.spi.RuntimeContextProvider
import cc.noharry.agent.spi.RuntimeEvidenceConfig
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.RuntimeHookRegistration
import cc.noharry.agent.spi.RuntimeTurnObserver
import cc.noharry.agent.spi.SessionEventLog
import cc.noharry.agent.spi.ToolMatcher
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelRef
import cc.noharry.agent.kernel.core.context.BootstrapPromptProvider
import cc.noharry.agent.kernel.core.context.BootstrapPromptResolver
import cc.noharry.agent.kernel.core.context.ContextBootstrapPromptResolver
import cc.noharry.agent.kernel.core.context.ContextCatalog
import cc.noharry.agent.kernel.core.context.ConversationCompactor
import cc.noharry.agent.kernel.core.context.HistoryCompactor
import cc.noharry.agent.runtime.ProtocolRules
import cc.noharry.agent.kernel.core.context.ProtocolTools
import cc.noharry.agent.kernel.core.context.SimpleBootstrapPromptResolver
import cc.noharry.agent.kernel.core.context.defaultContextCatalog
import cc.noharry.agent.kernel.core.context.validateHostSystemPrompt
import cc.noharry.agent.kernel.core.context.validateRunContext
import cc.noharry.agent.kernel.core.context.validateSceneRules
import cc.noharry.agent.kernel.core.loop.DEFAULT_MAX_MODEL_ROUNDS
import cc.noharry.agent.kernel.core.loop.DEFAULT_MAX_TOOL_CALLS_PER_RUN
import cc.noharry.agent.kernel.core.loop.DEFAULT_MODEL_RETRY_MAX_ATTEMPTS
import cc.noharry.agent.kernel.core.loop.LoopBudgetConfig
import cc.noharry.agent.runtime.LoopSettings
import cc.noharry.agent.kernel.core.loop.ModelRetryConfig
import cc.noharry.agent.kernel.core.plugin.ProfileCompiler
import cc.noharry.agent.kernel.core.plugin.RuntimeBlueprint
import cc.noharry.agent.kernel.core.plugin.RuntimeCapabilityDeclarations
import cc.noharry.agent.kernel.core.plugin.RuntimeCompositionMessages
import cc.noharry.agent.kernel.core.state.DEFAULT_MAX_ACTIVE_SESSIONS
import cc.noharry.agent.kernel.core.state.DEFAULT_MAX_QUEUED_RUNS_GLOBAL
import cc.noharry.agent.kernel.core.state.DEFAULT_MAX_QUEUED_RUNS_PER_SESSION
import cc.noharry.agent.kernel.core.state.FileBackedLoopHistoryStore
import cc.noharry.agent.kernel.core.state.InMemoryLoopHistoryStore
import cc.noharry.agent.kernel.core.state.LoopHistoryStore
import cc.noharry.agent.kernel.core.state.MAX_LOOP_HISTORY_BYTES
import cc.noharry.agent.kernel.core.state.ReliabilityRetentionPolicy
import cc.noharry.agent.kernel.core.state.ReliabilityStorage
import cc.noharry.agent.kernel.core.state.SessionRunSchedulerConfig
import cc.noharry.agent.kernel.core.state.SessionStore
import cc.noharry.agent.kernel.core.state.defaultSessionRunSchedulerConfig
import cc.noharry.agent.kernel.core.tools.BackendToolExposure
import cc.noharry.agent.kernel.core.tools.ConcurrencyLimitedExecEngine
import cc.noharry.agent.kernel.core.tools.ConcurrencyLimitedModelClient
import cc.noharry.agent.kernel.core.tools.DEFAULT_MAX_CONCURRENT_MODEL_CALLS
import cc.noharry.agent.kernel.core.tools.DEFAULT_MAX_CONCURRENT_TOOL_CALLS
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.kernel.core.tools.ProviderConcurrencyLimiter
import cc.noharry.agent.kernel.core.tools.ToolActivationStore
import cc.noharry.agent.kernel.core.tools.ToolDomainIndexer
import cc.noharry.agent.runtime.ToolDomainNaming
import cc.noharry.agent.kernel.core.tools.ToolProviderRegistry
import cc.noharry.agent.kernel.core.tools.WORKSPACE_TOOL_NAMES
import cc.noharry.agent.runtime.DefaultTraceRedactor
import cc.noharry.agent.runtime.StandardTraceRedactor
import cc.noharry.agent.runtime.TraceRedactor
import cc.noharry.agent.runtime.TraceVisibilityConfig
import cc.noharry.agent.policy.PolicyEngine
import cc.noharry.agent.policy.PolicyRule
import cc.noharry.agent.runtime.reliability.FileBackedReliabilityStorage
import cc.noharry.agent.runtime.reliability.InternalRuntimeEvidenceConfig
import cc.noharry.agent.runtime.reliability.ReliabilityStorageHardeningPolicy
import cc.noharry.agent.runtime.reliability.validateRuntimeEvidenceConfig
import cc.noharry.agent.runtime.run.BlueprintCapabilities
import cc.noharry.agent.runtime.run.DEFAULT_HOOK_TIMEOUT_MILLIS
import cc.noharry.agent.runtime.run.PolicyConfigException
import cc.noharry.agent.runtime.run.PolicyConfigLoader
import cc.noharry.agent.runtime.run.PolicyHookAdapter
import cc.noharry.agent.runtime.run.RuntimeHookChain
import cc.noharry.agent.runtime.run.RuntimeMemoryStore
import cc.noharry.agent.runtime.run.RuntimeProfileAdapter
import cc.noharry.agent.runtime.run.TodoStore
import cc.noharry.agent.runtime.workspace.ContextMaterializerRouter
import cc.noharry.agent.runtime.workspace.FileBackedMaterializerSchemaCache
import cc.noharry.agent.runtime.workspace.MaterializerSchemaCacheStore
import cc.noharry.agent.runtime.workspace.MemoryWorkspace
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsPathResolver
import cc.noharry.agent.vfs.VfsReadResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class AgentRuntimeBuilder {
    companion object {
        /** Host factory. Production callsites must use this instead of the constructor. */
        @JvmStatic
        fun create(): AgentRuntimeBuilder = AgentRuntimeBuilder()
    }

    private constructor()

    internal val declarations = RuntimeCapabilityDeclarations.Collector()
    internal val plugins = mutableListOf<AgentPlugin>()
    internal var profile: AgentProfile? = null
    internal var configuredModel: ModelClient? = null
    internal var threadStorageRoot: File? = null
    internal var workspaceRoot: File? = null
    internal var sessionStoreFactory: ((File) -> SessionStore)? = null
    internal var virtualWorkspace: VfsFileSystem? = null
    internal val runtimeWorkspaces = linkedMapOf<String, RuntimeWorkspaceRef>()
    internal var defaultWorkspaceId: String? = null
    internal var confirmationTemplate: ConfirmationTemplate? = null
    internal var reliabilityStorageRoot: File? = null
    internal var reliabilityRetentionPolicy: ReliabilityRetentionPolicy = ReliabilityRetentionPolicy()
    internal var reliabilityStorageHardeningPolicy: ReliabilityStorageHardeningPolicy =
        ReliabilityStorageHardeningPolicy()
    /** Validated host evidence config from experimental [runtimeEvidence]; null = disabled. */
    internal var evidenceConfig: InternalRuntimeEvidenceConfig? = null
    internal var traceVisibilityConfig: TraceVisibilityConfig = TraceVisibilityConfig()
    internal var traceRedactor: TraceRedactor = DefaultTraceRedactor
    internal var nowEpochMillis: () -> Long = { System.currentTimeMillis() }
    internal val policyRules = mutableListOf<PolicyRule>()
    internal val policyConfigJsons = mutableListOf<String>()
    internal val policyConfigFiles = mutableListOf<File>()
    internal val policyConfigPaths = mutableListOf<String>()
    internal val hooks = mutableListOf<PendingRuntimeHookRegistration>()
    internal var hookTimeoutMillis: Long = DEFAULT_HOOK_TIMEOUT_MILLIS
    internal var hostSystemPrompt: String? = null
    internal var runContext: String = ""
    internal var loopBudget: LoopBudgetConfig = LoopBudgetConfig()
    internal var contextInputTokenThreshold: Long? = null
    internal var contextWindowTokens: Long? = null
    internal val historyByteBudget: Int get() = if (contextInputTokenThreshold == null) MAX_LOOP_HISTORY_BYTES else Int.MAX_VALUE
    internal var maxConcurrentToolCalls: Int = DEFAULT_MAX_CONCURRENT_TOOL_CALLS
    internal var maxConcurrentModelCalls: Int = DEFAULT_MAX_CONCURRENT_MODEL_CALLS
    internal var sessionRunSchedulerConfig: SessionRunSchedulerConfig = defaultSessionRunSchedulerConfig()
    internal var modelRetryConfig: ModelRetryConfig = ModelRetryConfig()
    internal var sessionEventLogRoot: File? = null
    internal var agentsMdContent: String? = null
    internal val sceneRules = mutableMapOf<String, String>()
    internal val hostSkills = mutableListOf<HostSkill>()
    internal var sessionActivationInheritance: Boolean = false
    internal var sessionToolActivations: SessionToolActivations? = null
    internal var sessionHistory: SessionHistory? = null
    internal var subagents: Subagents? = null
    internal var webSearchProvider: WebSearchProvider? = null
    internal var webFetchFallback: WebFetchFallback? = null
    internal var loopSettings: LoopSettings = LoopSettings()
    internal var lessons: Boolean = true
    internal var toolDomains: ToolDomainNaming = ToolDomainIndexer.CONVENTIONS
    internal var protocolRules: ProtocolRules = ProtocolRules.DEFAULT
    internal var deferredBackendTools: Boolean = false
    internal var nativeDeferredTools: Boolean = false
    internal var runContextAsSystem: Boolean = false
    internal var networkConfirmationPolicy: NetworkToolConfirmationPolicy = NetworkToolConfirmationPolicy.REQUIRE_PER_HOST
    internal var nativeWebFetchEnabled: Boolean = true
    internal var disabledNativeTools: Set<String> = emptySet()

    fun model(client: ModelClient): AgentRuntimeBuilder = apply {
        configuredModel = client
        declarations.modelClient(client)
    }

    /** Registers tools implemented by the host or a connected MCP server. */
    fun tools(vararg providers: ToolProvider): AgentRuntimeBuilder = tools(providers.toList())

    fun tools(providers: List<ToolProvider>): AgentRuntimeBuilder = apply {
        providers.forEach(declarations::toolProvider)
    }

    fun skills(vararg skills: HostSkill): AgentRuntimeBuilder = apply { hostSkills += skills }

    /** Persists conversation items and model history independently of the optional workspace. */
    fun storage(directory: File): AgentRuntimeBuilder = apply {
        threadStorageRoot = directory
        sessionEventLogRoot = File(directory, "events")
        if (reliabilityStorageRoot == null) reliabilityStorageRoot = File(directory, "runtime")
    }

    /** The round, tool, time, context and retry budgets for turns. */
    fun limits(limits: LoopLimits): AgentRuntimeBuilder = apply {
        loopBudget(limits.maxModelRounds, limits.maxToolCallsPerTurn, limits.maxWallClockMillis)
        limits.contextWindowTokens?.let { contextWindow(it, limits.compactionThresholdRatio) }
        modelRetry(limits.modelRetryAttempts, limits.retryInitialBackoffMillis, limits.retryMaxBackoffMillis, limits.fallbackModels)
        loopSettings = loopSettings.withLimits(limits)
    }

    internal fun modelClient(client: ModelClient): AgentRuntimeBuilder = model(client)

    /** Sets the immutable profile identity compiled through [ProfileCompiler]. */
    @ExperimentalAgentRuntimeApi
    internal fun profile(profile: AgentProfile): AgentRuntimeBuilder = apply {
        this.profile = profile
    }

    /** Installs one trusted, host-packaged plugin into the single runtime Kernel. */
    @ExperimentalAgentRuntimeApi
    internal fun plugin(plugin: AgentPlugin): AgentRuntimeBuilder = apply {
        plugins += plugin
    }

    /** Uses a host-supplied append-only event authority instead of the Kernel default. */
    fun sessionEventLog(log: SessionEventLog): AgentRuntimeBuilder = apply {
        declarations.sessionEventLog(log)
    }

    /**
     * Configures the official kernel-owned durable EventLog root. Used only when the host does
     * not supply [sessionEventLog] and no packaged [AgentCapabilities.SESSION_EVENT_LOG] exists.
     * Business [virtualWorkspace] is never selected as the EventLog writer.
     */
    fun sessionEventLogRoot(root: File): AgentRuntimeBuilder = apply {
        sessionEventLogRoot = root
    }

    /** Registers a generic [ToolProvider]. Canonical capability is [cc.noharry.agent.spi.AgentCapabilities.TOOL_PROVIDER]. */
    internal fun toolProvider(provider: ToolProvider): AgentRuntimeBuilder = apply {
        declarations.toolProvider(provider)
    }

    /** Registers generic [ToolProvider]s. Canonical capability is [cc.noharry.agent.spi.AgentCapabilities.TOOL_PROVIDER]. */
    internal fun toolProviders(providers: List<ToolProvider>): AgentRuntimeBuilder = apply {
        providers.forEach(declarations::toolProvider)
    }

    /**
     * Registers a pre-model [RuntimeContextProvider].
     * IDs must be unique; implementations live outside `agent-runtime`.
     */
    fun contextProvider(provider: RuntimeContextProvider): AgentRuntimeBuilder = apply {
        declarations.contextProvider(provider)
    }

    /**
     * Registers a [RuntimeTurnObserver] for pre-model / terminal hooks.
     * IDs must be unique; implementations live outside `agent-runtime`.
     */
    @ExperimentalAgentRuntimeApi
    fun turnObserver(observer: RuntimeTurnObserver): AgentRuntimeBuilder = apply {
        declarations.turnObserver(observer)
    }

    /**
     * Keeps sessions, runs, messages and usage in the store [factory] opens under the [workspaceRoot]; without a
     * workspace root there is no session store. `agent-runtime-android` supplies an SQLite store.
     */
    /** @suppress */
    @cc.noharry.agent.spi.InternalAgentRuntimeApi
    fun sessionStore(factory: (workspaceRoot: File) -> SessionStore): AgentRuntimeBuilder = apply {
        sessionStoreFactory = factory
    }

    fun workspaceRoot(root: File): AgentRuntimeBuilder = apply {
        workspaceRoot = root
        val workspace = VfsFileSystem.singleFileRoot(root)
        virtualWorkspace = workspace
        registerWorkspace(RuntimeWorkspaceRef(DEFAULT_WORKSPACE_ID, workspace), makeDefault = true)
    }

    /** Uses a host-configured semantic-free VFS workspace. */
    fun virtualWorkspace(workspace: VfsFileSystem): AgentRuntimeBuilder = apply {
        workspaceRoot = null
        virtualWorkspace = workspace
        registerWorkspace(RuntimeWorkspaceRef(DEFAULT_WORKSPACE_ID, workspace), makeDefault = true)
    }

    /** Registers a host workspace selectable by `AgentRequest.workspaceId`. */
    fun workspace(ref: RuntimeWorkspaceRef): AgentRuntimeBuilder = apply {
        registerWorkspace(ref, makeDefault = defaultWorkspaceId == null)
    }

    /** Sets the default workspace id used when a request does not select one. */
    fun defaultWorkspace(workspaceId: String): AgentRuntimeBuilder = apply {
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank." }
        defaultWorkspaceId = workspaceId
    }

    internal fun confirmationOnRun(
        expiresInMillis: Long = DEFAULT_CONFIRMATION_TIMEOUT_MILLIS,
    ): AgentRuntimeBuilder = apply {
        confirmationTemplate = ConfirmationTemplate(expiresInMillis)
    }

    /**
     * Host/test injectable wall clock for referenceTime and runtime timers.
     * Required for deterministic temporal event Runtime E2E (validTime vs learnedAt).
     */
    @ExperimentalAgentRuntimeApi
    internal fun nowEpochMillis(clock: () -> Long): AgentRuntimeBuilder = apply {
        nowEpochMillis = clock
    }

    /** Adds a host policy rule. */
    fun policyRule(rule: PolicyRule): AgentRuntimeBuilder = apply {
        policyRules += rule
    }

    /** Adds a host runtime hook. */
    fun hook(
        hook: RuntimeHook,
        matcher: ToolMatcher = ToolMatcher.All,
        timeoutMillis: Long? = null,
        failurePolicy: HookFailurePolicy = HookFailurePolicy.FAIL_CLOSED,
    ): AgentRuntimeBuilder = apply {
        timeoutMillis?.let { require(it > 0) { "timeoutMillis must be positive." } }
        hooks += PendingRuntimeHookRegistration(
            hook = hook,
            matcher = matcher,
            timeoutMillis = timeoutMillis,
            failurePolicy = failurePolicy,
        )
    }

    /** Sets the pre-hook timeout in milliseconds. */
    @ExperimentalAgentRuntimeApi
    internal fun hookTimeoutMillis(timeoutMillis: Long): AgentRuntimeBuilder = apply {
        require(timeoutMillis > 0) { "hookTimeoutMillis must be positive." }
        hookTimeoutMillis = timeoutMillis
    }

    /** Appends host instructions after the internal protocol prompt. */
    internal fun systemPrompt(text: String): AgentRuntimeBuilder = instructions(text)

    fun instructions(text: String): AgentRuntimeBuilder = apply {
        validateHostSystemPrompt(text)
        hostSystemPrompt = text.takeIf { it.isNotBlank() }
    }

    /**
     * Host context for this runtime's runs (a conversation recap, the previous exchange, instructions picked for the
     * request), sent as a user message right after each run's input instead of in [systemPrompt]. Keeping what changes
     * from run to run out of the system prompt lets the provider's prompt cache keep the system prompt and the tool list
     * across runs. At most 16 KiB.
     */
    fun runContext(text: String): AgentRuntimeBuilder = apply {
        validateRunContext(text)
        runContext = text
    }

    /** Supplies an explicit in-memory `/AGENTS.md` fallback. Prefer a real workspace file. */
    fun agentsMd(content: String): AgentRuntimeBuilder = apply {
        agentsMdContent = content.takeIf { it.isNotBlank() }
    }

    /**
     * Rules for runs in [scene]: the system prompt carries [content] under the current scene, and a run in another
     * scene doesn't see it. Up to 16 KiB, like the host prompt; blank content removes the scene's rules.
     */
    fun sceneAgentsMd(scene: String, content: String): AgentRuntimeBuilder = apply {
        require(scene.isNotBlank()) { "A scene's rules need the scene's name." }
        if (content.isBlank()) {
            sceneRules.remove(scene)
        } else {
            validateSceneRules(scene, content)
            sceneRules[scene] = content
        }
    }

    /** Registers a host-provided skill for progressive disclosure. */
    internal fun skill(hostSkill: HostSkill): AgentRuntimeBuilder = apply {
        hostSkills += hostSkill
    }

    /** Enables session-level tool activation inheritance. */
    fun sessionActivationInheritance(enabled: Boolean = true): AgentRuntimeBuilder = apply {
        sessionActivationInheritance = enabled
    }

    /**
     * Keeps tool activations in [activations], which the host shares across the runtimes it builds for one session,
     * and turns on session inheritance.
     */
    @ExperimentalAgentRuntimeApi
    fun sessionToolActivations(activations: SessionToolActivations): AgentRuntimeBuilder = apply {
        sessionToolActivations = activations
        sessionActivationInheritance = true
    }

    /**
     * Starts each run's requests with its session's earlier runs from [history], which the host shares across the
     * runtimes it builds for one session, and adds the run to it when it ends.
     */
    fun sessionHistory(history: SessionHistory): AgentRuntimeBuilder = apply {
        sessionHistory = history
    }

    /**
     * Offers spawn_agent / wait_agent / cancel_agent, backed by [subagents], which the host shares across the runtimes
     * it builds (children outlive runs). Needs a workspace. A child's own runtime gets the same instance, so its tool
     * list matches its parent's; [SubagentConfig.maxDepth] refuses a child's spawn.
     */
    fun subagents(subagents: Subagents): AgentRuntimeBuilder = apply {
        this.subagents = subagents
    }

    /**
     * Offers the native web_search tool, backed by [provider] (e.g. a provider's search API): queries and results
     * reach the model and the event log, unlike a provider's server-side search, which the host should then turn
     * off. Null offers no web_search.
     */
    @ExperimentalAgentRuntimeApi
    fun webSearch(provider: WebSearchProvider?): AgentRuntimeBuilder = apply {
        webSearchProvider = provider
    }

    /**
     * What the loop judges about content in the host's users' language and its domain, and its timing ([LoopSettings]):
     * which final answers only announce what the agent will do, what lessons are about and how many reach a run, which
     * failed tool results are a service's answer, how long context providers have. A subagent's runtime, which the host
     * builds, takes its own.
     */
    @ExperimentalAgentRuntimeApi
    internal fun loopSettings(settings: LoopSettings): AgentRuntimeBuilder = settings(settings)

    fun settings(settings: LoopSettings): AgentRuntimeBuilder = apply {
        limits(settings.limits)
        loopSettings = settings
        imageInput(settings.imageInputEnabled)
        deferredBackendTools(settings.backendTools == BackendTools.SEARCH)
        nativeDeferredTools(settings.backendTools == BackendTools.NATIVE_DEFERRED)
        runContextAsSystem(settings.runContextAsSystem)
        lessons(settings.learnLessons)
        nativeWebFetchEnabled(settings.nativeWebFetchEnabled)
        disabledNativeTools(settings.disabledNativeTools)
        networkConfirmationPolicy(settings.networkConfirmationPolicy)
        hookTimeoutMillis(settings.hookTimeoutMillis)
        toolConcurrency(settings.concurrency.tools)
        modelConcurrency(settings.concurrency.models)
        sessionConcurrency(settings.concurrency.activeThreads, settings.concurrency.queuedPerThread, settings.concurrency.queuedTotal)
    }

    /**
     * Whether runs learn lessons (which tools did what was asked) and later runs get the fitting ones, including those
     * saved before; on by default when the runtime has a workspace to keep them in.
     */
    @ExperimentalAgentRuntimeApi
    internal fun lessons(enabled: Boolean): AgentRuntimeBuilder = apply {
        lessons = enabled
    }

    /**
     * The lines of the protocol section that tells the model how to use the runtime's tools ([ProtocolRules]): reword,
     * drop or add lines, such as the web search fallback offered when no search provider is set, or the folders the
     * workspace may be written in.
     */
    fun protocolRules(rules: ProtocolRules): AgentRuntimeBuilder = apply {
        protocolRules = rules
    }

    /** How tools are grouped into the areas the prompt lists for the model ([ToolDomainNaming]). */
    fun toolDomains(naming: ToolDomainNaming): AgentRuntimeBuilder = apply {
        toolDomains = naming
    }

    /**
     * Gets a page through [fallback] (e.g. a provider's fetch API) when web_fetch's own request was refused or failed;
     * the result says where it came from (`via`). Works with or without [webSearch]. Null keeps web_fetch to the device's
     * own requests.
     */
    @ExperimentalAgentRuntimeApi
    fun webFetchFallback(fallback: WebFetchFallback?): AgentRuntimeBuilder = apply {
        webFetchFallback = fallback
    }

    /**
     * Keeps backend tools out of the model's tool list, as Codex defers tools behind tool_search: search_tools results
     * and a tools-ready message for the run carry their schemas, and the model calls them through `call_tool`. The tool
     * list then stays the same for a whole session, so finding a tool mid-run no longer makes the session history miss
     * the provider's prompt cache. Hooks, policy, confirmation and events still see the real tool.
     */
    @ExperimentalAgentRuntimeApi
    internal fun deferredBackendTools(enabled: Boolean = true): AgentRuntimeBuilder = apply {
        deferredBackendTools = enabled
    }

    /**
     * Keeps the tool list stable with the provider's own mid-conversation tool changes instead of `call_tool`: backend
     * tools are declared `defer_loading` (no input cost) and loaded by `tool_addition` system messages as they are found,
     * so the model calls them natively with schema-checked arguments. Only for providers that support it, such as
     * DeepSeek's official Anthropic endpoint (gateways may drop the fields). Takes precedence over
     * [deferredBackendTools].
     */
    @ExperimentalAgentRuntimeApi
    internal fun nativeDeferredTools(enabled: Boolean = true): AgentRuntimeBuilder = apply {
        nativeDeferredTools = enabled
    }

    /**
     * Sends [runContext] as a system message in history instead of a user message: the same prompt-cache behavior with
     * system weight. Only for providers that accept system messages after the first turn (DeepSeek; not Anthropic's own
     * API).
     */
    @ExperimentalAgentRuntimeApi
    internal fun runContextAsSystem(enabled: Boolean = true): AgentRuntimeBuilder = apply {
        runContextAsSystem = enabled
    }

    /** Sets native loop budgets. */
    internal fun loopBudget(
        maxModelRounds: Int = DEFAULT_MAX_MODEL_ROUNDS,
        maxToolCallsPerRun: Int = DEFAULT_MAX_TOOL_CALLS_PER_RUN,
        maxWallClockMillis: Long? = null,
    ): AgentRuntimeBuilder = apply {
        loopBudget = LoopBudgetConfig(maxModelRounds, maxToolCallsPerRun, maxWallClockMillis, loopBudget.imageInputEnabled)
    }

    /**
     * Whether the model accepts image input (default true). When false, every image in a
     * request, such as a tool screenshot, becomes a text placeholder; the EventLog still keeps it.
     */
    internal fun imageInput(enabled: Boolean): AgentRuntimeBuilder = apply {
        loopBudget = loopBudget.copy(imageInputEnabled = enabled)
    }

    /**
     * Uses the model's context capacity for automatic compaction (default 80%).
     * Uses reported inclusive input tokens per model call, not billing totals; disables legacy
     * byte-triggered compaction/history trimming. Existing defaults remain when unset.
     */
    internal fun contextWindow(tokens: Long, compactionThresholdRatio: Double = 0.8): AgentRuntimeBuilder = apply {
        require(tokens > 0) { "Context window must be positive." }
        require(compactionThresholdRatio > 0.0 && compactionThresholdRatio <= 1.0) { "Compaction ratio must be in (0, 1]." }
        contextInputTokenThreshold = kotlin.math.ceil(tokens * compactionThresholdRatio).toLong().coerceAtLeast(1)
        contextWindowTokens = tokens
    }

    /** Sets the runtime-wide cap for concurrent tool executions. */
    internal fun toolConcurrency(
        maxConcurrentCalls: Int = 4,
    ): AgentRuntimeBuilder = apply {
        require(maxConcurrentCalls > 0) { "maxConcurrentCalls must be positive." }
        maxConcurrentToolCalls = maxConcurrentCalls
    }

    /**
     * Sets the runtime-wide cap for concurrent model completions, including history
     * compaction. The same wrapper is shared by the loop and [HistoryCompactor].
     */
    internal fun modelConcurrency(
        maxConcurrentCalls: Int = 4,
    ): AgentRuntimeBuilder = apply {
        require(maxConcurrentCalls > 0) { "maxConcurrentCalls must be positive." }
        maxConcurrentModelCalls = maxConcurrentCalls
    }

    /**
     * Sets process-local session/run admission budgets. All values must be positive.
     * `maxActiveSessions = 1` is the supported rollback setting. The scheduler is constructed
     * per runtime and ordinary-run admission is wired in-process; process-restored admission
     * remains deferred.
     */
    internal fun sessionConcurrency(
        maxActiveSessions: Int = DEFAULT_MAX_ACTIVE_SESSIONS,
        maxQueuedRunsPerSession: Int = DEFAULT_MAX_QUEUED_RUNS_PER_SESSION,
        maxQueuedRunsGlobal: Int = DEFAULT_MAX_QUEUED_RUNS_GLOBAL,
    ): AgentRuntimeBuilder = apply {
        require(maxActiveSessions > 0) { "maxActiveSessions must be positive." }
        require(maxQueuedRunsPerSession > 0) { "maxQueuedRunsPerSession must be positive." }
        require(maxQueuedRunsGlobal > 0) { "maxQueuedRunsGlobal must be positive." }
        sessionRunSchedulerConfig = SessionRunSchedulerConfig(
            maxActiveSessions = maxActiveSessions,
            maxQueuedRunsPerSession = maxQueuedRunsPerSession,
            maxQueuedRunsGlobal = maxQueuedRunsGlobal,
        )
    }

    /** Sets recoverable model retry and fallback behavior. */
    internal fun modelRetry(
        maxAttempts: Int = DEFAULT_MODEL_RETRY_MAX_ATTEMPTS,
        initialBackoffMillis: Long = 250L,
        maxBackoffMillis: Long = 2_000L,
        fallbackChain: List<ModelRef> = emptyList(),
    ): AgentRuntimeBuilder = apply {
        modelRetryConfig = ModelRetryConfig(
            maxAttempts = maxAttempts,
            initialBackoffMillis = initialBackoffMillis,
            maxBackoffMillis = maxBackoffMillis,
            fallbackChain = fallbackChain,
        )
    }

    /**
     * Controls host-level confirmation for `web_fetch`. `ALLOW_ALL_HOSTS` skips the per-host
     * confirmation gate entirely (URL validation and private-address denial still apply).
     */
    internal fun networkConfirmationPolicy(policy: NetworkToolConfirmationPolicy): AgentRuntimeBuilder = apply {
        networkConfirmationPolicy = policy
    }

    /**
     * When false, the native `web_fetch` tool is omitted from the model toolset. Hosts that
     * expose a model-visible `web_search` server tool should set this false to avoid the model
     * preferring `web_fetch` over `web_search`.
     */
    internal fun nativeWebFetchEnabled(enabled: Boolean): AgentRuntimeBuilder = apply {
        nativeWebFetchEnabled = enabled
    }

    /**
     * Excludes optional native tools from model requests and direct/nested dispatch.
     * Copies the set; unknown names and protocol-required finish are rejected.
     * An engine is still required for exec_js.
     */
    internal fun disabledNativeTools(names: Set<String>): AgentRuntimeBuilder = apply {
        disabledNativeTools = cc.noharry.agent.kernel.core.tools.snapshotDisabledNativeTools(names)
    }

    /** Registers a host-provided exec engine. */
    fun execEngine(engine: ExecEngine): AgentRuntimeBuilder = apply {
        declarations.execEngine(engine)
    }

    /** Adds JSON policy config text. Build fails fast when config is invalid. */
    fun policyConfigJson(json: String): AgentRuntimeBuilder = apply {
        policyConfigJsons += json
    }

    /** Adds a JSON policy config file. Build fails fast when config is invalid. */
    fun policyConfig(file: File): AgentRuntimeBuilder = apply {
        policyConfigFiles += file
    }

    /** Adds a JSON policy config from the configured virtual workspace. */
    fun policyConfigPath(path: String): AgentRuntimeBuilder = apply {
        policyConfigPaths += normalizedPolicyConfigPath(path)
    }

    internal fun traceVisibility(config: TraceVisibilityConfig): AgentRuntimeBuilder = apply {
        traceVisibilityConfig = config
    }

    /**
     * How the run's public events, summaries and failure reports hide personal data: [DefaultTraceRedactor] unless set;
     * the agent's answers and questions to the user go out as written, for the host to filter if it wants. A
     * [StandardTraceRedactor][cc.noharry.agent.runtime.StandardTraceRedactor] keeps phone numbers or coordinates
     * in events and summaries where an app needs them shown. Secrets are hidden after [redactor] whatever it does.
     */
    fun traceRedactor(redactor: TraceRedactor): AgentRuntimeBuilder = apply {
        traceRedactor = TraceRedactor { value -> SECRETS_ONLY.redact(redactor.redact(value)) }
    }

    /**
     * Same-module storage root only. Must not appear on the public host API surface.
     * Prefer [runtimeEvidence] for host/debug evidence wiring.
     */
    internal fun reliabilityStorageRoot(root: File): AgentRuntimeBuilder = apply {
        // Only apply when experimental evidence config is not already set.
        if (evidenceConfig == null) {
            reliabilityStorageRoot = root
        }
    }

    /**
     * Experimental aggregated host evidence configuration (debug/local only).
     *
     * Single public entry for reliability root, allowlisted probe pack ids, and
     * case binding metadata. Unknown pack ids and illegal case/hash combinations
     * throw [IllegalArgumentException] at configuration time.
     *
     * Does **not** accept internal [ProbePack] instances (no ProbePack in public/JVM ABI).
     */
    @ExperimentalAgentRuntimeApi
    fun runtimeEvidence(config: RuntimeEvidenceConfig): AgentRuntimeBuilder = apply {
        val validated = validateRuntimeEvidenceConfig(config)
        evidenceConfig = validated
        // Storage root is always applied so reliability/session files can share the path;
        // packs resolved only at build time from allowlisted string ids (no ProbePack in ABI).
        reliabilityStorageRoot = validated.storageRoot
    }

    internal fun reliabilityRetentionPolicy(policy: ReliabilityRetentionPolicy): AgentRuntimeBuilder = apply {
        reliabilityRetentionPolicy = policy
    }

    internal fun reliabilityStorageHardeningPolicy(
        policy: ReliabilityStorageHardeningPolicy,
    ): AgentRuntimeBuilder = apply {
        reliabilityStorageHardeningPolicy = policy
    }

    /** Builds the runtime synchronously; call from a worker thread when storage or files are configured. */
    fun build(): cc.noharry.agent.AgentRuntime {
        val model = requireNotNull(configuredModel) { "Configure a model with model(...) before building a runtime." }
        val store = cc.noharry.agent.runtime.thread.ThreadStore(threadStorageRoot, traceRedactor, nowEpochMillis)
        val history = sessionHistory ?: SessionHistory().also { sessionHistory = it }
        if (threadStorageRoot != null) history.store.bindPersistence(store.modelHistoryPersistence())
        val engine = buildEngine() as DefaultAgentRuntime
        return cc.noharry.agent.runtime.thread.DefaultThreadRuntime(
            engine, store, loopBudget.imageInputEnabled,
            confirmationTemplate?.expiresInMillis ?: DEFAULT_CONFIRMATION_TIMEOUT_MILLIS,
            nowEpochMillis, model::supportsImageInput, sessionRunSchedulerConfig,
        )
    }

    internal fun buildEngine(): EngineRuntime {
        val compiled = RuntimeProfileAdapter.compile(
            profile = profile ?: AgentProfile(RuntimeCompositionMessages.DEFAULT_PROFILE_ID),
            declarations = declarations.snapshot(),
            packagedPlugins = plugins.toList(),
        )
        return try {
            val capabilities = RuntimeProfileAdapter.resolve(compiled, sessionEventLogRoot, nowEpochMillis)
            val infrastructure = buildRuntimeInfrastructure(capabilities, compiled)
            createRuntime(capabilities, infrastructure, compiled)
        } catch (failure: Throwable) {
            compiled.close()
            throw failure
        }
    }

    /** Builds without configuration or storage I/O on the caller context. */
    suspend fun buildAsync(): cc.noharry.agent.AgentRuntime = withContext(Dispatchers.IO) { build() }

    internal fun registerWorkspace(ref: RuntimeWorkspaceRef, makeDefault: Boolean) {
        runtimeWorkspaces[ref.workspaceId] = ref
        if (makeDefault) defaultWorkspaceId = ref.workspaceId
    }


}

internal data class PendingRuntimeHookRegistration(
    val hook: RuntimeHook,
    val matcher: ToolMatcher,
    val timeoutMillis: Long?,
    val failurePolicy: HookFailurePolicy,
) {
    fun toRegistration(defaultTimeoutMillis: Long): RuntimeHookRegistration =
        RuntimeHookRegistration(
            hook = hook,
            matcher = matcher,
            timeoutMillis = timeoutMillis ?: defaultTimeoutMillis,
            failurePolicy = failurePolicy,
        )
}

private fun normalizedPolicyConfigPath(path: String): String =
    VfsPathResolver.normalize(path)?.value
        ?: throw PolicyConfigException("Policy config path must be an absolute VFS path.")

internal const val MEMORY_WORKSPACE_DIR = "memory"
internal const val MAX_POLICY_CONFIG_BYTES = 128 * 1024
internal const val DEFAULT_WORKSPACE_ID = "default"

/** Recent conversation kept verbatim when the session is compacted, as a fraction of the window (dsh: 0.16). */
internal const val CONVERSATION_RETAIN_RATIO = 0.16

/** What a host's redactor is always followed by: secrets hidden, nothing else changed. */
internal val SECRETS_ONLY = StandardTraceRedactor(phones = false, locations = false)

@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentProfile
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.AgentPlugin
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.RuntimeContextProvider
import cc.noharry.agent.kernel.api.RuntimeEvidenceConfig
import cc.noharry.agent.kernel.api.RuntimeTurnObserver
import cc.noharry.agent.kernel.api.SessionEventLog
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelRef
import cc.noharry.agent.policy.PolicyEngine
import cc.noharry.agent.policy.PolicyRule
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsPathResolver
import cc.noharry.agent.vfs.VfsReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import cc.noharry.agent.kernel.api.ExecEngine
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.RuntimeHookRegistration
import cc.noharry.agent.kernel.api.HookFailurePolicy
import cc.noharry.agent.kernel.api.ToolMatcher
import cc.noharry.agent.kernel.core.ConcurrencyLimitedExecEngine
import cc.noharry.agent.kernel.core.ConcurrencyLimitedModelClient
import cc.noharry.agent.kernel.core.DEFAULT_MAX_ACTIVE_SESSIONS
import cc.noharry.agent.kernel.core.DEFAULT_MAX_CONCURRENT_MODEL_CALLS
import cc.noharry.agent.kernel.core.DEFAULT_MAX_CONCURRENT_TOOL_CALLS
import cc.noharry.agent.kernel.core.DEFAULT_MAX_MODEL_ROUNDS
import cc.noharry.agent.kernel.core.DEFAULT_MAX_QUEUED_RUNS_GLOBAL
import cc.noharry.agent.kernel.core.DEFAULT_MAX_QUEUED_RUNS_PER_SESSION
import cc.noharry.agent.kernel.core.DEFAULT_MAX_TOOL_CALLS_PER_RUN
import cc.noharry.agent.kernel.core.DEFAULT_MODEL_RETRY_MAX_ATTEMPTS
import cc.noharry.agent.kernel.core.DefaultTraceRedactor
import cc.noharry.agent.kernel.core.FileBackedLoopHistoryStore
import cc.noharry.agent.kernel.core.MAX_LOOP_HISTORY_BYTES
import cc.noharry.agent.kernel.core.HistoryCompactor
import cc.noharry.agent.kernel.core.InMemoryLoopHistoryStore
import cc.noharry.agent.kernel.core.LoopBudgetConfig
import cc.noharry.agent.kernel.core.LoopHistoryStore
import cc.noharry.agent.kernel.core.MaterializerContext
import cc.noharry.agent.kernel.core.ModelRetryConfig
import cc.noharry.agent.kernel.core.ProfileCompiler
import cc.noharry.agent.kernel.core.ProtocolRequestTemplate
import cc.noharry.agent.kernel.core.ProviderConcurrencyLimiter
import cc.noharry.agent.kernel.core.RuntimeCapabilityDeclarations
import cc.noharry.agent.kernel.core.ReliabilityRetentionPolicy
import cc.noharry.agent.kernel.core.ReliabilityStorage
import cc.noharry.agent.kernel.core.RuntimeBlueprint
import cc.noharry.agent.kernel.core.RuntimeCompositionMessages
import cc.noharry.agent.kernel.core.SessionRunSchedulerConfig
import cc.noharry.agent.kernel.core.SessionStore
import cc.noharry.agent.kernel.core.ToolDomainIndexer
import cc.noharry.agent.kernel.core.ToolProviderRegistry
import cc.noharry.agent.kernel.core.TraceRedactor
import cc.noharry.agent.kernel.core.TraceVisibilityConfig
import cc.noharry.agent.kernel.core.defaultSessionRunSchedulerConfig
import cc.noharry.agent.kernel.core.BootstrapPromptProvider
import cc.noharry.agent.kernel.core.BootstrapPromptResolver
import cc.noharry.agent.kernel.core.ContextBootstrapPromptResolver
import cc.noharry.agent.kernel.core.SimpleBootstrapPromptResolver
import cc.noharry.agent.kernel.core.BackendToolExposure
import cc.noharry.agent.kernel.core.ConversationCompactor
import cc.noharry.agent.kernel.core.ToolActivationStore
import cc.noharry.agent.kernel.core.validateHostSystemPrompt
import cc.noharry.agent.kernel.core.validateRunContext

class AgentRuntimeBuilder {
    companion object {
        /** Host factory. Production callsites must use this instead of the constructor. */
        @JvmStatic
        fun create(): AgentRuntimeBuilder = AgentRuntimeBuilder()
    }

    private constructor()

    private val declarations = RuntimeCapabilityDeclarations.Collector()
    private val plugins = mutableListOf<AgentPlugin>()
    private var profile: AgentProfile? = null
    private var workspaceRoot: File? = null
    private var sessionStoreFactory: ((File) -> SessionStore)? = null
    private var virtualWorkspace: VfsFileSystem? = null
    private val runtimeWorkspaces = linkedMapOf<String, RuntimeWorkspaceRef>()
    private var defaultWorkspaceId: String? = null
    private var confirmationTemplate: ConfirmationTemplate? = null
    private var protocolRequestTemplate: ProtocolRequestTemplate? = null
    private var reliabilityStorageRoot: File? = null
    private var reliabilityRetentionPolicy: ReliabilityRetentionPolicy = ReliabilityRetentionPolicy()
    private var reliabilityStorageHardeningPolicy: ReliabilityStorageHardeningPolicy =
        ReliabilityStorageHardeningPolicy()
    /** Validated host evidence config from experimental [runtimeEvidence]; null = disabled. */
    private var evidenceConfig: InternalRuntimeEvidenceConfig? = null
    private var traceVisibilityConfig: TraceVisibilityConfig = TraceVisibilityConfig()
    private var traceRedactor: TraceRedactor = DefaultTraceRedactor
    private var nowEpochMillis: () -> Long = { System.currentTimeMillis() }
    private val policyRules = mutableListOf<PolicyRule>()
    private val policyConfigJsons = mutableListOf<String>()
    private val policyConfigFiles = mutableListOf<File>()
    private val policyConfigPaths = mutableListOf<String>()
    private val hooks = mutableListOf<PendingRuntimeHookRegistration>()
    private var hookTimeoutMillis: Long = DEFAULT_HOOK_TIMEOUT_MILLIS
    private var hostSystemPrompt: String? = null
    private var runContext: String = ""
    private var loopBudget: LoopBudgetConfig = LoopBudgetConfig()
    private var contextInputTokenThreshold: Long? = null
    private var contextWindowTokens: Long? = null
    private val historyByteBudget: Int get() = if (contextInputTokenThreshold == null) MAX_LOOP_HISTORY_BYTES else Int.MAX_VALUE
    private var maxConcurrentToolCalls: Int = DEFAULT_MAX_CONCURRENT_TOOL_CALLS
    private var maxConcurrentModelCalls: Int = DEFAULT_MAX_CONCURRENT_MODEL_CALLS
    private var sessionRunSchedulerConfig: SessionRunSchedulerConfig = defaultSessionRunSchedulerConfig()
    private var modelRetryConfig: ModelRetryConfig = ModelRetryConfig()
    private var sessionEventLogRoot: File? = null
    private var agentsMdContent: String? = null
    private val sceneRules = mutableMapOf<String, String>()
    private val hostSkills = mutableListOf<HostSkill>()
    private var sessionActivationInheritance: Boolean = false
    private var sessionToolActivations: SessionToolActivations? = null
    private var sessionHistory: SessionHistory? = null
    private var subagents: Subagents? = null
    private var webSearchProvider: WebSearchProvider? = null
    private var webFetchFallback: WebFetchFallback? = null
    private var deferredBackendTools: Boolean = false
    private var nativeDeferredTools: Boolean = false
    private var runContextAsSystem: Boolean = false
    private var networkConfirmationPolicy: NetworkToolConfirmationPolicy = NetworkToolConfirmationPolicy.REQUIRE_PER_HOST
    private var nativeWebFetchEnabled: Boolean = true
    private var disabledNativeTools: Set<String> = emptySet()

    fun modelClient(client: ModelClient): AgentRuntimeBuilder = apply {
        declarations.modelClient(client)
    }

    /** Sets the immutable profile identity compiled through [ProfileCompiler]. */
    @ExperimentalAgentRuntimeApi
    fun profile(profile: AgentProfile): AgentRuntimeBuilder = apply {
        this.profile = profile
    }

    /** Installs one trusted, host-packaged plugin into the single runtime Kernel. */
    @ExperimentalAgentRuntimeApi
    fun plugin(plugin: AgentPlugin): AgentRuntimeBuilder = apply {
        plugins += plugin
    }

    /** Uses a host-supplied append-only event authority instead of the Kernel default. */
    @ExperimentalAgentRuntimeApi
    fun sessionEventLog(log: SessionEventLog): AgentRuntimeBuilder = apply {
        declarations.sessionEventLog(log)
    }

    /**
     * Configures the official kernel-owned durable EventLog root. Used only when the host does
     * not supply [sessionEventLog] and no packaged [AgentCapabilities.SESSION_EVENT_LOG] exists.
     * Business [virtualWorkspace] is never selected as the EventLog writer.
     */
    @ExperimentalAgentRuntimeApi
    fun sessionEventLogRoot(root: File): AgentRuntimeBuilder = apply {
        sessionEventLogRoot = root
    }

    /** Registers a generic [ToolProvider]. Canonical capability is [cc.noharry.agent.kernel.api.AgentCapabilities.TOOL_PROVIDER]. */
    fun toolProvider(provider: ToolProvider): AgentRuntimeBuilder = apply {
        declarations.toolProvider(provider)
    }

    /** Registers generic [ToolProvider]s. Canonical capability is [cc.noharry.agent.kernel.api.AgentCapabilities.TOOL_PROVIDER]. */
    fun toolProviders(providers: List<ToolProvider>): AgentRuntimeBuilder = apply {
        providers.forEach(declarations::toolProvider)
    }

    /**
     * Registers a candidate pre-model [RuntimeContextProvider].
     * IDs must be unique; implementations live outside `agent-runtime`.
     */
    @ExperimentalAgentRuntimeApi
    fun contextProvider(provider: RuntimeContextProvider): AgentRuntimeBuilder = apply {
        declarations.contextProvider(provider)
    }

    /**
     * Registers a candidate [RuntimeTurnObserver] for pre-model / terminal hooks.
     * IDs must be unique; implementations live outside `agent-runtime`.
     */
    @ExperimentalAgentRuntimeApi
    fun turnObserver(observer: RuntimeTurnObserver): AgentRuntimeBuilder = apply {
        declarations.turnObserver(observer)
    }

    /**
     * Keeps sessions, runs, messages and usage in the store [factory] opens under the [workspaceRoot]; without a
     * workspace root there is no session store. `agent-runtime-android` supplies an SQLite store. Candidate API.
     */
    @ExperimentalAgentRuntimeApi
    fun sessionStore(factory: (workspaceRoot: File) -> SessionStore): AgentRuntimeBuilder = apply {
        sessionStoreFactory = factory
    }

    fun workspaceRoot(root: File): AgentRuntimeBuilder = apply {
        workspaceRoot = root
        val workspace = VfsFileSystem.singleFileRoot(root)
        virtualWorkspace = workspace
        registerWorkspace(RuntimeWorkspaceRef(DEFAULT_WORKSPACE_ID, workspace), makeDefault = true)
    }

    /** Uses a host-configured semantic-free VFS workspace. Candidate API. */
    fun virtualWorkspace(workspace: VfsFileSystem): AgentRuntimeBuilder = apply {
        workspaceRoot = null
        virtualWorkspace = workspace
        registerWorkspace(RuntimeWorkspaceRef(DEFAULT_WORKSPACE_ID, workspace), makeDefault = true)
    }

    /** Registers a candidate host workspace selectable by `AgentRequest.workspaceId`. */
    fun workspace(ref: RuntimeWorkspaceRef): AgentRuntimeBuilder = apply {
        registerWorkspace(ref, makeDefault = defaultWorkspaceId == null)
    }

    /** Sets the candidate default workspace id used when a request does not select one. */
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
     * Adds a scaffold ProtocolRequest emitted during each run.
     *
     * This is a development hook for design-to-code validation, not a frozen API contract.
     */
    internal fun protocolRequestOnRun(
        kind: String,
        payloadJson: String = "{}",
        expiresInMillis: Long? = null,
    ): AgentRuntimeBuilder = apply {
        protocolRequestTemplate = ProtocolRequestTemplate(kind, payloadJson, expiresInMillis)
    }

    /**
     * Host/test injectable wall clock for referenceTime and runtime timers.
     * Required for deterministic temporal event Runtime E2E (validTime vs learnedAt).
     */
    @ExperimentalAgentRuntimeApi
    fun nowEpochMillis(clock: () -> Long): AgentRuntimeBuilder = apply {
        nowEpochMillis = clock
    }

    /** Adds a candidate host policy rule. This API is not frozen until API freeze closes. */
    fun policyRule(rule: PolicyRule): AgentRuntimeBuilder = apply {
        policyRules += rule
    }

    /** Adds a candidate host runtime hook. This API is not frozen until API freeze closes. */
    @ExperimentalAgentRuntimeApi
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

    /** Sets the candidate pre-hook timeout in milliseconds. */
    @ExperimentalAgentRuntimeApi
    fun hookTimeoutMillis(timeoutMillis: Long): AgentRuntimeBuilder = apply {
        require(timeoutMillis > 0) { "hookTimeoutMillis must be positive." }
        hookTimeoutMillis = timeoutMillis
    }

    /** Appends candidate host instructions after the internal protocol prompt. */
    fun systemPrompt(text: String): AgentRuntimeBuilder = apply {
        validateHostSystemPrompt(text)
        hostSystemPrompt = text.takeIf { it.isNotBlank() }
    }

    /**
     * Host context for this runtime's runs (a conversation recap, the previous exchange, instructions picked for the
     * request), sent as a user message right after each run's input instead of in [systemPrompt]. Keeping what changes
     * from run to run out of the system prompt lets the provider's prompt cache keep the system prompt and the tool list
     * across runs. At most 16 KiB. Candidate API.
     */
    @ExperimentalAgentRuntimeApi
    fun runContext(text: String): AgentRuntimeBuilder = apply {
        validateRunContext(text)
        runContext = text
    }

    /** Supplies an explicit in-memory `/AGENTS.md` fallback. Prefer a real workspace file. Candidate API. */
    fun agentsMd(content: String): AgentRuntimeBuilder = apply {
        agentsMdContent = content.takeIf { it.isNotBlank() }
    }

    /** Adds scene-specific runtime guidance. Candidate API; scene paths are compatibility-only. */
    fun sceneAgentsMd(scene: String, content: String): AgentRuntimeBuilder = apply {
        sceneRules[scene] = content
    }

    /** Registers a host-provided skill for progressive disclosure. Candidate API. */
    fun skill(hostSkill: HostSkill): AgentRuntimeBuilder = apply {
        hostSkills += hostSkill
    }

    /** Enables session-level tool activation inheritance (SPEC-CDR-003). Candidate API. */
    fun sessionActivationInheritance(enabled: Boolean = true): AgentRuntimeBuilder = apply {
        sessionActivationInheritance = enabled
    }

    /**
     * Keeps tool activations in [activations], which the host shares across the runtimes it builds for one session,
     * and turns on session inheritance. Candidate API.
     */
    @ExperimentalAgentRuntimeApi
    fun sessionToolActivations(activations: SessionToolActivations): AgentRuntimeBuilder = apply {
        sessionToolActivations = activations
        sessionActivationInheritance = true
    }

    /**
     * Starts each run's requests with its session's earlier runs from [history], which the host shares across the
     * runtimes it builds for one session, and adds the run to it when it ends. Candidate API.
     */
    @ExperimentalAgentRuntimeApi
    fun sessionHistory(history: SessionHistory): AgentRuntimeBuilder = apply {
        sessionHistory = history
    }

    /**
     * Offers spawn_agent / wait_agent / cancel_agent, backed by [subagents], which the host shares across the runtimes
     * it builds (children outlive runs). Needs a workspace. A child's own runtime gets the same instance, so its tool
     * list matches its parent's; [SubagentConfig.maxDepth] refuses a child's spawn. Candidate API.
     */
    @ExperimentalAgentRuntimeApi
    fun subagents(subagents: Subagents): AgentRuntimeBuilder = apply {
        this.subagents = subagents
    }

    /**
     * Offers the native web_search tool, backed by [provider] (e.g. a provider's search API): queries and results
     * reach the model and the event log, unlike a provider's server-side search, which the host should then turn
     * off. Null offers no web_search. Candidate API.
     */
    @ExperimentalAgentRuntimeApi
    fun webSearch(provider: WebSearchProvider?): AgentRuntimeBuilder = apply {
        webSearchProvider = provider
    }

    /**
     * Gets a page through [fallback] (e.g. a provider's fetch API) when web_fetch's own request was refused or failed;
     * the result says where it came from (`via`). Works with or without [webSearch]. Null keeps web_fetch to the device's
     * own requests. Candidate API.
     */
    @ExperimentalAgentRuntimeApi
    fun webFetchFallback(fallback: WebFetchFallback?): AgentRuntimeBuilder = apply {
        webFetchFallback = fallback
    }

    /**
     * Keeps backend tools out of the model's tool list, as Codex defers tools behind tool_search: search_tools results
     * and a tools-ready message for the run carry their schemas, and the model calls them through `call_tool`. The tool
     * list then stays the same for a whole session, so finding a tool mid-run no longer makes the session history miss
     * the provider's prompt cache. Hooks, policy, confirmation and events still see the real tool. Candidate API.
     */
    @ExperimentalAgentRuntimeApi
    fun deferredBackendTools(enabled: Boolean = true): AgentRuntimeBuilder = apply {
        deferredBackendTools = enabled
    }

    /**
     * Keeps the tool list stable with the provider's own mid-conversation tool changes instead of `call_tool`: backend
     * tools are declared `defer_loading` (no input cost) and loaded by `tool_addition` system messages as they are found,
     * so the model calls them natively with schema-checked arguments. Only for providers that support it, such as
     * DeepSeek's official Anthropic endpoint (gateways may drop the fields). Takes precedence over
     * [deferredBackendTools]. Candidate API.
     */
    @ExperimentalAgentRuntimeApi
    fun nativeDeferredTools(enabled: Boolean = true): AgentRuntimeBuilder = apply {
        nativeDeferredTools = enabled
    }

    /**
     * Sends [runContext] as a system message in history instead of a user message: the same prompt-cache behavior with
     * system weight. Only for providers that accept system messages after the first turn (DeepSeek; not Anthropic's own
     * API). Candidate API.
     */
    @ExperimentalAgentRuntimeApi
    fun runContextAsSystem(enabled: Boolean = true): AgentRuntimeBuilder = apply {
        runContextAsSystem = enabled
    }

    /** Sets candidate native loop budgets. This API is not frozen until API freeze closes. */
    fun loopBudget(
        maxModelRounds: Int = DEFAULT_MAX_MODEL_ROUNDS,
        maxToolCallsPerRun: Int = DEFAULT_MAX_TOOL_CALLS_PER_RUN,
        maxWallClockMillis: Long? = null,
    ): AgentRuntimeBuilder = apply {
        loopBudget = LoopBudgetConfig(maxModelRounds, maxToolCallsPerRun, maxWallClockMillis, loopBudget.imageInputEnabled)
    }

    /**
     * Candidate API: whether the model accepts image input (default true). When false, every image in a
     * request, such as a tool screenshot, becomes a text placeholder; the EventLog still keeps it.
     */
    fun imageInput(enabled: Boolean): AgentRuntimeBuilder = apply {
        loopBudget = loopBudget.copy(imageInputEnabled = enabled)
    }

    /**
     * Candidate API: uses the model's context capacity for automatic compaction (default80%).
     * Uses reported inclusive input tokens per model call, not billing totals; disables legacy
     * byte-triggered compaction/history trimming. Existing defaults remain when unset. Not frozen.
     */
    fun contextWindow(tokens: Long, compactionThresholdRatio: Double = 0.8): AgentRuntimeBuilder = apply {
        require(tokens > 0) { "Context window must be positive." }
        require(compactionThresholdRatio > 0.0 && compactionThresholdRatio <= 1.0) { "Compaction ratio must be in (0, 1]." }
        contextInputTokenThreshold = kotlin.math.ceil(tokens * compactionThresholdRatio).toLong().coerceAtLeast(1)
        contextWindowTokens = tokens
    }

    /** Sets the candidate runtime-wide cap for concurrent tool executions. Not frozen. */
    fun toolConcurrency(
        maxConcurrentCalls: Int = 4,
    ): AgentRuntimeBuilder = apply {
        require(maxConcurrentCalls > 0) { "maxConcurrentCalls must be positive." }
        maxConcurrentToolCalls = maxConcurrentCalls
    }

    /**
     * Sets the candidate runtime-wide cap for concurrent model completions, including history
     * compaction. The same wrapper is shared by the loop and [HistoryCompactor]. Not frozen.
     */
    fun modelConcurrency(
        maxConcurrentCalls: Int = 4,
    ): AgentRuntimeBuilder = apply {
        require(maxConcurrentCalls > 0) { "maxConcurrentCalls must be positive." }
        maxConcurrentModelCalls = maxConcurrentCalls
    }

    /**
     * Sets candidate process-local session/run admission budgets. Not frozen until API freeze
     * closes. All values must be positive. `maxActiveSessions = 1` is the supported rollback
     * setting. The scheduler is constructed per runtime and ordinary-run admission is wired
     * in-process; process-restored admission remains deferred.
     */
    fun sessionConcurrency(
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

    /** Sets candidate recoverable model retry and fallback behavior. Not frozen until API freeze closes. */
    fun modelRetry(
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
     * Not frozen until API freeze closes.
     */
    fun networkConfirmationPolicy(policy: NetworkToolConfirmationPolicy): AgentRuntimeBuilder = apply {
        networkConfirmationPolicy = policy
    }

    /**
     * When false, the native `web_fetch` tool is omitted from the model toolset. Hosts that
     * expose a model-visible `web_search` server tool should set this false to avoid the model
     * preferring `web_fetch` over `web_search`. Not frozen until API freeze closes.
     */
    fun nativeWebFetchEnabled(enabled: Boolean): AgentRuntimeBuilder = apply {
        nativeWebFetchEnabled = enabled
    }

    /**
     * Excludes optional native tools from model requests and direct/nested dispatch.
     * Copies the set; unknown names and protocol-required finish are rejected.
     * Candidate API, not frozen until API freeze closes. An engine is still required for exec_js.
     */
    fun disabledNativeTools(names: Set<String>): AgentRuntimeBuilder = apply {
        disabledNativeTools = cc.noharry.agent.kernel.core.snapshotDisabledNativeTools(names)
    }

    /** Registers a candidate host-provided exec engine. Not frozen until API freeze closes. */
    @ExperimentalAgentRuntimeApi
    fun execEngine(engine: ExecEngine): AgentRuntimeBuilder = apply {
        declarations.execEngine(engine)
    }

    /** Adds candidate JSON policy config text. Build fails fast when config is invalid. */
    fun policyConfigJson(json: String): AgentRuntimeBuilder = apply {
        policyConfigJsons += json
    }

    /** Adds a candidate JSON policy config file. Build fails fast when config is invalid. */
    fun policyConfig(file: File): AgentRuntimeBuilder = apply {
        policyConfigFiles += file
    }

    /** Adds a candidate JSON policy config from the configured virtual workspace. Candidate API. */
    fun policyConfigPath(path: String): AgentRuntimeBuilder = apply {
        policyConfigPaths += normalizedPolicyConfigPath(path)
    }

    internal fun traceVisibility(config: TraceVisibilityConfig): AgentRuntimeBuilder = apply {
        traceVisibilityConfig = config
    }

    internal fun traceRedactor(redactor: TraceRedactor): AgentRuntimeBuilder = apply {
        traceRedactor = redactor
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
     * Not an API freeze. Expanding beyond debug/local requires a separate owner review.
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
    fun build(): AgentRuntime {
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

    private fun buildRuntimeInfrastructure(
        capabilities: BlueprintCapabilities,
        blueprint: RuntimeBlueprint,
    ): RuntimeInfrastructure {
        traceVisibilityConfig.validate()
        val configTexts = policyConfigJsons +
            policyConfigFiles.map { it.readText() } +
            policyConfigPaths.map(::readPolicyConfigPath)
        val policyEngine = PolicyEngine(policyRules.toList() + configTexts.flatMap(PolicyConfigLoader::load))
        val providerConcurrencyLimiter = ProviderConcurrencyLimiter()
        val mcpRegistry = ToolProviderRegistry(
            providers = emptyList(),
            concurrencyLimiter = providerConcurrencyLimiter,
            blueprint = blueprint,
        )
        workspaceRoot?.let { MemoryWorkspace(File(it, MEMORY_WORKSPACE_DIR)).initialize() }
        val storage = buildStorageInfrastructure()
        return RuntimeInfrastructure(
            mcpRegistry = mcpRegistry,
            providerConcurrencyLimiter = providerConcurrencyLimiter,
            workspaceRegistry = RuntimeWorkspaceRegistry(runtimeWorkspaces.values.toList(), defaultWorkspaceId),
            hookChain = buildHookChain(policyEngine, capabilities.hooks),
            storage = storage,
            context = buildContextInfrastructure(storage.reliabilityStorage, mcpRegistry),
        )
    }

    private fun createRuntime(
        capabilities: BlueprintCapabilities,
        infrastructure: RuntimeInfrastructure,
        blueprint: RuntimeBlueprint,
    ): AgentRuntime {
        val limitedModel = capabilities.modelClient?.let { client ->
            ConcurrencyLimitedModelClient(
                delegate = client,
                limiter = infrastructure.providerConcurrencyLimiter,
                maxConcurrentModelCalls = maxConcurrentModelCalls,
            )
        }
        val limitedExec = capabilities.execEngine?.let { engine ->
            ConcurrencyLimitedExecEngine(
                delegate = engine,
                limiter = infrastructure.providerConcurrencyLimiter,
            )
        }
        return DefaultAgentRuntime(
            modelClient = limitedModel,
            mcpRegistry = infrastructure.mcpRegistry,
            workspaceRegistry = infrastructure.workspaceRegistry,
            confirmationTemplate = confirmationTemplate,
            protocolRequestTemplate = protocolRequestTemplate,
            nowEpochMillis = nowEpochMillis,
            hookChain = infrastructure.hookChain,
            traceVisibilityConfig = traceVisibilityConfig,
            traceRedactor = traceRedactor,
            reliabilityStorage = infrastructure.storage.reliabilityStorage,
            materializerSchemaCache = infrastructure.storage.schemaCache,
            bootstrapPrompt = BootstrapPromptProvider.build(hostSystemPrompt),
            promptResolver = infrastructure.context.promptResolver,
            contextRouter = infrastructure.context.contextRouter,
            todoStore = infrastructure.context.todoStore,
            memoryStore = infrastructure.context.memoryStore,
            sessionActivationInheritance = sessionActivationInheritance,
            historyCompactor = HistoryCompactor(limitedModel, inputTokenThreshold = contextInputTokenThreshold),
            loopBudget = loopBudget,
            maxConcurrentToolCalls = maxConcurrentToolCalls,
            modelRetryConfig = modelRetryConfig,
            execEngine = limitedExec,
            loopHistoryStore = infrastructure.storage.loopHistoryStore,
            sessionStore = infrastructure.storage.sessionStore,
            sessionEventLog = capabilities.eventLog,
            networkConfirmationPolicy = networkConfirmationPolicy,
            nativeWebFetchEnabled = nativeWebFetchEnabled,
            disabledNativeTools = disabledNativeTools,
            reliabilityStorageRoot = reliabilityStorageRoot,
            // Packs come only from validated evidence allowlist (string ids), never host-supplied ProbePack.
            probePacks = evidenceConfig?.takeIf { it.probesEnabled }?.resolvePacks() ?: emptyList(),
            evidenceConfig = evidenceConfig,
            contextProviders = capabilities.contextProviders,
            turnObservers = capabilities.turnObservers,
            blueprint = blueprint,
            sessionRunSchedulerConfig = sessionRunSchedulerConfig,
            activationStore = sessionToolActivations?.store ?: ToolActivationStore(nowEpochMillis = nowEpochMillis),
            runContext = runContext,
            sessionHistory = sessionHistory?.store,
            backendToolExposure = when {
                nativeDeferredTools -> BackendToolExposure.NATIVE_DEFERRED
                deferredBackendTools -> BackendToolExposure.CALL_TOOL
                else -> BackendToolExposure.DIRECT
            },
            runContextAsSystem = runContextAsSystem,
            conversationCompactor = conversationCompactor(limitedModel),
            subagents = subagents,
            webSearchProvider = webSearchProvider,
            webFetchFallback = webFetchFallback,
        )
    }

    /**
     * With session history and a context window, the session's oldest runs are summarized when a request reaches the
     * compaction threshold; the newest 16% of the window (as dsh keeps) stays as it was.
     */
    private fun conversationCompactor(model: ModelClient?): ConversationCompactor? {
        val threshold = contextInputTokenThreshold ?: return null
        val window = contextWindowTokens ?: return null
        if (sessionHistory == null) return null
        val retain = (window * CONVERSATION_RETAIN_RATIO).toLong().coerceAtMost(threshold - 1)
        return ConversationCompactor(model, threshold, retain, nowEpochMillis)
    }

    /** Candidate API: builds without configuration or storage I/O on the caller context. Not frozen. */
    suspend fun buildAsync(): AgentRuntime = withContext(Dispatchers.IO) { build() }

    private fun buildStorageInfrastructure(): StorageInfrastructure =
        StorageInfrastructure(
            reliabilityStorage = reliabilityStorageRoot?.let {
                FileBackedReliabilityStorage(it, reliabilityRetentionPolicy, reliabilityStorageHardeningPolicy)
            },
            schemaCache = reliabilityStorageRoot?.let(::FileBackedMaterializerSchemaCache),
            loopHistoryStore = reliabilityStorageRoot?.let { FileBackedLoopHistoryStore(it, historyByteBudget) }
                ?: InMemoryLoopHistoryStore(historyByteBudget),
            sessionStore = workspaceRoot?.let { root -> sessionStoreFactory?.invoke(root) },
        )

    private fun buildHookChain(
        policyEngine: PolicyEngine,
        pluginHooks: List<RuntimeHook>,
    ): RuntimeHookChain =
        RuntimeHookChain(
            listOf(
                RuntimeHookRegistration(
                    hook = PolicyHookAdapter(policyEngine),
                    timeoutMillis = hookTimeoutMillis,
                    failurePolicy = HookFailurePolicy.FAIL_CLOSED,
                ),
            ) +
                hooks.map { it.toRegistration(hookTimeoutMillis) } +
                pluginHooks.map {
                    RuntimeHookRegistration(
                        hook = it,
                        timeoutMillis = hookTimeoutMillis,
                        failurePolicy = HookFailurePolicy.FAIL_CLOSED,
                    )
                },
        )

    private fun buildContextInfrastructure(
        reliabilityStorage: ReliabilityStorage?,
        mcpRegistry: ToolProviderRegistry,
    ): ContextInfrastructure {
        val memoryDir = workspaceRoot?.let { File(it, MEMORY_WORKSPACE_DIR) }
        val todoStore = memoryDir?.let(::TodoStore)
        val memoryStore = memoryDir?.let { RuntimeMemoryStore(it, nowEpochMillis) }
            ?: virtualWorkspace?.let { RuntimeMemoryStore(it, nowEpochMillis) }
        val skillRegistry = SkillRegistry(hostSkills.toList(), virtualWorkspace)
        val contextRouter = ContextMaterializerRouter(
            storage = reliabilityStorage,
            memoryStore = memoryStore,
            todoStore = todoStore,
            skillRegistry = skillRegistry,
            agentsMdContent = agentsMdContent,
        )
        val promptResolver = if (shouldUseContextPrompt(todoStore)) {
            ContextBootstrapPromptResolver(
                hostSystemPrompt,
                skillRegistry,
                sceneRules = sceneRules.keys,
                toolDomainIndexProvider = {
                    val tools = mcpRegistry.allTools(MaterializerContext())
                    ToolDomainIndexer.index(tools)
                },
            )
        } else {
            SimpleBootstrapPromptResolver(BootstrapPromptProvider.build(hostSystemPrompt))
        }
        return ContextInfrastructure(todoStore, memoryStore, contextRouter, promptResolver)
    }

    private fun shouldUseContextPrompt(todoStore: TodoStore?): Boolean =
        todoStore != null ||
            virtualWorkspace != null ||
            agentsMdContent != null ||
            hostSkills.isNotEmpty()

    private fun readPolicyConfigPath(path: String): String {
        val workspace = virtualWorkspace ?: throw PolicyConfigException(
            "policyConfigPath requires virtualWorkspace(...).",
        )
        return when (val result = runBlocking { workspace.read(path, 0, MAX_POLICY_CONFIG_BYTES) }) {
            is VfsReadResult.Found -> result.text
            is VfsReadResult.Error -> throw PolicyConfigException(result.message)
            VfsReadResult.BinaryUnsupported -> throw PolicyConfigException("Policy config must be text.")
            VfsReadResult.InvalidPath -> throw PolicyConfigException("Invalid policy config path: $path")
            VfsReadResult.NotFound -> throw PolicyConfigException("Policy config not found: $path")
            VfsReadResult.ReadDenied -> throw PolicyConfigException("Policy config read denied: $path")
        }
    }

    private fun registerWorkspace(ref: RuntimeWorkspaceRef, makeDefault: Boolean) {
        runtimeWorkspaces[ref.workspaceId] = ref
        if (makeDefault) defaultWorkspaceId = ref.workspaceId
    }

    private data class ContextInfrastructure(
        val todoStore: TodoStore?,
        val memoryStore: RuntimeMemoryStore?,
        val contextRouter: ContextMaterializerRouter,
        val promptResolver: BootstrapPromptResolver,
    )

    private data class StorageInfrastructure(
        val reliabilityStorage: ReliabilityStorage?,
        val schemaCache: MaterializerSchemaCacheStore?,
        val loopHistoryStore: LoopHistoryStore,
        val sessionStore: SessionStore?,
    )

    private data class RuntimeInfrastructure(
        val mcpRegistry: ToolProviderRegistry,
        val providerConcurrencyLimiter: ProviderConcurrencyLimiter,
        val workspaceRegistry: RuntimeWorkspaceRegistry,
        val hookChain: RuntimeHookChain,
        val storage: StorageInfrastructure,
        val context: ContextInfrastructure,
    )
}

private data class PendingRuntimeHookRegistration(
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

private const val MEMORY_WORKSPACE_DIR = "memory"
private const val MAX_POLICY_CONFIG_BYTES = 128 * 1024
private const val DEFAULT_WORKSPACE_ID = "default"

/** Recent conversation kept verbatim when the session is compacted, as a fraction of the window (dsh: 0.16). */
private const val CONVERSATION_RETAIN_RATIO = 0.16

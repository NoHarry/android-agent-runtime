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

internal fun AgentRuntimeBuilder.buildRuntimeInfrastructure(
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

internal fun AgentRuntimeBuilder.createRuntime(
    capabilities: BlueprintCapabilities,
    infrastructure: RuntimeInfrastructure,
    blueprint: RuntimeBlueprint,
): EngineRuntime {
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
        nowEpochMillis = nowEpochMillis,
        hookChain = infrastructure.hookChain,
        traceVisibilityConfig = traceVisibilityConfig,
        traceRedactor = traceRedactor,
        reliabilityStorage = infrastructure.storage.reliabilityStorage,
        materializerSchemaCache = infrastructure.storage.schemaCache,
        bootstrapPrompt = BootstrapPromptProvider.build(hostSystemPrompt, tools = protocolTools(), protocolRules = protocolRules),
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
        disabledNativeTools = offeredNativeToolsExcluded(),
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
        loopSettings = loopSettings,
    )
}

/**
 * With session history and a context window, the session's oldest runs are summarized when a request reaches the
 * compaction threshold; the newest 16% of the window (as dsh keeps) stays as it was.
 */
internal fun AgentRuntimeBuilder.conversationCompactor(model: ModelClient?): ConversationCompactor? {
    val threshold = contextInputTokenThreshold ?: return null
    val window = contextWindowTokens ?: return null
    if (sessionHistory == null) return null
    val retain = (window * CONVERSATION_RETAIN_RATIO).toLong().coerceAtMost(threshold - 1)
    return ConversationCompactor(model, threshold, retain, nowEpochMillis)
}

internal fun AgentRuntimeBuilder.buildStorageInfrastructure(): StorageInfrastructure =
    StorageInfrastructure(
        reliabilityStorage = reliabilityStorageRoot?.let {
            FileBackedReliabilityStorage(it, reliabilityRetentionPolicy, reliabilityStorageHardeningPolicy)
        },
        schemaCache = reliabilityStorageRoot?.let(::FileBackedMaterializerSchemaCache),
        loopHistoryStore = reliabilityStorageRoot?.let { FileBackedLoopHistoryStore(it, historyByteBudget) }
            ?: InMemoryLoopHistoryStore(historyByteBudget),
        sessionStore = workspaceRoot?.let { root -> sessionStoreFactory?.invoke(root) },
    )

internal fun AgentRuntimeBuilder.buildHookChain(
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

internal fun AgentRuntimeBuilder.buildContextInfrastructure(
    reliabilityStorage: ReliabilityStorage?,
    mcpRegistry: ToolProviderRegistry,
): ContextInfrastructure {
    val memoryDir = workspaceRoot?.let { File(it, MEMORY_WORKSPACE_DIR) }
    val todoStore = memoryDir?.let(::TodoStore)
    val memoryStore = if (lessons) {
        memoryDir?.let { RuntimeMemoryStore(it, nowEpochMillis) } ?: virtualWorkspace?.let { RuntimeMemoryStore(it, nowEpochMillis) }
    } else {
        null
    }
    val skillRegistry = SkillRegistry(hostSkills.toList(), virtualWorkspace)
    val contextRouter = ContextMaterializerRouter(
        storage = reliabilityStorage,
        memoryStore = memoryStore,
        todoStore = todoStore,
        skillRegistry = skillRegistry,
        agentsMdContent = agentsMdContent,
    )
    val promptResolver = promptResolver(todoStore, skillRegistry, mcpRegistry)
    return ContextInfrastructure(todoStore, memoryStore, contextRouter, promptResolver)
}

internal fun AgentRuntimeBuilder.promptResolver(
    todoStore: TodoStore?,
    skillRegistry: SkillRegistry,
    mcpRegistry: ToolProviderRegistry,
): BootstrapPromptResolver {
    val tools = protocolTools()
    val sceneGuidance = sceneRules.toMap()
    val files = runtimeWorkspaces.isNotEmpty()
    return when {
        shouldUseContextPrompt(todoStore) -> ContextBootstrapPromptResolver(
            hostSystemPrompt,
            // Without a workspace there is no read_file: no context paths to read and no skill files to open.
            skillRegistry.takeIf { files },
            contextCatalog = if (files) defaultContextCatalog() else ContextCatalog(emptyList()),
            toolDomainIndexProvider = {
                ToolDomainIndexer.index(mcpRegistry.allTools(MaterializerContext()), toolDomains)
            },
            tools = tools,
            protocolRules = protocolRules,
            sceneGuidance = sceneGuidance,
        )
        // Without a workspace the prompt is the same every round, except for a scene that has its own rules.
        sceneGuidance.isNotEmpty() -> BootstrapPromptResolver { scene, _ ->
            BootstrapPromptProvider.build(
                hostSystemPrompt,
                scene = scene?.takeIf { it in sceneGuidance },
                tools = tools,
                protocolRules = protocolRules,
                sceneGuidance = sceneGuidance,
            )
        }
        else -> SimpleBootstrapPromptResolver(BootstrapPromptProvider.build(hostSystemPrompt, tools = tools, protocolRules = protocolRules))
    }
}

/** The native tools this runtime offers, for the protocol prompt. */
internal fun AgentRuntimeBuilder.protocolTools() = ProtocolTools(
    webFetch = nativeWebFetchEnabled && "web_fetch" !in disabledNativeTools,
    webSearch = webSearchProvider != null && "web_search" !in disabledNativeTools,
    disabled = offeredNativeToolsExcluded(),
)

internal fun AgentRuntimeBuilder.shouldUseContextPrompt(todoStore: TodoStore?): Boolean =
    todoStore != null ||
        virtualWorkspace != null ||
        agentsMdContent != null ||
        hostSkills.isNotEmpty()

internal fun AgentRuntimeBuilder.readPolicyConfigPath(path: String): String {
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

/** The native tools left out: the host's, and the file tools when the runtime has no workspace for them. */
internal fun AgentRuntimeBuilder.offeredNativeToolsExcluded(): Set<String> =
    if (runtimeWorkspaces.isEmpty()) disabledNativeTools + (WORKSPACE_TOOL_NAMES - "search_tools") else disabledNativeTools


internal data class ContextInfrastructure(
    val todoStore: TodoStore?,
    val memoryStore: RuntimeMemoryStore?,
    val contextRouter: ContextMaterializerRouter,
    val promptResolver: BootstrapPromptResolver,
)

internal data class StorageInfrastructure(
    val reliabilityStorage: ReliabilityStorage?,
    val schemaCache: MaterializerSchemaCacheStore?,
    val loopHistoryStore: LoopHistoryStore,
    val sessionStore: SessionStore?,
)

internal data class RuntimeInfrastructure(
    val mcpRegistry: ToolProviderRegistry,
    val providerConcurrencyLimiter: ProviderConcurrencyLimiter,
    val workspaceRegistry: RuntimeWorkspaceRegistry,
    val hookChain: RuntimeHookChain,
    val storage: StorageInfrastructure,
    val context: ContextInfrastructure,
)

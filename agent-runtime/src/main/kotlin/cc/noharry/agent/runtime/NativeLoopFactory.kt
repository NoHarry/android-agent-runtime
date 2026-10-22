@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ProbeEmitterRegistration
import cc.noharry.agent.kernel.api.RuntimeContextProvider
import cc.noharry.agent.kernel.api.RuntimeProbeEmitterHub
import cc.noharry.agent.kernel.api.RuntimeTurnObserver
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeHandoff
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.runtime.probe.ProbeRunAttachment
import cc.noharry.agent.vfs.VfsFileSystem
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import cc.noharry.agent.kernel.api.ExecEngine
import cc.noharry.agent.kernel.core.BackendToolExposure
import cc.noharry.agent.kernel.core.BootstrapPrompt
import cc.noharry.agent.kernel.core.BootstrapPromptResolver
import cc.noharry.agent.kernel.core.HistoryCompactor
import cc.noharry.agent.kernel.core.LoopBudgetConfig
import cc.noharry.agent.kernel.core.LoopHistoryStore
import cc.noharry.agent.kernel.core.ModelRetryConfig
import cc.noharry.agent.kernel.core.NativeLoopOutcome
import cc.noharry.agent.kernel.core.NativeLoopSummary
import cc.noharry.agent.kernel.core.NativeSubagentType
import cc.noharry.agent.kernel.core.NativeToolLoop
import cc.noharry.agent.kernel.core.PendingNativeToolUse
import cc.noharry.agent.kernel.core.RuntimeSteeringQueue
import cc.noharry.agent.kernel.core.ConversationCompactor
import cc.noharry.agent.kernel.core.SessionHistoryStore
import cc.noharry.agent.kernel.core.ToolActivationStore
import cc.noharry.agent.kernel.core.ToolExecutionLimiter
import cc.noharry.agent.kernel.core.ToolProviderRegistry
import cc.noharry.agent.kernel.core.TraceRedactor
import cc.noharry.agent.kernel.core.failed
import cc.noharry.agent.kernel.core.RuntimeMcpDebugTraceSink

internal class NativeLoopFactory(
    private val modelClient: ModelClient?,
    private val mcpRegistry: ToolProviderRegistry,
    private val materializerSchemaCache: MaterializerSchemaCacheStore?,
    private val traceRedactor: TraceRedactor,
    private val hookChain: RuntimeHookChain,
    private val bootstrapPrompt: BootstrapPrompt,
    private val promptResolver: BootstrapPromptResolver,
    private val contextRouter: ContextMaterializerRouter?,
    private val loopBudget: LoopBudgetConfig,
    private val toolExecutionLimiter: ToolExecutionLimiter = ToolExecutionLimiter(),
    private val loopHistoryStore: LoopHistoryStore,
    private val activationStore: ToolActivationStore,
    private val nowEpochMillis: () -> Long,
    private val mcpDebugTraceSink: RuntimeMcpDebugTraceSink = RuntimeMcpDebugTraceSink.None,
    private val todoStore: TodoStore? = null,
    private val memoryStore: RuntimeMemoryStore? = null,
    private val historyCompactor: HistoryCompactor? = null,
    private val sessionRecorder: RuntimeSessionRecorder = RuntimeSessionRecorder(null, nowEpochMillis = nowEpochMillis),
    private val handoffProvider: suspend (String) -> RuntimeHandoff? = { null },
    private val modelRetryConfig: ModelRetryConfig = ModelRetryConfig(),
    private val execEngine: ExecEngine? = null,
    private val networkToolBackend: NetworkToolBackend = NetworkToolBackend(),
    private val nativeWebFetchEnabled: Boolean = true,
    private val disabledNativeTools: Set<String> = emptySet(),
    private val steeringQueue: RuntimeSteeringQueue = RuntimeSteeringQueue(nowEpochMillis),
    private val probeAttachmentProvider: (AgentRequest, String) -> ProbeRunAttachment =
        { _, runId -> ProbeRunAttachment.disabled(runId) },
    private val contextProviders: List<RuntimeContextProvider> = emptyList(),
    private val turnObservers: List<RuntimeTurnObserver> = emptyList(),
    private val runContext: String = "",
    private val sessionHistory: SessionHistoryStore? = null,
    private val backendToolExposure: BackendToolExposure = BackendToolExposure.DIRECT,
    private val runContextAsSystem: Boolean = false,
    private val conversationCompactor: ConversationCompactor? = null,
    private val subagents: Subagents? = null,
    private val nativeWebSearchEnabled: Boolean = false,
) {
    /** Unique per Runtime instance for Probe hub isolation. */
    val runtimeInstanceId: String = UUID.randomUUID().toString()

    /** Survives confirmation / user-input pause until terminal finishAndPersist. */
    private val probeAttachments = ConcurrentHashMap<String, ProbeRunAttachment>()
    private val probeRegistrations = ConcurrentHashMap<String, ProbeEmitterRegistration>()
    private val workspaces = ConcurrentHashMap<String, VfsFileSystem>()
    private val discoveries = ConcurrentHashMap<String, cc.noharry.agent.kernel.core.OnDemandToolDiscovery>()

    suspend fun run(
        request: AgentRequest,
        runId: String,
        workspaceFileSystem: VfsFileSystem?,
        emitPublicEvent: suspend (AgentEvent) -> Unit,
    ): NativeLoopOutcome {
        val probe = try {
            probeAttachmentProvider(request, runId)
        } catch (_: Exception) {
            ProbeRunAttachment.disabled(runId)
        }
        probeAttachments[runId] = probe
        if (workspaceFileSystem != null) {
            workspaces[runId] = workspaceFileSystem
        }
        val cleaned = AtomicBoolean(false)
        suspend fun cleanupOnce(status: String) {
            if (!cleaned.compareAndSet(false, true)) return
            finishProbeRun(runId, status)
        }
        // Terminal probe finalize for successful control flow is owned by DefaultAgentRuntime
        // after observer notify. Unexpected throw/cancel must still release resources once.
        return try {
            loop(runId, workspaceFileSystem, probeAttachment = probe, childRun = subagents?.isSubagentSession(request.sessionId) == true)
                .run(request, runId, emitPublicEvent)
        } catch (cancelled: CancellationException) {
            cleanupOnce("CANCELLED")
            throw cancelled
        } catch (error: Exception) {
            cleanupOnce("FAILED")
            NativeLoopOutcome.Terminal(
                RuntimeResult(
                    runId = runId,
                    status = RuntimeRunStatus.FAILED,
                    // The cause in one short line: without it a failure seen on someone else's device cannot be told
                    // from any other (owner 2026-10-12, a screenshot of two such failures).
                    response = "Native loop failed: ${loopFailureCause(error)}",
                    errorCode = RuntimeErrorCode.MODEL_LOOP_FAILED.wireCode,
                    recoverable = false,
                    completion = RuntimeCompletionSummary(outcome = RuntimeCompletionOutcome.FAILED),
                ),
                NativeLoopSummary(),
            )
        }
    }

    suspend fun resumeConfirmedToolUse(
        pending: PendingNativeToolUse,
        emitPublicEvent: suspend (AgentEvent) -> Unit = {},
    ): NativeLoopOutcome {
        val runId = pending.context.runId
        val probe = probeAttachments[runId] ?: ProbeRunAttachment.disabled(runId)
        return loop(
            runId,
            workspaceFileSystem = workspaces[runId],
            probeAttachment = probe,
        ).resumeConfirmedToolUse(pending, emitPublicEvent)
    }

    suspend fun resumeUserInput(
        runId: String,
        sessionId: String,
        requestId: String,
        answer: String,
        scene: String?,
        modelRef: cc.noharry.agent.kernel.api.model.ModelRef?,
        currentStateKeys: Set<String> = emptySet(),
        workspaceId: String? = null,
        workspaceFileSystem: VfsFileSystem? = null,
        emitPublicEvent: suspend (AgentEvent) -> Unit = {},
    ): NativeLoopOutcome {
        val probe = probeAttachments[runId] ?: ProbeRunAttachment.disabled(runId)
        return loop(runId, workspaceFileSystem, probeAttachment = probe)
            .resumeUserInput(
                runId,
                sessionId,
                requestId,
                answer,
                scene,
                modelRef,
                currentStateKeys,
                workspaceId,
                emitPublicEvent,
            )
    }

    /**
     * Persist exactly-once CANCELLED probe evidence for paused/in-flight leftovers.
     * Already-finalized runs are absent from the map and are never rewritten
     * ([ProbeRunAttachment.finishAndPersist] is additionally CAS-idempotent).
     * Call before [closeAll] drains Hub/maps so close evidence exists before resources go away.
     */
    fun persistRemainingProbes(status: String = RuntimeRunStatus.CANCELLED.name) {
        val remaining = probeAttachments.keys.toList()
        for (runId in remaining) {
            runBlocking { finishProbeRun(runId, status) }
        }
    }

    /**
     * Close/abort cleanup: persist remaining paused/in-flight probes as CANCELLED (never
     * rewrite an already-finalized status to ABORTED), uninstall Hub entries for this
     * Runtime only (compare-and-remove), clear maps.
     *
     * Only paused/in-flight runs (still in the map) are finished with ABORTED; runs already
     * terminalized by [finishProbeRun] are absent from the map, so their status is never
     * rewritten (ProbeRunAttachment.finishAndPersist is additionally CAS-idempotent).
     */
    fun closeAll() {
        persistRemainingProbes(RuntimeRunStatus.CANCELLED.name)
        RuntimeProbeEmitterHub.uninstallAllForRuntime(runtimeInstanceId)
        probeRegistrations.clear()
        probeAttachments.clear()
        workspaces.clear()
        discoveries.clear()
    }

    /**
     * Exact runtime-scoped terminal finalizer. Idempotent: second call for the same runId is a
     * no-op (CAS on ProbeRunAttachment). Order for hosts: notify turn observers first, then this
     * (finishAndPersist + hub uninstall + map cleanup). [incompleteReason] records an explicit
     * incomplete cause in run-meta/probe evidence when observers could not all complete.
     */
    internal suspend fun finishProbeRun(
        runId: String,
        status: String,
        incompleteReason: String? = null,
    ) {
        val probe = probeAttachments.remove(runId)
        val reg = probeRegistrations.remove(runId)
        workspaces.remove(runId)
        discoveries.remove(runId)
        if (reg != null) {
            try {
                RuntimeProbeEmitterHub.uninstall(reg)
            } catch (_: Exception) {
            }
        }
        if (probe != null) {
            try {
                // Idempotent on ProbeRunAttachment (exactly-once terminal drain).
                probe.finishAndPersist(status, incompleteReason)
            } catch (_: Exception) {
            }
        }
    }

    internal fun hasProbeAttachment(runId: String): Boolean = probeAttachments.containsKey(runId)

    internal fun hasProbeRegistration(runId: String): Boolean = probeRegistrations.containsKey(runId)


    private fun loop(
        runId: String,
        workspaceFileSystem: VfsFileSystem? = null,
        probeAttachment: ProbeRunAttachment = ProbeRunAttachment.disabled(runId),
        childRun: Boolean = false,
    ): NativeToolLoop =
        NativeToolLoop(
            modelClient = modelClient,
            mcpRegistry = mcpRegistry,
            workspace = workspaceFileSystem?.let {
                VirtualWorkspace(
                    it,
                    materializer(),
                    mcpRegistry = mcpRegistry,
                    activationStore = activationStore,
                    runId = runId,
                    sessionRecorder = sessionRecorder,
                    onDemandDiscovery = discoveries.getOrPut(runId) { cc.noharry.agent.kernel.core.OnDemandToolDiscovery() },
                    deferBackendTools = backendToolExposure == BackendToolExposure.CALL_TOOL,
                    subagentTools = subagents?.let { it::execute },
                )
            },
            traceRedactor = traceRedactor,
            hookChain = hookChain,
            bootstrapPrompt = bootstrapPrompt,
            promptResolver = promptResolver,
            loopBudget = loopBudget,
            toolExecutionLimiter = toolExecutionLimiter,
            loopHistoryStore = loopHistoryStore,
            nowEpochMillis = nowEpochMillis,
            activationStore = activationStore,
            mcpDebugTraceSink = mcpDebugTraceSink,
            todoStore = todoStore,
            memoryStore = memoryStore,
            historyCompactor = historyCompactor,
            sessionRecorder = sessionRecorder,
            handoffProvider = handoffProvider,
            modelRetryConfig = modelRetryConfig,
            execEngine = execEngine,
            networkToolBackend = networkToolBackend,
            nativeWebFetchEnabled = nativeWebFetchEnabled,
            disabledNativeTools = disabledNativeTools,
            steeringQueue = steeringQueue,
            probeSink = ProbeSessionLoopProbeSink(probeAttachment.session),
            contextProviders = contextProviders,
            turnObservers = turnObservers,
            runtimeInstanceId = runtimeInstanceId,
            runContext = runContext,
            sessionHistory = sessionHistory,
            backendToolExposure = backendToolExposure,
            runContextAsSystem = runContextAsSystem,
            conversationCompactor = conversationCompactor,
            subagentTools = subagents != null && workspaceFileSystem != null,
            subagentTypes = subagents?.config?.types.orEmpty().map { NativeSubagentType(it.name, it.description) },
            textNeedsFinish = childRun,
            nativeWebSearchEnabled = nativeWebSearchEnabled,
        )

    private fun materializer(): VirtualFileMaterializer =
        VirtualFileMaterializer(
            mcpRegistry = mcpRegistry,
            activationStore = activationStore,
            nowEpochMillis = nowEpochMillis,
            schemaCacheStore = materializerSchemaCache,
            contextRouter = contextRouter,
        )
}

/** An unexpected loop failure as "Type: message", on one line and at most [MAX_LOOP_FAILURE_CHARS], for the result. */
private const val MAX_CAUSE_DEPTH = 16

internal fun loopFailureCause(error: Throwable): String {
    // Bounded: initCause lets a chain loop back on itself (A caused by B caused by A).
    val root = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSE_DEPTH).last()
    val message = root.message.orEmpty().replace(WHITESPACE, " ").trim()
    return "${root.javaClass.simpleName}${if (message.isEmpty()) "" else ": $message"}".take(MAX_LOOP_FAILURE_CHARS)
}

private const val MAX_LOOP_FAILURE_CHARS = 160
private val WHITESPACE = Regex("""\s+""")

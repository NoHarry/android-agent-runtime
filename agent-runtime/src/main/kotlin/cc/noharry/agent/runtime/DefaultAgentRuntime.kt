@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.spi.AgentEvent
import cc.noharry.agent.spi.AgentRequest
import cc.noharry.agent.spi.AgentRuntime
import cc.noharry.agent.spi.CancelPendingConfirmationRequest
import cc.noharry.agent.spi.CancelPendingResult
import cc.noharry.agent.spi.ConfirmationChoice
import cc.noharry.agent.spi.ConfirmationDecision
import cc.noharry.agent.spi.ConfirmationResumeResult
import cc.noharry.agent.spi.ExecEngine
import cc.noharry.agent.spi.InternalAgentRuntimeApi
import cc.noharry.agent.spi.PendingConfirmation
import cc.noharry.agent.spi.PostUserInputRequest
import cc.noharry.agent.spi.PostUserInputResult
import cc.noharry.agent.spi.RuntimeContextProvider
import cc.noharry.agent.spi.RuntimeTurnObserver
import cc.noharry.agent.spi.SessionCapabilityContext
import cc.noharry.agent.spi.SessionEventLog
import cc.noharry.agent.spi.SessionEventQuery
import cc.noharry.agent.spi.SessionRunConflictPolicy
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.reliability.RuntimeCompletionSummary
import cc.noharry.agent.spi.reliability.RuntimeErrorCode
import cc.noharry.agent.spi.reliability.RuntimeResult
import cc.noharry.agent.spi.reliability.RuntimeRunSnapshot
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.context.BootstrapPrompt
import cc.noharry.agent.kernel.core.context.BootstrapPromptProvider
import cc.noharry.agent.kernel.core.context.BootstrapPromptResolver
import cc.noharry.agent.kernel.core.context.ConversationCompactor
import cc.noharry.agent.kernel.core.context.HistoryCompactor
import cc.noharry.agent.kernel.core.context.SessionHistoryStore
import cc.noharry.agent.kernel.core.context.SimpleBootstrapPromptResolver
import cc.noharry.agent.kernel.core.loop.CONFIRMATION_CANCELLED_CODE
import cc.noharry.agent.kernel.core.loop.LoopBudgetConfig
import cc.noharry.agent.runtime.LoopSettings
import cc.noharry.agent.kernel.core.loop.ModelRetryConfig
import cc.noharry.agent.kernel.core.loop.NativeLoopOutcome
import cc.noharry.agent.kernel.core.loop.NativeLoopSummary
import cc.noharry.agent.kernel.core.loop.RuntimeObligationLedger
import cc.noharry.agent.kernel.core.loop.RuntimeSteeringQueue
import cc.noharry.agent.kernel.core.memory.extractMemoryCandidates
import cc.noharry.agent.kernel.core.memory.lessonMode
import cc.noharry.agent.kernel.core.plugin.RuntimeBlueprint
import cc.noharry.agent.kernel.core.plugin.closeSafely
import cc.noharry.agent.kernel.core.state.InMemoryConfirmationStore
import cc.noharry.agent.kernel.core.state.InMemoryLoopHistoryStore
import cc.noharry.agent.kernel.core.state.LoopHistoryStore
import cc.noharry.agent.kernel.core.state.PendingToolUseController
import cc.noharry.agent.kernel.core.state.ReliabilityStorage
import cc.noharry.agent.kernel.core.state.ResumeFirstController
import cc.noharry.agent.kernel.core.state.RunStateController
import cc.noharry.agent.kernel.core.state.RunStateLockRegistry
import cc.noharry.agent.kernel.core.state.RuntimeStateRetentionPolicy
import cc.noharry.agent.kernel.core.state.SessionRunAdmission
import cc.noharry.agent.kernel.core.state.SessionRunLease
import cc.noharry.agent.kernel.core.state.SessionRunScheduler
import cc.noharry.agent.kernel.core.state.SessionRunSchedulerConfig
import cc.noharry.agent.kernel.core.state.SessionRunSchedulerSnapshot
import cc.noharry.agent.kernel.core.state.SessionStore
import cc.noharry.agent.kernel.core.state.defaultSessionRunSchedulerConfig
import cc.noharry.agent.kernel.core.tools.BackendToolExposure
import cc.noharry.agent.kernel.core.tools.DEFAULT_MAX_CONCURRENT_TOOL_CALLS
import cc.noharry.agent.kernel.core.tools.RuntimeMcpDebugTraceSink
import cc.noharry.agent.kernel.core.tools.ToolActivationStore
import cc.noharry.agent.kernel.core.tools.ToolExecutionLimiter
import cc.noharry.agent.kernel.core.tools.ToolProviderRegistry
import cc.noharry.agent.runtime.DebugRawTraceAccessDecision
import cc.noharry.agent.runtime.RedactedTraceSummary
import cc.noharry.agent.runtime.TraceRedactor
import cc.noharry.agent.runtime.TraceSummaryBuilder
import cc.noharry.agent.runtime.TraceVisibilityConfig
import cc.noharry.agent.kernel.core.trace.redactedForPublic
import cc.noharry.agent.runtime.probe.ProbePack
import cc.noharry.agent.runtime.reliability.InternalRuntimeEvidenceConfig
import cc.noharry.agent.runtime.run.NativeLoopFactory
import cc.noharry.agent.runtime.run.RuntimeControlResumeCoordinator
import cc.noharry.agent.runtime.run.RuntimeHookChain
import cc.noharry.agent.runtime.run.RuntimeMemoryStore
import cc.noharry.agent.runtime.run.RuntimeSessionAdmissionCoordinator
import cc.noharry.agent.runtime.run.RuntimeSessionRecorder
import cc.noharry.agent.runtime.run.RuntimeTerminalCoordinator
import cc.noharry.agent.runtime.run.TodoStore
import cc.noharry.agent.runtime.run.UserInputResumeController
import cc.noharry.agent.runtime.workspace.ContextMaterializerRouter
import cc.noharry.agent.runtime.workspace.MaterializerSchemaCacheStore
import cc.noharry.agent.runtime.workspace.VirtualWorkspace
import cc.noharry.agent.vfs.VfsFileSystem
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal class DefaultAgentRuntime(
    private val modelClient: ModelClient?,
    private val mcpRegistry: ToolProviderRegistry,
    private val workspaceRegistry: RuntimeWorkspaceRegistry = RuntimeWorkspaceRegistry(emptyList(), null),
    private val confirmationTemplate: ConfirmationTemplate?,
    private val nowEpochMillis: () -> Long,
    private val hookChain: RuntimeHookChain,
    private val traceVisibilityConfig: TraceVisibilityConfig,
    private val traceRedactor: TraceRedactor,
    reliabilityStorage: ReliabilityStorage? = null,
    private val materializerSchemaCache: MaterializerSchemaCacheStore? = null,
    bootstrapPrompt: BootstrapPrompt = BootstrapPromptProvider.build(),
    private val promptResolver: BootstrapPromptResolver = SimpleBootstrapPromptResolver(bootstrapPrompt),
    private val contextRouter: ContextMaterializerRouter? = null,
    private val todoStore: TodoStore? = null,
    private val memoryStore: RuntimeMemoryStore? = null,
    private val sessionActivationInheritance: Boolean = false,
    private val historyCompactor: HistoryCompactor? = null,
    loopBudget: LoopBudgetConfig = LoopBudgetConfig(),
    maxConcurrentToolCalls: Int = DEFAULT_MAX_CONCURRENT_TOOL_CALLS,
    modelRetryConfig: ModelRetryConfig = ModelRetryConfig(),
    execEngine: ExecEngine? = null,
    private val loopHistoryStore: LoopHistoryStore = InMemoryLoopHistoryStore(),
    sessionStore: SessionStore? = null,
    private val sessionEventLog: SessionEventLog? = null,
    networkConfirmationPolicy: NetworkToolConfirmationPolicy = NetworkToolConfirmationPolicy.REQUIRE_PER_HOST,
    private val nativeWebFetchEnabled: Boolean = true,
    private val disabledNativeTools: Set<String> = emptySet(),
    runtimeStateRetentionPolicy: RuntimeStateRetentionPolicy = RuntimeStateRetentionPolicy(),
    private val runtimeIoDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Optional reliability root for probe artifact files (internal only). */
    private val reliabilityStorageRoot: File? = null,
    /** Enabled probe packs; empty disables probes (observe-only off). */
    private val probePacks: List<ProbePack> = emptyList(),
    /** Host evidence binding from experimental [cc.noharry.agent.spi.RuntimeEvidenceConfig]; null disables. */
    private val evidenceConfig: InternalRuntimeEvidenceConfig? = null,
    private val contextProviders: List<RuntimeContextProvider> = emptyList(),
    private val turnObservers: List<RuntimeTurnObserver> = emptyList(),
    private val blueprint: RuntimeBlueprint? = null,
    sessionRunSchedulerConfig: SessionRunSchedulerConfig = defaultSessionRunSchedulerConfig(),
    /** Shared by the host across the runtimes it builds for one session's runs (see [SessionToolActivations]). */
    private val activationStore: ToolActivationStore = ToolActivationStore(nowEpochMillis = nowEpochMillis),
    private val runContext: String = "",
    /** Shared by the host across the runtimes it builds for one session's runs (see [SessionHistory]). */
    private val sessionHistory: SessionHistoryStore? = null,
    private val backendToolExposure: BackendToolExposure = BackendToolExposure.DIRECT,
    private val runContextAsSystem: Boolean = false,
    private val conversationCompactor: ConversationCompactor? = null,
    /** Shared by the host across runtimes, like [sessionHistory] (see [Subagents]). */
    private val subagents: Subagents? = null,
    /** Backs the native web_search tool (see [WebSearchProvider]). */
    private val webSearchProvider: WebSearchProvider? = null,
    /** Gets pages web_fetch's own request could not (see [WebFetchFallback]). */
    private val webFetchFallback: WebFetchFallback? = null,
    private val loopSettings: LoopSettings = LoopSettings(),
) : AgentRuntime {
    private val turnApprovals = java.util.concurrent.ConcurrentHashMap<String, suspend (PendingConfirmation) -> cc.noharry.agent.ApprovalState>()
    private val closed = AtomicBoolean(false)
    private val stateLocks = RunStateLockRegistry()
    private val runStateController = RunStateController(
        nowEpochMillis,
        traceRedactor,
        reliabilityStorage,
        stateLocks,
        runtimeStateRetentionPolicy,
    )
    private val confirmationStore = InMemoryConfirmationStore(nowEpochMillis)
    private val pendingToolUseController = PendingToolUseController()
    private val steeringQueue = RuntimeSteeringQueue(nowEpochMillis)
    private val sessionRecorder = RuntimeSessionRecorder(sessionStore, sessionEventLog, nowEpochMillis)
    private val mcpDebugTraceSink = RuntimeMcpDebugTraceSink { record ->
        runtimeIo { runStateController.recordMcpDebugCall(record) }
    }
    private val toolExecutionLimiter = ToolExecutionLimiter(maxConcurrentToolCalls)
    private val resumeFirstController = ResumeFirstController(runStateController)
    private val networkToolBackend = NetworkToolBackend(
        confirmationPolicy = networkConfirmationPolicy,
        searchService = webSearchProvider?.asService(),
        fetchFallback = webFetchFallback?.asService(),
    )
    private val terminalCoordinator = RuntimeTerminalCoordinator(
        turnObservers = turnObservers,
        nowEpochMillis = nowEpochMillis,
        lookupCompletionOutcome = { runId ->
            runStateController.result(runId)?.completion?.outcome?.name
        },
        cleanupTerminalRun = { runId, workspaceId -> cleanupTerminalRun(runId, workspaceId) },
        activationStore = activationStore,
        evidenceConfig = evidenceConfig,
        probePacks = probePacks,
        reliabilityStorageRoot = reliabilityStorageRoot,
        nativeWebFetchEnabled = nativeWebFetchEnabled,
        disabledNativeTools = disabledNativeTools,
        nativeExecEnabled = execEngine != null,
    )
    private val sessionAdmission = RuntimeSessionAdmissionCoordinator(
        config = sessionRunSchedulerConfig,
        closed = closed,
        runStateController = runStateController,
        sessionRecorder = sessionRecorder,
        resumeFirstController = resumeFirstController,
        runtimeIoDispatcher = runtimeIoDispatcher,
        terminal = terminalCoordinator,
        cleanupTerminalRun = { runId, workspaceId -> cleanupTerminalRun(runId, workspaceId) },
        cancelledObligationOrUnknown = { runId -> cancelledObligationOrUnknown(runId) },
        onSessionDrained = { drain -> drainSessionCapabilities(drain.sessionId) },
    )
    private val nativeLoopFactory = NativeLoopFactory(
        modelClient = modelClient,
        mcpRegistry = mcpRegistry,
        materializerSchemaCache = materializerSchemaCache,
        traceRedactor = traceRedactor,
        hookChain = hookChain,
        bootstrapPrompt = bootstrapPrompt,
        promptResolver = promptResolver,
        contextRouter = contextRouter,
        loopBudget = loopBudget,
        toolExecutionLimiter = toolExecutionLimiter,
        loopHistoryStore = loopHistoryStore,
        activationStore = activationStore,
        nowEpochMillis = nowEpochMillis,
        mcpDebugTraceSink = mcpDebugTraceSink,
        todoStore = todoStore,
        memoryStore = memoryStore,
        historyCompactor = historyCompactor,
        sessionRecorder = sessionRecorder,
        handoffProvider = { runId -> runtimeIo { runStateController.handoff(runId) } },
        modelRetryConfig = modelRetryConfig,
        execEngine = execEngine,
        networkToolBackend = networkToolBackend,
        nativeWebFetchEnabled = nativeWebFetchEnabled,
        disabledNativeTools = disabledNativeTools,
        steeringQueue = steeringQueue,
        probeAttachmentProvider = { request, runId -> terminalCoordinator.openProbeAttachment(request, runId) },
        contextProviders = contextProviders,
        turnObservers = turnObservers,
        runContext = runContext,
        sessionHistory = sessionHistory,
        backendToolExposure = backendToolExposure,
        runContextAsSystem = runContextAsSystem,
        conversationCompactor = conversationCompactor,
        subagents = subagents,
        nativeWebSearchEnabled = webSearchProvider != null,
        loopSettings = loopSettings,
    )
    private val userInputResumeController = UserInputResumeController(
        runStateController = runStateController,
        nativeLoopFactory = nativeLoopFactory,
        confirmationStore = confirmationStore,
        pendingToolUseController = pendingToolUseController,
        workspaceRegistry = workspaceRegistry,
        ioDispatcher = runtimeIoDispatcher,
    )
    private val controlResume = RuntimeControlResumeCoordinator(
        confirmationStore = confirmationStore,
        pendingToolUseController = pendingToolUseController,
        nativeLoopFactory = nativeLoopFactory,
        userInputResumeController = userInputResumeController,
        sessionAdmission = sessionAdmission,
        terminal = terminalCoordinator,
        runStateController = runStateController,
        sessionRecorder = sessionRecorder,
        traceRedactor = traceRedactor,
        closed = closed,
        runtimeIoDispatcher = runtimeIoDispatcher,
        cleanupTerminalRun = { runId, workspaceId -> cleanupTerminalRun(runId, workspaceId) },
        cancelledObligationCompletion = { runId -> cancelledObligationCompletion(runId) },
        recordResumeConfirmation = { runId, outcome -> recordResumeConfirmation(runId, outcome) },
        recordResumeUserInput = { runId, outcome -> recordResumeUserInput(runId, outcome) },
    )

    init {
        terminalCoordinator.bindLeaseRelease { runId, lease ->
            sessionAdmission.releaseExactLease(runId, lease)
        }
        terminalCoordinator.bindLoopFactory(
            runtimeInstanceId = { nativeLoopFactory.runtimeInstanceId },
            finishProbe = { runId, status, reason ->
                nativeLoopFactory.finishProbeRun(runId, status, reason)
            },
        )
    }

    override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
        coroutineScope { collectOrdinaryRun(request) }
    }

    internal suspend fun readThreadAttachment(threadId: String, sha256: String): ByteArray? =
        runtimeIo { sessionEventLog?.readAttachment(threadId, sha256) }

    internal fun childApprovalPolicy(threadId: String): cc.noharry.agent.ApprovalPolicy? = subagents?.approvalPolicyFor(threadId)

    internal fun clearThreadHistory(threadId: String) {
        sessionHistory?.clearSession(threadId)
        activationStore.clearSession(threadId)
        workspaceRegistry.clearSession(threadId)
    }

    /** Called after the conversation's turns have drained, including across runtimes sharing its storage. */
    internal suspend fun deleteThreadData(threadId: String, knownExecutionIds: Set<String>): Set<String> {
        check(sessionEventLog?.deleteThread(threadId) != false) { "The configured event log does not support thread deletion" }
        val executions = runStateController.deleteSession(threadId, knownExecutionIds)
        sessionRecorder.deleteThread(threadId, executions)
        check(todoStore?.clear(threadId) != false) { "Cannot delete the thread's saved plan" }
        clearThreadHistory(threadId)
        executions.forEach { executionId ->
            loopHistoryStore.delete(executionId)
            workspaceRegistry.clearRun(executionId)
        }
        return executions
    }

    /** A persisted attempt without a saved conversation result cannot be resumed after process loss. */
    internal fun interruptAbandonedAttempt(executionId: String) {
        val status = runStateController.snapshot(executionId)?.status ?: return
        if (status == RuntimeRunStatus.COMPLETED || status == RuntimeRunStatus.FAILED || status == RuntimeRunStatus.CANCELLED) return
        runStateController.applyRuntimeResult(RuntimeResult(
            executionId, RuntimeRunStatus.CANCELLED, "Execution was interrupted before its turn result was saved.",
            errorCode = "INTERRUPTED",
        ))
    }

    internal suspend fun notifyTurnFinished(result: cc.noharry.agent.TurnResult, sessionId: String, executionId: String) {
        hookChain.onTurnFinished(result, cc.noharry.agent.spi.HookContext(sessionId, executionId, result.turnId))
    }

    internal fun runThread(
        request: AgentRequest,
        approve: suspend (PendingConfirmation) -> cc.noharry.agent.ApprovalState,
    ): Flow<AgentEvent> = flow {
        check(turnApprovals.putIfAbsent(request.executionId, approve) == null) { "Turn is already running" }
        try { run(request).collect { emit(it) } } finally { turnApprovals.remove(request.executionId, approve) }
    }

    override suspend fun resumeConfirmation(
        decision: ConfirmationDecision,
    ): ConfirmationResumeResult {
        return coroutineScope {
            val pending = confirmationStore.getPending(decision.pendingId)
            if (pending != null && decision.choice == ConfirmationChoice.APPROVE) {
                return@coroutineScope controlResume.resumeApprovedConfirmation(decision, pending.runId)
            }
            controlResume.rejectOrCancelConfirmation(decision)
        }
    }

    override suspend fun getPendingConfirmation(pendingId: String): PendingConfirmation? =
        confirmationStore.getPending(pendingId)

    override suspend fun cancelPendingConfirmation(request: CancelPendingConfirmationRequest): CancelPendingResult {
        val result = confirmationStore.cancel(request)
        pendingToolUseController.remove(request.pendingId)?.let { paused ->
            if (result.apiResult is CancelPendingResult.Cancelled) nativeLoopFactory.recordUnconfirmed(paused, CONFIRMATION_CANCELLED_CODE)
        }
        controlResume.applyConfirmationTerminalState(result.runId, result.terminalStatus, result.response)
        return result.apiResult
    }

    override suspend fun getRunState(runId: String): RuntimeRunSnapshot? = runtimeIo {
        runStateController.snapshot(runId)
    }

    override suspend fun getRuntimeResult(runId: String): RuntimeResult? = runtimeIo {
        runStateController.result(runId)?.redactedForPublic(traceRedactor)
    }

    override suspend fun getRuntimeHandoff(runId: String) = runtimeIo {
        runStateController.handoff(runId)?.redactedForPublic(traceRedactor)
    }
    override suspend fun getOpenConfusion(runId: String) = runtimeIo {
        runStateController.openConfusion(runId)?.redactedForPublic(traceRedactor)
    }

    override suspend fun resumeUserInput(request: AgentRequest): RuntimeResult? {
        val runId = request.resumeRunId ?: return null
        return coroutineScope {
            var lease = sessionAdmission.leaseOf(runId)
            try {
                controlResume.resumeAdmittedUserInput(request, runId) { next -> lease = next }
            } catch (cancelled: CancellationException) {
                steeringQueue.close(runId, RuntimeRunStatus.CANCELLED.name)
                sessionAdmission.terminalizeCancelledRun(runId, lease)
                throw cancelled
            }
        }?.redactedForPublic(traceRedactor)
    }

    override suspend fun postUserInput(request: PostUserInputRequest): PostUserInputResult {
        return runtimeIo {
            val result = steeringQueue.post(request)
            if (result == PostUserInputResult.NotFound && runStateController.snapshot(request.runId) != null) {
                PostUserInputResult.RunNotActive
            } else result
        }
    }

    override suspend fun getRuntimeEvidence(evidenceId: String) = runtimeIo {
        runStateController.evidence(evidenceId)
            ?.redactedForPublic(traceRedactor)
    }

    override suspend fun querySessionEvents(query: SessionEventQuery) = runtimeIo {
        sessionEventLog?.query(query).orEmpty()
    }

    override suspend fun replaySessionEvents(sessionId: String, runId: String) = runtimeIo {
        sessionEventLog?.replay(sessionId, runId).orEmpty()
    }

    @OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)
    override fun subscribeSessionEvents(
        sessionId: String,
        listener: (cc.noharry.agent.spi.SessionEvent) -> Unit,
    ): AutoCloseable {
        val log = sessionEventLog ?: return AutoCloseable { }
        return log.subscribe { event ->
            if (event.sessionId == sessionId) listener(event)
        }
    }

    @OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)
    override suspend fun getSessionEventLogHealth(
        sessionId: String,
        runId: String,
    ) = runtimeIo {
        sessionEventLog?.health(sessionId, runId)
            ?: cc.noharry.agent.spi.SessionEventLogHealth.unknown()
    }

    @OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            runBlocking { sessionAdmission.closeScheduledRuns() }
        } catch (_: Throwable) {
        }
        terminalCoordinator.cancelNotifyJobs()
        // Persist paused/waiting CANCELLED probe evidence before loop/plugin drain.
        try {
            nativeLoopFactory.persistRemainingProbes()
        } catch (_: Exception) {
        }
        closeLoopAndPlugins()
        (sessionEventLog as? cc.noharry.agent.runtime.eventlog.AsyncSessionEventLog)?.close()
        // Cancel/close paths must not leak delivery or request indices.
        terminalCoordinator.clearDeliveryState()
        sessionAdmission.clearLeases()
    }

    internal fun runtimeBlueprintForTest(): RuntimeBlueprint? = blueprint

    @OptIn(InternalAgentRuntimeApi::class)
    internal fun providerLimiterEntryCountForTests(): Int =
        mcpRegistry.cachedLimiterEntryCountForTests()

    override suspend fun getRuntimeTraceEvents(runId: String) = runtimeIo {
        runStateController.traceEvents(runId)
            .distinctBy { "${it.eventName}:${it.createdAtEpochMillis}:${it.evidenceId}" }
    }
    internal suspend fun getRunEnvelope(runId: String) = runtimeIo { runStateController.envelope(runId) }
    internal suspend fun getRuntimeWorkpad(runId: String) = runtimeIo {
        runStateController.workpad(runId)?.redactedForPublic(traceRedactor)
    }
    internal suspend fun getRedactedTraceSummary(runId: String): RedactedTraceSummary =
        runtimeIo {
            TraceSummaryBuilder(traceRedactor).build(
                runId = runId,
                envelope = runStateController.envelope(runId),
                workpad = runStateController.workpad(runId),
                handoff = runStateController.handoff(runId),
                openConfusion = runStateController.openConfusion(runId),
                result = runStateController.result(runId),
                traceEvents = runStateController.traceEvents(runId)
                    .distinctBy { "${it.eventName}:${it.createdAtEpochMillis}:${it.evidenceId}" },
            )
        }
    internal fun allowsModelTraceSummary(): Boolean = traceVisibilityConfig.exposeModelTraceSummary
    internal fun debugRawTraceAccessDecision(): DebugRawTraceAccessDecision = traceVisibilityConfig.debugRawTraceAccessDecision()

    @Volatile
    internal var beforeCleanupForTest: (() -> Unit)? = null

    @Volatile
    internal var beforePersistQueuedForTest: (() -> Unit)? = null

    @OptIn(InternalAgentRuntimeApi::class)
    internal suspend fun sessionSchedulerSnapshotForTest(): SessionRunSchedulerSnapshot =
        sessionAdmission.scheduler.snapshot()

    internal fun sessionRunSchedulerForTest(): SessionRunScheduler = sessionAdmission.scheduler

    internal fun workspaceBoundForTest(runId: String): Boolean =
        workspaceRegistry.fileSystemForRun(runId) != null

    internal fun clearLoopHistoryCacheForTest(runId: String) {
        loopHistoryStore.clearCached(runId)
    }

    private suspend fun FlowCollector<AgentEvent>.collectOrdinaryRun(request: AgentRequest) {
        val runId = request.executionId
        var lease: SessionRunLease? = null
        try {
            val admitted = admitOrdinaryRun(request, runId) ?: return
            lease = admitted.lease
            executeAdmittedOrdinary(request, runId, admitted)
        } catch (cancelled: CancellationException) {
            steeringQueue.close(runId, RuntimeRunStatus.CANCELLED.name)
            sessionAdmission.terminalizeCancelledRun(runId, lease)
            throw cancelled
        } catch (error: Throwable) {
            try {
                sessionAdmission.terminalizeUnexpectedRunFailure(runId, lease)
            } catch (_: Throwable) {
                // Keep the original collector/storage failure even if terminal cleanup fails.
            }
            throw error
        }
    }

    private suspend fun FlowCollector<AgentEvent>.admitOrdinaryRun(
        request: AgentRequest,
        runId: String,
    ): AdmittedOrdinaryRun? {
        if (closed.get()) {
            emitClosedWithoutStart(runId)
            return null
        }
        val blocker = runtimeIo { sessionAdmission.waitingConflictBlocker(request) }
        if (blocker != null) {
            emit(AgentEvent.RuntimeBlocked(blocker))
            return null
        }
        if (!emitReplaceOwnerOrContinue(request)) {
            return null
        }
        return persistAndClaimOrdinary(request, runId)
    }

    private suspend fun FlowCollector<AgentEvent>.emitReplaceOwnerOrContinue(
        request: AgentRequest,
    ): Boolean {
        if (request.sessionRunConflictPolicy != SessionRunConflictPolicy.CANCEL_AND_REPLACE) {
            return true
        }
        val replaceBlocker = sessionAdmission.replaceWaitingOwner(request.sessionId)
        if (replaceBlocker == null) {
            return true
        }
        emit(AgentEvent.RuntimeBlocked(replaceBlocker))
        return false
    }

    private suspend fun FlowCollector<AgentEvent>.persistAndClaimOrdinary(
        request: AgentRequest,
        runId: String,
    ): AdmittedOrdinaryRun? {
        beforePersistQueuedForTest?.invoke()
        val queued = sessionAdmission.persistQueuedRun(request, runId)
        if (queued == null) {
            emitUnpersistedFailure(sessionAdmission.duplicateRunResult(runId))
            return null
        }
        terminalCoordinator.indexRunRequest(request, runId)
        return claimOrdinaryAdmission(request, runId)
    }

    private suspend fun FlowCollector<AgentEvent>.claimOrdinaryAdmission(
        request: AgentRequest,
        runId: String,
    ): AdmittedOrdinaryRun? {
        return when (val admission = sessionAdmission.scheduler.admitOrdinary(request.sessionId, runId)) {
            is SessionRunAdmission.Rejected -> {
                emitTerminalResult(sessionAdmission.admissionRejectionResult(admission, runId), workspaceId = null)
                null
            }
            is SessionRunAdmission.Granted -> bindGrantedOrdinary(admission, runId)
        }
    }

    private suspend fun FlowCollector<AgentEvent>.bindGrantedOrdinary(
        admission: SessionRunAdmission.Granted,
        runId: String,
    ): AdmittedOrdinaryRun? {
        val lease = sessionAdmission.attachGrantedLease(admission)
        if (lease == null) {
            emitTerminalResult(sessionAdmission.closedRunResult(runId), workspaceId = null)
            return null
        }
        val started = sessionAdmission.persistRunningAfterGrant(runId, lease)
        if (started == null) {
            sessionAdmission.persistClosedRun(runId)
            if (!closed.get()) {
                emitTerminalResult(sessionAdmission.closedRunResult(runId), workspaceId = null)
            }
            return null
        }
        // Input is admitted from RUNNING onward; facts are buffered until the loop attaches its recorder.
        steeringQueue.admit(started.runId, started.sessionId)
        return AdmittedOrdinaryRun(lease, started)
    }

    private suspend fun FlowCollector<AgentEvent>.executeAdmittedOrdinary(
        request: AgentRequest,
        runId: String,
        admitted: AdmittedOrdinaryRun,
    ) {
        if (sessionActivationInheritance) {
            activationStore.inheritSessionActivations(runId, request.sessionId)
        }
        val selected = emitWorkspaceSelection(request, runId) ?: return
        if (emitConfiguredConfirmation(admitted.started, request)) {
            return
        }
        emitNativeLoopOutcome(request, runId, admitted.started, selected)
    }

    private suspend fun FlowCollector<AgentEvent>.emitWorkspaceSelection(
        request: AgentRequest,
        runId: String,
    ): SelectedWorkspace? {
        emitHost(AgentEvent.Started(runId))
        return when (val workspaceSelection = workspaceRegistry.select(request, runId)) {
            is RuntimeWorkspaceSelection.Selected -> {
                if (workspaceSelection.changed && workspaceSelection.workspaceId != null) {
                    emitHost(AgentEvent.WorkspaceChanged(runId, workspaceSelection.workspaceId))
                }
                SelectedWorkspace(workspaceSelection.workspaceId, workspaceSelection.fileSystem)
            }
            is RuntimeWorkspaceSelection.Unknown -> {
                emitTerminalResult(
                    unknownWorkspaceResult(runId, workspaceSelection.workspaceId),
                    workspaceId = null,
                )
                null
            }
        }
    }

    private suspend fun FlowCollector<AgentEvent>.emitNativeLoopOutcome(
        request: AgentRequest,
        runId: String,
        started: RuntimeRunSnapshot,
        selected: SelectedWorkspace,
    ) {
        var outcome = nativeLoopFactory.run(request, runId, selected.fileSystem) { emitHost(it) }
        val approve = turnApprovals[runId]
        while (outcome is NativeLoopOutcome.RequiresConfirmation && approve != null) {
            outcome = continueApprovedTurn(request, outcome, approve)
        }
        when (val loopOutcome = outcome) {
            is NativeLoopOutcome.Terminal -> emitTerminalLoopResult(request, runId, selected.workspaceId, loopOutcome)
            is NativeLoopOutcome.RequiresConfirmation -> {
                runtimeIo { runStateController.recordNativeLoopSummary(runId, loopOutcome.summary) }
                emitToolConfirmation(started, request, loopOutcome)
            }
            is NativeLoopOutcome.NeedsUserInput -> {
                runtimeIo { runStateController.recordNativeLoopSummary(runId, loopOutcome.summary) }
                if (approve == null) emitWaitingUserInput(started, loopOutcome.question, loopOutcome.summary)
                else finishQuestionTurn(started, loopOutcome, selected.workspaceId)
            }
        }
    }

    private suspend fun FlowCollector<AgentEvent>.continueApprovedTurn(
        request: AgentRequest,
        outcome: NativeLoopOutcome.RequiresConfirmation,
        approve: suspend (PendingConfirmation) -> cc.noharry.agent.ApprovalState,
    ): NativeLoopOutcome = continueThreadApproval(request, outcome, approve, nativeLoopFactory, steeringQueue,
        nowEpochMillis()) { emitHost(it) }

    private suspend fun FlowCollector<AgentEvent>.finishQuestionTurn(
        started: RuntimeRunSnapshot,
        outcome: NativeLoopOutcome.NeedsUserInput,
        workspaceId: String?,
    ) {
        val waiting = runtimeIo { runStateController.markWaitingUserInput(started, outcome.question, outcome.summary) }
        sessionAdmission.markFinalizingIfExecuting(started.runId)
        persistTerminalResult(RuntimeResult(started.runId, RuntimeRunStatus.COMPLETED, outcome.question))
        coordinateTerminalFinalization(started.runId, RuntimeRunStatus.COMPLETED.name, workspaceId)
        emit(AgentEvent.RuntimeBlocked(waiting.blocker.copy(message = outcome.question)))
    }

    private suspend fun FlowCollector<AgentEvent>.emitTerminalLoopResult(
        request: AgentRequest,
        runId: String,
        workspaceId: String?,
        loopOutcome: NativeLoopOutcome.Terminal,
    ) {
        runtimeIo {
            runStateController.recordNativeLoopSummary(runId, loopOutcome.summary)
            memoryStore?.let { store ->
                for (candidate in extractMemoryCandidates(loopOutcome.summary, loopOutcome.result, loopSettings.memory.policy)) {
                    store.promote(candidate, request.scene, candidate.lessonMode())
                }
            }
        }
        // Persist Runtime result/session first, then observers.
        emitTerminalResult(loopOutcome.result, workspaceId)
    }

    private fun closeLoopAndPlugins() {
        // Drain loop resources first, then the shared limiter/registry, then plugins.
        // Aggregate ordinary exceptions without throwing. Idempotent via [closed].
        try {
            nativeLoopFactory.closeAll()
        } catch (_: Exception) {
        }
        try {
            runBlocking { mcpRegistry.close() }
        } catch (_: Exception) {
        }
        blueprint?.close()
    }

    private suspend fun drainSessionCapabilities(sessionId: String) {
        try {
            mcpRegistry.closeSession(sessionId)
        } finally {
            try {
                blueprint?.closeSession(SessionCapabilityContext(sessionId))
            } catch (_: Exception) {
                // Blueprint close uses closeSafely and normally does not throw.
                // Swallow so a later close failure cannot replace the MCP exception.
            }
        }
    }

    private suspend fun FlowCollector<AgentEvent>.emitConfiguredConfirmation(
        started: RuntimeRunSnapshot,
        request: AgentRequest,
    ): Boolean {
        val confirmation = confirmationTemplate ?: return false
        val pending = confirmationStore.create(started.runId, request.sessionId, confirmation.expiresInMillis, CONFIGURED_CONFIRMATION_PROMPT)
        runtimeIo {
            runStateController.markWaitingConfirmation(
                started = started,
                prompt = CONFIGURED_CONFIRMATION_PROMPT,
                pendingConfirmationId = pending.pendingId,
            )
            sessionRecorder.updateRunStatus(started.runId, RuntimeRunStatus.WAITING_CONFIRMATION)
        }
        sessionAdmission.markWaitingAfterPersist(started.runId)
        emit(AgentEvent.ConfirmationRequired(pending))
        return true
    }

    private suspend fun FlowCollector<AgentEvent>.emitTerminalResult(
        result: RuntimeResult,
        workspaceId: String?,
    ) {
        sessionAdmission.markFinalizingIfExecuting(result.runId)
        val recorded = persistTerminalResult(result)
        // Unified post-persist: observer delivery → Probe finishAndPersist → hub uninstall → cleanup.
        coordinateTerminalFinalization(recorded.runId, recorded.status.name, workspaceId)
        emitHost(AgentEvent.ResultAvailable(recorded.redactedForPublic(traceRedactor)))
        emitTerminalHostStatus(recorded.redactedForPublic(traceRedactor))
    }

    private suspend fun persistTerminalResult(result: RuntimeResult): RuntimeResult = runtimeIo {
        runStateController.applyRuntimeResult(result).also { terminal ->
            sessionRecorder.finishRun(terminal)
            (sessionEventLog as? cc.noharry.agent.runtime.eventlog.AsyncSessionEventLog)?.flush()
            if (sessionActivationInheritance) {
                runStateController.snapshot(terminal.runId)?.sessionId?.let { sessionId ->
                    activationStore.snapshotSessionActivations(terminal.runId, sessionId)
                }
            }
        }
    }

    private suspend fun FlowCollector<AgentEvent>.emitTerminalHostStatus(recorded: RuntimeResult) {
        if (recorded.status == RuntimeRunStatus.COMPLETED) {
            emitHost(AgentEvent.Finished(recorded.runId))
        } else {
            emitHost(
                AgentEvent.Failed(
                    recorded.runId,
                    recorded.errorCode ?: RuntimeErrorCode.MODEL_LOOP_FAILED.wireCode,
                    recorded.response,
                    recorded.recoverable,
                ),
            )
        }
    }

    private suspend fun FlowCollector<AgentEvent>.emitToolConfirmation(
        started: RuntimeRunSnapshot,
        request: AgentRequest,
        outcome: NativeLoopOutcome.RequiresConfirmation,
    ) {
        val pending = confirmationStore.create(
            runId = started.runId,
            sessionId = request.sessionId,
            expiresInMillis = DEFAULT_CONFIRMATION_TIMEOUT_MILLIS,
            prompt = outcome.prompt,
            toolName = outcome.pendingToolUse?.toolUse?.toolName,
            argumentsJson = outcome.pendingToolUse?.toolUse?.argumentsJson,
        )
        runtimeIo {
            runStateController.markWaitingConfirmation(
                started = started,
                prompt = outcome.prompt,
                pendingConfirmationId = pending.pendingId,
                summary = outcome.summary,
            )
            sessionRecorder.updateRunStatus(started.runId, RuntimeRunStatus.WAITING_CONFIRMATION)
        }
        sessionAdmission.markWaitingAfterPersist(started.runId)
        recordPendingToolUse(pending, outcome)
        emit(AgentEvent.ConfirmationRequired(pending))
    }

    private fun recordPendingToolUse(
        pending: PendingConfirmation,
        outcome: NativeLoopOutcome.RequiresConfirmation,
    ) {
        outcome.pendingToolUse?.let { pendingToolUseController.record(pending, it) }
    }

    private suspend fun recordResumeConfirmation(
        runId: String?,
        outcome: NativeLoopOutcome.RequiresConfirmation,
    ) {
        val snapshot = resumedSnapshot(runId) ?: return
        val pending = confirmationStore.create(
            runId = snapshot.runId,
            sessionId = snapshot.sessionId,
            expiresInMillis = DEFAULT_CONFIRMATION_TIMEOUT_MILLIS,
            prompt = outcome.prompt,
            toolName = outcome.pendingToolUse?.toolUse?.toolName,
            argumentsJson = outcome.pendingToolUse?.toolUse?.argumentsJson,
        )
        runtimeIo {
            runStateController.recordNativeLoopSummary(snapshot.runId, outcome.summary)
            runStateController.markWaitingConfirmation(
                started = snapshot,
                prompt = outcome.prompt,
                pendingConfirmationId = pending.pendingId,
                summary = outcome.summary,
            )
            sessionRecorder.updateRunStatus(snapshot.runId, RuntimeRunStatus.WAITING_CONFIRMATION)
        }
        sessionAdmission.markWaitingAfterPersist(snapshot.runId)
        recordPendingToolUse(pending, outcome)
    }

    private suspend fun recordResumeUserInput(
        runId: String?,
        outcome: NativeLoopOutcome.NeedsUserInput,
    ) {
        val snapshot = resumedSnapshot(runId) ?: return
        runtimeIo {
            runStateController.recordNativeLoopSummary(snapshot.runId, outcome.summary)
            runStateController.markWaitingUserInput(snapshot, outcome.question, outcome.summary)
            sessionRecorder.updateRunStatus(snapshot.runId, RuntimeRunStatus.WAITING_USER_INPUT)
        }
        sessionAdmission.markWaitingAfterPersist(snapshot.runId)
    }

    private suspend fun resumedSnapshot(runId: String?): RuntimeRunSnapshot? =
        runId?.let { runtimeIo { runStateController.snapshot(it) } }

    private suspend fun FlowCollector<AgentEvent>.emitWaitingUserInput(
        started: RuntimeRunSnapshot,
        question: String,
        summary: NativeLoopSummary,
    ) {
        val waitingState = runtimeIo {
            runStateController.markWaitingUserInput(started, question, summary).also {
                sessionRecorder.updateRunStatus(started.runId, RuntimeRunStatus.WAITING_USER_INPUT)
            }
        }
        sessionAdmission.markWaitingAfterPersist(started.runId)
        emit(AgentEvent.RuntimeBlocked(waitingState.blocker.redactedForPublic(traceRedactor)))
    }

    private fun cancelledObligationCompletion(
        runId: String,
    ): RuntimeCompletionSummary {
        val history = loopHistoryStore.read(runId)
        val ledger = RuntimeObligationLedger.decode(history?.obligationLedger)
        ledger.markCancelled()
        if (history != null) {
            loopHistoryStore.save(runId, history.messages, history.budgetSnapshot, ledger.encode())
        }
        return ledger.summarize(cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome.FAILED)
    }

    private suspend fun cleanupTerminalRun(runId: String, workspaceId: String? = null) {
        beforeCleanupForTest?.invoke()
        runtimeIo {
            confirmationStore.clearRun(runId)
            pendingToolUseController.clearRun(runId)
            networkToolBackend.clearRun(runId)
            activationStore.clear(runId)
            sessionHistory?.forget(runId)
            subagents?.runEnded(runId)
            // Host cleanup may run before the loop observes its own cancellation; label leftovers with the real terminal status.
            steeringQueue.clear(runId, runStateController.result(runId)?.status?.name ?: "RUN_CLOSED")
            loopHistoryStore.clearCached(runId)
            val fileSystem = workspaceRegistry.fileSystem(workspaceId)
                ?: workspaceRegistry.fileSystemForRun(runId)
            fileSystem?.let { VirtualWorkspace.cleanupScratch(it, runId) }
            workspaceRegistry.clearRun(runId)
        }
    }

    private suspend fun <T> runtimeIo(block: suspend () -> T): T =
        withContext(runtimeIoDispatcher) { block() }

    private fun unknownWorkspaceResult(runId: String, workspaceId: String): RuntimeResult =
        RuntimeResult(
            runId = runId,
            status = RuntimeRunStatus.FAILED,
            response = "Unknown runtime workspace: $workspaceId",
            errorCode = RuntimeErrorCode.WORKSPACE_NOT_CONFIGURED.wireCode,
            recoverable = false,
        )

    private suspend fun FlowCollector<AgentEvent>.emitUnpersistedFailure(result: RuntimeResult) {
        val publicResult = result.redactedForPublic(traceRedactor)
        emitHost(AgentEvent.ResultAvailable(publicResult))
        emitHost(
            AgentEvent.Failed(
                publicResult.runId,
                publicResult.errorCode ?: RuntimeErrorCode.DUPLICATE_RUN_ID.wireCode,
                publicResult.response,
                publicResult.recoverable,
            ),
        )
    }

    private suspend fun FlowCollector<AgentEvent>.emitHost(event: AgentEvent) {
        if (closed.get()) return
        if (event is AgentEvent.EmptyCompletionObserved) {
            runtimeIo { runStateController.recordObservation(event.runId, "EMPTY_COMPLETION_OBSERVED", event.traceDetails()) }
        }
        emit(event)
    }

    private suspend fun FlowCollector<AgentEvent>.emitClosedWithoutStart(runId: String) {
        val result = sessionAdmission.closedRunResult(runId).redactedForPublic(traceRedactor)
        emit(AgentEvent.ResultAvailable(result))
        emit(
            AgentEvent.Failed(
                runId,
                RuntimeErrorCode.RUNTIME_CLOSED.wireCode,
                result.response,
                result.recoverable,
            ),
        )
    }

    private suspend fun coordinateTerminalFinalization(
        runId: String,
        status: String,
        workspaceId: String? = null,
        lease: SessionRunLease? = sessionAdmission.leaseOf(runId),
    ) {
        terminalCoordinator.coordinateTerminalFinalization(runId, status, workspaceId, lease)
    }

    private fun RuntimeRunStatus.isTerminalStatus(): Boolean =
        this == RuntimeRunStatus.COMPLETED ||
            this == RuntimeRunStatus.FAILED ||
            this == RuntimeRunStatus.CANCELLED

    private fun cancelledObligationOrUnknown(runId: String): RuntimeCompletionSummary {
        val history = loopHistoryStore.read(runId)
        return if (history != null) {
            cancelledObligationCompletion(runId)
        } else {
            RuntimeCompletionSummary.unknown()
        }
    }

    private data class AdmittedOrdinaryRun(
        val lease: SessionRunLease,
        val started: RuntimeRunSnapshot,
    )

    private data class SelectedWorkspace(
        val workspaceId: String?,
        val fileSystem: VfsFileSystem?,
    )
}

private const val CONFIGURED_CONFIRMATION_PROMPT = "Resolve the pending confirmation."

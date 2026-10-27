@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.plugin
import cc.noharry.agent.kernel.api.AgentCapabilities
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.CancelPendingConfirmationRequest
import cc.noharry.agent.kernel.api.CancelPendingResult
import cc.noharry.agent.kernel.api.ConfirmationDecision
import cc.noharry.agent.kernel.api.ConfirmationResumeResult
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.PendingConfirmation
import cc.noharry.agent.kernel.api.PostUserInputRequest
import cc.noharry.agent.kernel.api.PostUserInputResult
import cc.noharry.agent.kernel.api.ProtocolRequestCancellation
import cc.noharry.agent.kernel.api.ProtocolRequestCloseLoopResult
import cc.noharry.agent.kernel.api.ProtocolRequestRejection
import cc.noharry.agent.kernel.api.ProtocolRequestResolution
import cc.noharry.agent.kernel.api.SessionCapabilityContext
import cc.noharry.agent.kernel.api.WorkspacePort
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.reliability.OpenConfusion
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidence
import cc.noharry.agent.kernel.api.reliability.RuntimeHandoff
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeTraceEvent
import cc.noharry.agent.kernel.core.context.BootstrapPromptProvider
import cc.noharry.agent.kernel.core.context.ProtocolTools
import cc.noharry.agent.kernel.core.loop.NativeLoopOutcome
import cc.noharry.agent.kernel.core.loop.NativeToolLoop
import cc.noharry.agent.kernel.core.loop.RuntimeSteeringQueue
import cc.noharry.agent.kernel.core.state.DEFAULT_MAX_TERMINAL_RUNS
import cc.noharry.agent.kernel.core.tools.ConcurrencyLimitedExecEngine
import cc.noharry.agent.kernel.core.tools.ConcurrencyLimitedModelClient
import cc.noharry.agent.kernel.core.tools.ProviderConcurrencyLimiter
import cc.noharry.agent.kernel.core.tools.ToolProviderRegistry
import cc.noharry.agent.kernel.core.trace.DefaultTraceRedactor
import cc.noharry.agent.kernel.core.trace.TraceRedactor
import cc.noharry.agent.kernel.core.trace.redactedForPublic
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Minimal [AgentRuntime] that executes the single core [NativeToolLoop] with
 * in-memory/no-op ports. Control/query methods fail closed when no pending control exists.
 *
 * Host-supplied [ModelClient] and [WorkspacePort] are externally owned and are not closed
 * with this runtime; hosts may share those objects across runtimes. One runtime instance
 * owns its compiled blueprint, limiter, and registry; do not share those objects.
 *
 * Installed [AgentCapabilities.RUNTIME_HOOK] and [AgentCapabilities.SESSION_EVENT_LOG]
 * are unsupported here and are ignored if present.
 */
@ExperimentalAgentRuntimeApi
class CoreAgentRuntime internal constructor(
    private val blueprint: RuntimeBlueprint,
    private val modelClient: ModelClient,
    private val workspace: WorkspacePort,
    private val configuredWorkspaceId: String = KernelRuntimeBuilder.DEFAULT_WORKSPACE_ID,
    private val maxTerminalRuns: Int = DEFAULT_MAX_TERMINAL_RUNS,
    private val traceRedactor: TraceRedactor = DefaultTraceRedactor,
) : AgentRuntime {
    private val limiter = ProviderConcurrencyLimiter()
    private val mcpRegistry = ToolProviderRegistry(
        providers = emptyList(),
        concurrencyLimiter = limiter,
        blueprint = blueprint,
    )
    private val limitedModel = ConcurrencyLimitedModelClient(modelClient, limiter)
    private val limitedExec = blueprint.resolveShared(AgentCapabilities.EXEC_ENGINE)?.let { engine ->
        ConcurrencyLimitedExecEngine(engine, limiter)
    }

    private val closed = AtomicBoolean(false)
    private val resourcesClosed = AtomicBoolean(false)
    private val admissionLock = Any()
    private val activeRuns = ConcurrentHashMap<String, TrackedRun>()
    private val sessionCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val sessionMutexes = ConcurrentHashMap<String, Mutex>()
    private val sessionDrains = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val terminalLock = Any()
    private val terminalOrder = ArrayDeque<String>()
    private val terminalResults = HashMap<String, RuntimeResult>()
    private val steeringQueue = RuntimeSteeringQueue()

    internal fun modelClientForTest(): ModelClient = modelClient

    internal fun workspaceForTest(): WorkspacePort = workspace

    internal fun blueprintForTest(): RuntimeBlueprint = blueprint

    internal fun configuredWorkspaceIdForTest(): String = configuredWorkspaceId

    internal fun limitedModelForTest(): ModelClient = limitedModel

    internal fun limitedExecForTest(): ConcurrencyLimitedExecEngine? = limitedExec

    internal fun providerLimiterEntryCountForTests(): Int =
        mcpRegistry.cachedLimiterEntryCountForTests()

    internal fun activeRunCountForTest(): Int = activeRuns.size

    internal fun resourcesClosedForTest(): Boolean = resourcesClosed.get()

    override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
        val runId = request.requestId
        val sessionId = request.sessionId
        val job = currentCoroutineContext().job
        when (admit(request, job)) {
            Admission.Closed -> {
                emitClosedFailure(runId)
                return@flow
            }
            Admission.Duplicate -> {
                emitTerminal(duplicateResult(runId), emitStarted = false)
                return@flow
            }
            Admission.UnknownWorkspace -> {
                emitTerminal(unknownWorkspaceResult(runId, request.workspaceId), emitStarted = true)
                return@flow
            }
            Admission.Admitted -> Unit
        }
        try {
            awaitSessionDrain(sessionId)
            sessionMutex(sessionId).withLock {
                if (closed.get()) {
                    recordTerminal(closedResult(runId))
                    return@withLock
                }
                emitIfOpen(AgentEvent.Started(runId))
                executeLoop(request, runId)
            }
        } catch (cancelled: CancellationException) {
            steeringQueue.close(runId, "CANCELLED")
            recordTerminal(if (closed.get()) closedResult(runId) else cancelledResult(runId))
            throw cancelled
        } finally {
            withContext(NonCancellable) {
                steeringQueue.clear(runId, terminalOf(runId)?.status?.name ?: "RUN_CLOSED")
                leaveRun(runId, sessionId)
            }
        }
    }

    override suspend fun resumeConfirmation(decision: ConfirmationDecision): ConfirmationResumeResult =
        ConfirmationResumeResult.NotFound

    override suspend fun getPendingConfirmation(pendingId: String): PendingConfirmation? = null

    override suspend fun cancelPendingConfirmation(
        request: CancelPendingConfirmationRequest,
    ): CancelPendingResult = CancelPendingResult.NotFound

    override suspend fun getRunState(runId: String): RuntimeRunSnapshot? = null

    override suspend fun getRuntimeResult(runId: String): RuntimeResult? =
        terminalOf(runId)?.redactedForPublic(traceRedactor)

    override suspend fun getRuntimeHandoff(runId: String): RuntimeHandoff? = null

    override suspend fun getOpenConfusion(runId: String): OpenConfusion? = null

    override suspend fun resumeUserInput(request: AgentRequest): RuntimeResult? = null

    override suspend fun postUserInput(request: PostUserInputRequest): PostUserInputResult {
        val result = steeringQueue.post(request)
        return if (result == PostUserInputResult.NotFound && hasTerminal(request.runId)) PostUserInputResult.RunNotActive else result
    }

    override suspend fun resolveRuntimeProtocolRequest(
        decision: ProtocolRequestResolution,
    ): ProtocolRequestCloseLoopResult = ProtocolRequestCloseLoopResult.NotFound

    override suspend fun rejectRuntimeProtocolRequest(
        decision: ProtocolRequestRejection,
    ): ProtocolRequestCloseLoopResult = ProtocolRequestCloseLoopResult.NotFound

    override suspend fun cancelRuntimeProtocolRequest(
        decision: ProtocolRequestCancellation,
    ): ProtocolRequestCloseLoopResult = ProtocolRequestCloseLoopResult.NotFound

    override suspend fun getPendingProtocolRequests(
        sessionId: String?,
        runId: String?,
    ): List<RuntimeProtocolRequest> = emptyList()

    override suspend fun getRuntimeEvidence(evidenceId: String): RuntimeEvidence? = null

    override suspend fun getRuntimeTraceEvents(runId: String): List<RuntimeTraceEvent> = emptyList()

    override fun close() {
        val jobs: List<Job>
        val closeNow: Boolean
        synchronized(admissionLock) {
            if (!closed.compareAndSet(false, true)) return
            jobs = activeRuns.values.map { it.job }
            closeNow = activeRuns.isEmpty()
        }
        jobs.forEach { job -> job.cancel() }
        if (closeNow) {
            runBlocking { closeResources() }
        }
    }

    private suspend fun FlowCollector<AgentEvent>.executeLoop(
        request: AgentRequest,
        runId: String,
    ) {
        val outcome = try {
            NativeToolLoop(
                modelClient = limitedModel,
                mcpRegistry = mcpRegistry,
                workspace = workspace,
                execEngine = limitedExec,
                contextProviders = blueprint.resolveSharedAll(AgentCapabilities.RUNTIME_CONTEXT_PROVIDER),
                turnObservers = blueprint.resolveSharedAll(AgentCapabilities.RUNTIME_TURN_OBSERVER),
                nativeWebFetchEnabled = false,
                bootstrapPrompt = KERNEL_PROMPT,
                steeringQueue = steeringQueue,
            ).run(request, runId) { event -> emitIfOpen(event) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emitTerminal(failedResult(runId, error.message ?: "Model loop failed."))
            return
        }
        when (outcome) {
            is NativeLoopOutcome.Terminal -> emitTerminal(outcome.result)
            is NativeLoopOutcome.RequiresConfirmation ->
                emitTerminal(
                    pendingResult(
                        runId = runId,
                        message = outcome.prompt,
                        errorCode = RuntimeErrorCode.PENDING_CONFIRMATION_EXISTS,
                    ),
                )
            is NativeLoopOutcome.NeedsUserInput ->
                emitTerminal(
                    pendingResult(
                        runId = runId,
                        message = outcome.question,
                        errorCode = RuntimeErrorCode.WAITING_USER_INPUT,
                    ),
                )
        }
    }

    private fun admit(request: AgentRequest, job: Job): Admission {
        synchronized(admissionLock) {
            if (closed.get()) return Admission.Closed
            if (!acceptsWorkspaceId(request.workspaceId, configuredWorkspaceId)) {
                return Admission.UnknownWorkspace
            }
            val runId = request.requestId
            if (activeRuns.containsKey(runId) || hasTerminal(runId)) {
                return Admission.Duplicate
            }
            activeRuns[runId] = TrackedRun(request.sessionId, job)
            sessionCounts.getOrPut(request.sessionId) { AtomicInteger(0) }.incrementAndGet()
            sessionMutexes.getOrPut(request.sessionId) { Mutex() }
            return Admission.Admitted
        }
    }

    private suspend fun leaveRun(runId: String, sessionId: String) {
        val decision = synchronized(admissionLock) { decideLeaveLocked(runId, sessionId) }
        if (decision.drainSession) {
            try {
                drainSession(sessionId)
            } finally {
                completeSessionDrain(sessionId)
            }
        }
        if (decision.closeResources) {
            closeResources()
        }
    }

    private fun decideLeaveLocked(runId: String, sessionId: String): LeaveDecision {
        if (activeRuns.remove(runId) == null) {
            return LeaveDecision(drainSession = false, closeResources = false)
        }
        val remaining = sessionCounts[sessionId]?.decrementAndGet() ?: 0
        val lastSession = remaining <= 0
        if (lastSession) {
            sessionCounts.remove(sessionId)
            sessionMutexes.remove(sessionId)
            sessionDrains[sessionId] = CompletableDeferred()
        }
        return LeaveDecision(
            drainSession = lastSession,
            closeResources = closed.get() && activeRuns.isEmpty(),
        )
    }

    private suspend fun awaitSessionDrain(sessionId: String) {
        val gate = synchronized(admissionLock) { sessionDrains[sessionId] } ?: return
        gate.await()
    }

    private fun completeSessionDrain(sessionId: String) {
        val gate = synchronized(admissionLock) { sessionDrains.remove(sessionId) }
        gate?.complete(Unit)
    }

    private suspend fun drainSession(sessionId: String) {
        try {
            mcpRegistry.closeSession(sessionId)
        } finally {
            try {
                if (!resourcesClosed.get()) {
                    blueprint.closeSession(SessionCapabilityContext(sessionId))
                }
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun closeResources() {
        if (!resourcesClosed.compareAndSet(false, true)) return
        try {
            mcpRegistry.close()
        } finally {
            blueprint.close()
        }
    }

    private fun sessionMutex(sessionId: String): Mutex =
        sessionMutexes.getOrPut(sessionId) { Mutex() }

    private suspend fun FlowCollector<AgentEvent>.emitIfOpen(event: AgentEvent) {
        if (closed.get()) return
        emit(event)
    }

    private suspend fun FlowCollector<AgentEvent>.emitClosedFailure(runId: String) {
        val result = closedResult(runId)
        recordTerminal(result)
        val publicResult = result.redactedForPublic(traceRedactor)
        emit(AgentEvent.ResultAvailable(publicResult))
        emit(
            AgentEvent.Failed(
                runId,
                RuntimeErrorCode.RUNTIME_CLOSED.wireCode,
                publicResult.response,
                publicResult.recoverable,
            ),
        )
    }

    private suspend fun FlowCollector<AgentEvent>.emitTerminal(
        result: RuntimeResult,
        emitStarted: Boolean = false,
    ) {
        recordTerminal(result)
        emitPublicTerminal(result, emitStarted)
    }

    private suspend fun FlowCollector<AgentEvent>.emitPublicTerminal(
        result: RuntimeResult,
        emitStarted: Boolean,
    ) {
        val publicResult = result.redactedForPublic(traceRedactor)
        if (emitStarted) {
            emitIfOpen(AgentEvent.Started(publicResult.runId))
        }
        emitIfOpen(AgentEvent.ResultAvailable(publicResult))
        if (publicResult.status == RuntimeRunStatus.COMPLETED) {
            emitIfOpen(AgentEvent.Finished(publicResult.runId))
        } else {
            emitIfOpen(
                AgentEvent.Failed(
                    publicResult.runId,
                    publicResult.errorCode ?: RuntimeErrorCode.MODEL_LOOP_FAILED.wireCode,
                    publicResult.response,
                    publicResult.recoverable,
                ),
            )
        }
    }

    private fun recordTerminal(result: RuntimeResult) {
        synchronized(terminalLock) {
            val previous = terminalResults.put(result.runId, result)
            if (previous == null) {
                terminalOrder.addLast(result.runId)
            }
            while (terminalOrder.size > maxTerminalRuns) {
                val evicted = terminalOrder.removeFirst()
                if (evicted != result.runId) {
                    terminalResults.remove(evicted)
                }
            }
        }
    }

    private fun terminalOf(runId: String): RuntimeResult? =
        synchronized(terminalLock) { terminalResults[runId] }

    private fun hasTerminal(runId: String): Boolean =
        synchronized(terminalLock) { runId in terminalResults }

    private data class TrackedRun(
        val sessionId: String,
        val job: Job,
    )

    private data class LeaveDecision(
        val drainSession: Boolean,
        val closeResources: Boolean,
    )

    private sealed interface Admission {
        data object Closed : Admission
        data object Duplicate : Admission
        data object UnknownWorkspace : Admission
        data object Admitted : Admission
    }
}

internal fun acceptsWorkspaceId(requestWorkspaceId: String?, configuredWorkspaceId: String): Boolean {
    val requested = requestWorkspaceId?.trim()?.takeIf { it.isNotEmpty() } ?: return true
    return requested == configuredWorkspaceId
}

private fun closedResult(runId: String): RuntimeResult = RuntimeResult(
    runId = runId,
    status = RuntimeRunStatus.CANCELLED,
    response = "Runtime is closed.",
    errorCode = RuntimeErrorCode.RUNTIME_CLOSED.wireCode,
    recoverable = false,
    completion = RuntimeCompletionSummary.unknown(),
)

private fun cancelledResult(runId: String): RuntimeResult = RuntimeResult(
    runId = runId,
    status = RuntimeRunStatus.CANCELLED,
    response = "Run cancelled.",
    errorCode = null,
    recoverable = false,
    completion = RuntimeCompletionSummary.unknown(),
)

private fun duplicateResult(runId: String): RuntimeResult = RuntimeResult(
    runId = runId,
    status = RuntimeRunStatus.FAILED,
    response = "Duplicate live runId $runId.",
    errorCode = RuntimeErrorCode.DUPLICATE_RUN_ID.wireCode,
    recoverable = false,
    completion = RuntimeCompletionSummary.unknown(),
)

private fun unknownWorkspaceResult(runId: String, workspaceId: String?): RuntimeResult = RuntimeResult(
    runId = runId,
    status = RuntimeRunStatus.FAILED,
    response = "Unknown runtime workspace: ${workspaceId.orEmpty()}",
    errorCode = RuntimeErrorCode.WORKSPACE_NOT_CONFIGURED.wireCode,
    recoverable = false,
    completion = RuntimeCompletionSummary.unknown(),
)

private fun pendingResult(
    runId: String,
    message: String,
    errorCode: RuntimeErrorCode,
): RuntimeResult = RuntimeResult(
    runId = runId,
    status = RuntimeRunStatus.FAILED,
    response = message,
    errorCode = errorCode.wireCode,
    recoverable = false,
    completion = RuntimeCompletionSummary.unknown(),
)

private fun failedResult(
    runId: String,
    message: String,
    recoverable: Boolean = true,
): RuntimeResult = RuntimeResult(
    runId = runId,
    status = RuntimeRunStatus.FAILED,
    response = message,
    errorCode = RuntimeErrorCode.MODEL_LOOP_FAILED.wireCode,
    recoverable = recoverable,
    completion = RuntimeCompletionSummary(
        outcome = cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome.FAILED,
    ),
)

/** The kernel runtime offers no web_fetch, so its protocol prompt does not describe it. */
private val KERNEL_PROMPT = BootstrapPromptProvider.build(tools = ProtocolTools(webFetch = false))

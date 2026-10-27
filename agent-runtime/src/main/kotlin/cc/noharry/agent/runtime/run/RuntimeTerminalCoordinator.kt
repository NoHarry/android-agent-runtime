@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.run
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.RuntimeCompletedTurn
import cc.noharry.agent.kernel.api.RuntimeTurnObserver
import cc.noharry.agent.kernel.core.loop.completed
import cc.noharry.agent.kernel.core.state.SessionRunLease
import cc.noharry.agent.kernel.core.tools.ToolActivationStore
import cc.noharry.agent.runtime.probe.CaseBinding
import cc.noharry.agent.runtime.probe.IsolationDeclaration
import cc.noharry.agent.runtime.probe.ProbePack
import cc.noharry.agent.runtime.probe.ProbeRunAttachment
import cc.noharry.agent.runtime.probe.buildRunMeta
import cc.noharry.agent.runtime.probe.snapshotAdvertisedToolset
import cc.noharry.agent.runtime.reliability.InternalRuntimeEvidenceConfig
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Observer delivery, bounded retry, Probe cleanup, and request index.
 * Ports keep persist-before-notify and observer → Probe → cleanup → lease order.
 */
internal class RuntimeTerminalCoordinator(
    private val turnObservers: List<RuntimeTurnObserver>,
    private val nowEpochMillis: () -> Long,
    private val lookupCompletionOutcome: (String) -> String?,
    private val cleanupTerminalRun: suspend (String, String?) -> Unit,
    private val activationStore: ToolActivationStore,
    private val evidenceConfig: InternalRuntimeEvidenceConfig?,
    private val probePacks: List<ProbePack>,
    private val reliabilityStorageRoot: File?,
    private val nativeWebFetchEnabled: Boolean,
    private val nativeExecEnabled: Boolean = false,
    private val disabledNativeTools: Set<String> = emptySet(),
) {
    private val turnFinishedComplete = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val turnFinishedDelivery =
        java.util.concurrent.ConcurrentHashMap<String, TurnFinishedDelivery>()
    private val runRequestIndex = java.util.concurrent.ConcurrentHashMap<String, AgentRequest>()
    private val probeCleanupOnce = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val turnFinishedMutexes =
        java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val observerNotifyJob = SupervisorJob()
    private val observerNotifyScope = CoroutineScope(observerNotifyJob + Dispatchers.Default)
    private lateinit var releaseExactLease: suspend (String, SessionRunLease?) -> Unit
    private lateinit var runtimeInstanceId: () -> String
    private lateinit var finishProbe: suspend (String, String, String?) -> Unit

    fun bindLeaseRelease(release: suspend (String, SessionRunLease?) -> Unit) {
        releaseExactLease = release
    }

    fun bindLoopFactory(
        runtimeInstanceId: () -> String,
        finishProbe: suspend (String, String, String?) -> Unit,
    ) {
        this.runtimeInstanceId = runtimeInstanceId
        this.finishProbe = finishProbe
    }

    fun indexRunRequest(request: AgentRequest, runId: String) {
        runRequestIndex[runId] = request
    }

    fun tryBeginProbeCleanup(runId: String): Boolean = probeCleanupOnce.add(runId)

    fun hasCompletedTurn(runId: String): Boolean = runId in turnFinishedComplete

    fun cancelNotifyJobs() {
        observerNotifyJob.cancel()
    }

    fun clearDeliveryState() {
        turnFinishedDelivery.clear()
        runRequestIndex.clear()
    }

    /**
     * Opens observe-only probe attachment for this run (internal).
     * Failures never propagate; empty packs / missing root → disabled attachment.
     *
     * Case binding and packs come only from validated [evidenceConfig] (host experimental API).
     * Default (null config) remains fully disabled with no hardcoded sample ids.
     */
    fun openProbeAttachment(request: AgentRequest, runId: String): ProbeRunAttachment {
        val evidence = evidenceConfig
        val packs = resolveProbePacks(evidence)
        val root = evidence?.storageRoot ?: reliabilityStorageRoot
        val casePackId = evidence?.casePackId ?: "none"
        val promptSha = evidence?.promptSha256
        val startedAt = nowEpochMillis()
        return ProbeRunAttachment.open(
            reliabilityRoot = root,
            runId = runId,
            sessionId = request.sessionId,
            requestId = request.requestId,
            packs = packs,
            casePackId = casePackId,
            runMetaBuilder = {
                buildOpenProbeRunMeta(request, runId, startedAt, packs, casePackId, promptSha, root)
            },
        )
    }

    /**
     * Unified post-persist terminal coordinator. Fixed order:
     * RuntimeTurnObserver delivery → Probe finishAndPersist → exact Hub uninstall → resource cleanup.
     *
     * Cancellation-safe: if observer delivery is cancelled, remaining observers get a bounded
     * idempotent retry inside NonCancellable, then Probe/Hub/cleanup close regardless. The original
     * CancellationException is re-thrown after closure so structured concurrency is preserved.
     *
     * When observers cannot all complete, Probe evidence carries an explicit incomplete reason —
     * never fake complete=true, never rewrite the terminal status to ABORTED on close.
     */
    suspend fun coordinateTerminalFinalization(
        runId: String,
        status: String,
        workspaceId: String? = null,
        lease: SessionRunLease? = null,
    ) {
        var cancellation: CancellationException? = null
        try {
            try {
                notifyTurnFinishedForRun(runId, status)
            } catch (ex: CancellationException) {
                cancellation = ex
            }
            closeTerminalResources(runId, status, workspaceId, lease)
        } catch (ex: CancellationException) {
            throw ex
        }
        cancellation?.let { throw it }
    }

    /**
     * Unified terminal Probe finalizer: idempotent finishAndPersist + exact registration uninstall.
     * Call only after turn observers have been notified for this run. [incompleteReason] records
     * an explicit incomplete cause in Probe evidence when observers could not all complete.
     */
    suspend fun finalizeProbeTerminal(
        runId: String,
        status: String,
        incompleteReason: String? = null,
    ) {
        try {
            finishProbe(runId, status, incompleteReason)
        } catch (_: Exception) {
            // observe-only
        }
    }

    private suspend fun closeTerminalResources(
        runId: String,
        status: String,
        workspaceId: String?,
        lease: SessionRunLease?,
    ) {
        // Resource closure is guaranteed even under cancellation (NonCancellable).
        // Exact lease is released in finally even if Probe/cleanup throws.
        withContext(NonCancellable) {
            try {
                val stillIncomplete = boundedRetryObservers(runId, status)
                if (!probeCleanupOnce.add(runId)) {
                    return@withContext
                }
                finalizeProbeTerminal(
                    runId = runId,
                    status = status,
                    incompleteReason = if (stillIncomplete) OBSERVER_DELIVERY_INCOMPLETE_REASON else null,
                )
                cleanupTerminalRun(runId, workspaceId)
            } finally {
                releaseExactLease(runId, lease)
            }
        }
    }

    /**
     * Terminal observer delivery: persist-before-notify unchanged.
     * Per-run cursor tracks which observers already succeeded so CancellationException
     * does not permanently mark the whole set delivered; retries skip completed observers.
     */
    private suspend fun notifyTurnFinished(
        request: AgentRequest,
        runId: String,
        terminalStatus: String,
    ) {
        val turn = RuntimeCompletedTurn(
            runId = runId,
            sessionId = request.sessionId,
            requestId = request.requestId,
            scene = request.scene,
            inputDigest = sha256Hex(request.input).take(16),
            terminalStatus = terminalStatus,
            referenceTimeEpochMillis = nowEpochMillis(),
            runtimeInstanceId = runtimeInstanceId(),
            completionOutcome = lookupCompletionOutcome(runId),
        )
        notifyTurnFinishedOnce(turn)
    }

    private suspend fun notifyTurnFinishedOnce(turn: RuntimeCompletedTurn) {
        if (turnObservers.isEmpty()) {
            runRequestIndex.remove(turn.runId)
            return
        }
        if (turn.runId in turnFinishedComplete) return
        val mutex = turnFinishedMutexes.getOrPut(turn.runId) { Mutex() }
        mutex.withLock {
            if (turn.runId in turnFinishedComplete) return
            deliverTurnObservers(turn)
        }
    }

    private suspend fun deliverTurnObservers(turn: RuntimeCompletedTurn) {
        val delivery = turnFinishedDelivery.computeIfAbsent(turn.runId) {
            TurnFinishedDelivery(turn)
        }
        try {
            for (observer in turnObservers) {
                if (observer.id in delivery.deliveredObserverIds) continue
                when (notifyObserverBounded(observer, delivery.turn, OBSERVER_PRIMARY_TIMEOUT_MILLIS)) {
                    ObserverNotifyOutcome.DELIVERED ->
                        delivery.deliveredObserverIds.add(observer.id)
                    ObserverNotifyOutcome.INCOMPLETE ->
                        return
                    ObserverNotifyOutcome.CANCELLED ->
                        throw CancellationException("observer delivery cancelled")
                }
            }
            markTurnDeliveryComplete(turn.runId, delivery)
        } catch (ex: CancellationException) {
            // Keep delivery cursor + runRequestIndex for resume; cleanup on close/full success.
            throw ex
        }
    }

    private fun markTurnDeliveryComplete(runId: String, delivery: TurnFinishedDelivery) {
        if (turnObservers.all { it.id in delivery.deliveredObserverIds }) {
            turnFinishedDelivery.remove(runId)
            turnFinishedComplete.add(runId)
            turnFinishedMutexes.remove(runId)
            runRequestIndex.remove(runId)
        }
    }

    private suspend fun notifyTurnFinishedForRun(runId: String, terminalStatus: String) {
        val existing = turnFinishedDelivery[runId]
        if (existing != null) {
            notifyTurnFinishedOnce(existing.turn.copy(terminalStatus = terminalStatus))
            return
        }
        val request = runRequestIndex[runId] ?: return
        notifyTurnFinished(request, runId, terminalStatus)
    }

    /**
     * Bounded idempotent retry of observer delivery inside NonCancellable. Uses the per-run
     * cursor to skip already-delivered observers. Returns true when some observers remain
     * undelivered (incomplete reason will be recorded).
     */
    private suspend fun boundedRetryObservers(runId: String, status: String): Boolean {
        if (turnObservers.isEmpty()) return false
        if (runId in turnFinishedComplete) return false
        val delivery = turnFinishedDelivery[runId] ?: return false
        val turn = delivery.turn.copy(terminalStatus = status)
        retryRemainingObservers(delivery, turn)
        return !completeRetryIfDelivered(runId, delivery)
    }

    private suspend fun retryRemainingObservers(
        delivery: TurnFinishedDelivery,
        turn: RuntimeCompletedTurn,
    ) {
        for (observer in turnObservers) {
            if (observer.id in delivery.deliveredObserverIds) continue
            when (notifyObserverBounded(observer, turn, OBSERVER_RETRY_TIMEOUT_MILLIS)) {
                ObserverNotifyOutcome.DELIVERED ->
                    delivery.deliveredObserverIds.add(observer.id)
                ObserverNotifyOutcome.INCOMPLETE,
                ObserverNotifyOutcome.CANCELLED,
                -> Unit
            }
        }
    }

    private fun completeRetryIfDelivered(runId: String, delivery: TurnFinishedDelivery): Boolean {
        val allDelivered = turnObservers.all { it.id in delivery.deliveredObserverIds }
        if (!allDelivered) {
            return false
        }
        turnFinishedDelivery.remove(runId)
        turnFinishedComplete.add(runId)
        runRequestIndex.remove(runId)
        return true
    }

    /**
     * Delivers one observer on an independent scope so a NonCancellable/hung callback cannot
     * pin the run job or [close]. Timeout and parent cancellation stop waiting; the side-effect
     * thread may continue (documented limitation).
     */
    private suspend fun notifyObserverBounded(
        observer: RuntimeTurnObserver,
        turn: RuntimeCompletedTurn,
        timeoutMillis: Long,
    ): ObserverNotifyOutcome {
        val result = CompletableDeferred<ObserverNotifyOutcome>()
        val job = observerNotifyScope.launch {
            completeObserverNotify(observer, turn, result)
        }
        return awaitObserverNotify(result, job, timeoutMillis)
    }

    private suspend fun completeObserverNotify(
        observer: RuntimeTurnObserver,
        turn: RuntimeCompletedTurn,
        result: CompletableDeferred<ObserverNotifyOutcome>,
    ) {
        try {
            observer.onTurnFinished(turn)
            result.complete(ObserverNotifyOutcome.DELIVERED)
        } catch (ex: CancellationException) {
            result.complete(ObserverNotifyOutcome.CANCELLED)
            throw ex
        } catch (_: Exception) {
            result.complete(ObserverNotifyOutcome.DELIVERED)
        }
    }

    private suspend fun awaitObserverNotify(
        result: CompletableDeferred<ObserverNotifyOutcome>,
        job: kotlinx.coroutines.Job,
        timeoutMillis: Long,
    ): ObserverNotifyOutcome {
        return try {
            withTimeout(timeoutMillis) { result.await() }
        } catch (_: TimeoutCancellationException) {
            job.cancel()
            ObserverNotifyOutcome.INCOMPLETE
        } catch (ex: CancellationException) {
            job.cancel()
            throw ex
        }
    }

    private fun resolveProbePacks(evidence: InternalRuntimeEvidenceConfig?): List<ProbePack> {
        // Host experimental config is the only production enablement path; constructor packs are
        // already resolved from allowlisted ids at build time (never raw host ProbePack inject).
        return when {
            evidence == null -> probePacks
            evidence.probesEnabled -> evidence.resolvePacks()
            else -> emptyList()
        }
    }

    private fun buildOpenProbeRunMeta(
        request: AgentRequest,
        runId: String,
        startedAt: Long,
        packs: List<ProbePack>,
        casePackId: String,
        promptSha: String?,
        root: File?,
    ) = if (root == null || packs.isEmpty()) {
        null
    } else {
        buildProbeRunMeta(
            request = request,
            runId = runId,
            startedAt = startedAt,
            packs = packs,
            casePackId = casePackId,
            promptSha256 = promptSha,
            nativeWebFetchEnabled = nativeWebFetchEnabled,
        )
    }

    /**
     * Finish-time run-meta: toolset must reflect the advertised model catalog (native +
     * activated backend tools), not an empty list. Empty toolset + comparable=true violates A6.
     */
    private fun buildProbeRunMeta(
        request: AgentRequest,
        runId: String,
        startedAt: Long,
        packs: List<ProbePack>,
        casePackId: String,
        promptSha256: String?,
        nativeWebFetchEnabled: Boolean,
    ) = buildRunMeta(
        runId = runId,
        sessionId = request.sessionId,
        armId = "default",
        buildId = "agent-runtime",
        startedAtEpochMillis = startedAt,
        provider = request.modelRef?.providerId ?: "unknown",
        protocol = "native",
        model = request.modelRef?.modelId ?: "unknown",
        baseUrlHost = "local",
        isolation = IsolationDeclaration(
            session = "runtime",
            memory = "default",
            cache = "default",
        ),
        caseBinding = CaseBinding(
            casePackId = casePackId,
            promptSha256 = promptSha256,
        ),
        enabledProbePacks = packs.map { it.packId },
        toolset = snapshotAdvertisedToolset(
            runId = runId,
            activationStore = activationStore,
            nativeWebFetchEnabled = nativeWebFetchEnabled,
            nativeExecEnabled = nativeExecEnabled,
            disabledNativeTools = disabledNativeTools,
        ),
    )

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class TurnFinishedDelivery(
        val turn: RuntimeCompletedTurn,
        val deliveredObserverIds: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap.newKeySet(),
    )
}

private enum class ObserverNotifyOutcome {
    DELIVERED,
    INCOMPLETE,
    CANCELLED,
}

/** Incomplete reason recorded in Probe evidence when observer delivery could not complete. */
internal const val OBSERVER_DELIVERY_INCOMPLETE_REASON = "observer_delivery_incomplete"

/**
 * Ordinary observer delivery bound. Close must not wait on a single attempt longer than this;
 * this is the documented reasonable timeout and must not be weakened.
 */
private const val OBSERVER_PRIMARY_TIMEOUT_MILLIS = 2_000L

/** Hard upper bound for each cancellation-recovery observer attempt during terminal cleanup. */
private const val OBSERVER_RETRY_TIMEOUT_MILLIS = 100L

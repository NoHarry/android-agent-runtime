package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.SessionRunConflictPolicy
import cc.noharry.agent.kernel.api.reliability.RuntimeBlocker
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.api.reliability.SuggestedNextAction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import cc.noharry.agent.kernel.core.ResumeFirstController
import cc.noharry.agent.kernel.core.RunStateController
import cc.noharry.agent.kernel.core.SessionCapabilityDrainLease
import cc.noharry.agent.kernel.core.SessionLaneOwnerPhase
import cc.noharry.agent.kernel.core.SessionRunAdmission
import cc.noharry.agent.kernel.core.SessionRunAdmissionReason
import cc.noharry.agent.kernel.core.SessionRunCloseSnapshot
import cc.noharry.agent.kernel.core.SessionRunLease
import cc.noharry.agent.kernel.core.SessionRunScheduler
import cc.noharry.agent.kernel.core.SessionRunSchedulerConfig
import cc.noharry.agent.kernel.core.SessionRunTransition
import cc.noharry.agent.kernel.core.WAITING_PROTOCOL_BLOCKER_CODE
import cc.noharry.agent.kernel.core.failed

/**
 * Scheduler, lease, conflict, and close helpers for [DefaultAgentRuntime].
 * Ports preserve stale-lease, process-restart-unsupported, and close-bound semantics.
 */
internal class RuntimeSessionAdmissionCoordinator(
    config: SessionRunSchedulerConfig,
    private val closed: AtomicBoolean,
    private val runStateController: RunStateController,
    private val sessionRecorder: RuntimeSessionRecorder,
    private val resumeFirstController: ResumeFirstController,
    private val runtimeIoDispatcher: CoroutineDispatcher,
    private val terminal: RuntimeTerminalCoordinator,
    private val cleanupTerminalRun: suspend (String, String?) -> Unit,
    private val cancelledObligationOrUnknown: (String) -> RuntimeCompletionSummary,
    private val onSessionDrained: suspend (SessionCapabilityDrainLease) -> Unit = {},
) {
    val scheduler = SessionRunScheduler(config)
    private val runLeases = ConcurrentHashMap<String, SessionRunLease>()

    fun leaseOf(runId: String): SessionRunLease? = runLeases[runId]

    fun clearLeases() {
        runLeases.clear()
    }

    suspend fun waitingConflictBlocker(request: AgentRequest): RuntimeBlocker? {
        val owner = scheduler.ownerQuery(request.sessionId)
        if (owner == null) {
            terminalizeStalePersistedSessionRuns(request.sessionId)
            return null
        }
        if (owner.phase != SessionLaneOwnerPhase.WAITING_CONTROL) {
            // Owner is executing/finalizing: never consult a stale persisted WAITING snapshot.
            return null
        }
        return waitingOwnerBlocker(request, owner.lease.runId)
    }

    suspend fun replaceWaitingOwner(sessionId: String): RuntimeBlocker? {
        val waiting = scheduler.waitingOwnerLease(sessionId)
        if (waiting != null) {
            return replaceLiveWaitingOwner(waiting)
        }
        val owner = scheduler.ownerQuery(sessionId)
        if (owner != null) {
            // Executing/finalizing owner: do not consult persisted WAITING.
            return null
        }
        terminalizeStalePersistedSessionRuns(sessionId)
        return null
    }

    suspend fun persistQueuedRun(request: AgentRequest, runId: String): RuntimeRunSnapshot? {
        return try {
            runtimeIo {
                runStateController.queueRun(request, runId).also {
                    sessionRecorder.startQueuedRun(request, runId)
                }
            }
        } catch (_: IllegalStateException) {
            null
        }
    }

    suspend fun attachGrantedLease(admission: SessionRunAdmission.Granted): SessionRunLease? {
        val job = currentCoroutineContext()[Job]
        return withContext(NonCancellable) {
            if (job == null) {
                scheduler.complete(admission.lease)
                return@withContext null
            }
            bindOrCompleteGrantedLease(admission, job)
        }
    }

    suspend fun admitResume(runId: String): ResumeAdmission {
        val snapshot = runtimeIo { runStateController.snapshot(runId) }
            ?: return ResumeAdmission.Rejected(SessionRunAdmissionReason.UNKNOWN_RUN)
        return when (val admission = scheduler.admitResume(snapshot.sessionId, runId)) {
            is SessionRunAdmission.Granted -> grantedResumeAdmission(admission)
            is SessionRunAdmission.Rejected -> rejectedResumeAdmission(admission, snapshot, runId)
        }
    }

    suspend fun persistRunningAfterGrant(
        runId: String,
        lease: SessionRunLease,
    ): RuntimeRunSnapshot? {
        if (!revalidateAdmittedExecution(lease)) return null
        val admitted = runtimeIo { runStateController.tryMarkRunAdmitted(runId) } ?: return null
        if (admitted.status != RuntimeRunStatus.RUNNING) return null
        runtimeIo { sessionRecorder.updateRunStatus(runId, RuntimeRunStatus.RUNNING) }
        if (!revalidateAdmittedExecution(lease)) return null
        val after = runtimeIo { runStateController.snapshot(runId) }
        return after.takeIf { it?.status == RuntimeRunStatus.RUNNING }
    }

    suspend fun persistRunningAfterResumeGrant(
        runId: String,
        lease: SessionRunLease,
    ): Boolean {
        if (!revalidateAdmittedExecution(lease)) return false
        val before = runtimeIo { runStateController.snapshot(runId) }
        if (before == null || before.status.isTerminalStatus()) return false
        if (!before.status.isResumableExecutionStatus()) return false
        val admitted = runtimeIo { runStateController.tryMarkExecutionResumed(runId) } ?: return false
        if (admitted.status != RuntimeRunStatus.RUNNING) return false
        runtimeIo { sessionRecorder.updateRunStatus(runId, RuntimeRunStatus.RUNNING) }
        if (!revalidateAdmittedExecution(lease)) return false
        val after = runtimeIo { runStateController.snapshot(runId) }
        return after != null && after.status == RuntimeRunStatus.RUNNING
    }

    suspend fun markWaitingAfterPersist(runId: String) {
        val lease = runLeases[runId] ?: return
        withContext(NonCancellable) {
            scheduler.markWaitingControl(lease)
        }
    }

    suspend fun markFinalizingIfExecuting(runId: String, lease: SessionRunLease? = runLeases[runId]) {
        val current = lease ?: return
        withContext(NonCancellable) {
            scheduler.markFinalizing(current)
        }
    }

    suspend fun releaseLease(runId: String, lease: SessionRunLease? = runLeases[runId]) {
        val current = lease ?: return
        val completion = withContext(NonCancellable) {
            scheduler.completeAndQueryDrain(current).also {
                runLeases.remove(runId, current)
            }
        }
        val drain = completion.drainLease ?: return
        try {
            onSessionDrained(drain)
        } finally {
            scheduler.finishSessionDrain(drain)
        }
    }

    suspend fun releaseExactLease(runId: String, lease: SessionRunLease?) {
        val expected = lease ?: return
        val live = runLeases[runId]
        if (live != null && live != expected) {
            return
        }
        releaseLease(runId, expected)
    }

    suspend fun terminalizeCancelledRun(runId: String, lease: SessionRunLease?) {
        withContext(NonCancellable) {
            val live = runLeases[runId]
            if (lease != null && live != null && live != lease) {
                // Stale pre-resume lease must not terminalize or release the current owner.
                return@withContext
            }
            persistCancelledOwner(runId, lease ?: live)
        }
    }

    /**
     * Fail-closed unexpected Throwable after a queued/admitted ordinary run exists.
     * Does not emit to the host collector. Does not create phantom state when no run
     * was persisted. Never terminalizes or releases a newer rotated lease.
     */
    suspend fun terminalizeUnexpectedRunFailure(runId: String, lease: SessionRunLease?) {
        withContext(NonCancellable) {
            val live = runLeases[runId]
            if (lease != null && live != null && live != lease) {
                // Stale pre-resume lease must not terminalize or release the current owner.
                return@withContext
            }
            persistUnexpectedOwnerFailure(runId, lease ?: live)
        }
    }

    suspend fun persistClosedRun(runId: String) {
        val current = runtimeIo { runStateController.snapshot(runId) }
        if (current == null || current.status.isTerminalStatus()) {
            releaseLease(runId)
            return
        }
        markFinalizingIfExecuting(runId)
        applyRuntimeResult(closedRunResult(runId))
    }

    suspend fun applyRuntimeResult(
        result: RuntimeResult,
        lease: SessionRunLease? = runLeases[result.runId],
    ): RuntimeResult {
        markFinalizingIfExecuting(result.runId, lease)
        val recorded = runtimeIo {
            runStateController.applyRuntimeResult(result).also(sessionRecorder::finishRun)
        }
        terminal.coordinateTerminalFinalization(recorded.runId, recorded.status.name, lease = lease)
        return recorded
    }

    suspend fun closeScheduledRuns() {
        val snapshot = withContext(NonCancellable) { scheduler.close() }
        try {
            withTimeoutOrNull(CLOSE_SCHEDULED_RUNS_TIMEOUT_MILLIS) {
                drainClosedSnapshot(snapshot)
            }
        } catch (_: Throwable) {
        }
        withContext(NonCancellable) {
            fallbackCloseFinalize(snapshot)
        }
    }

    fun admissionRejectionResult(
        admission: SessionRunAdmission.Rejected,
        runId: String,
    ): RuntimeResult =
        when (admission.reason) {
            SessionRunAdmissionReason.CLOSED -> closedRunResult(runId)
            SessionRunAdmissionReason.SESSION_QUEUE_FULL ->
                failedUnknownResult(
                    runId = runId,
                    errorCode = RuntimeErrorCode.SESSION_QUEUE_FULL,
                    response = "Session run queue is full.",
                )
            SessionRunAdmissionReason.RUNTIME_BUSY ->
                failedUnknownResult(
                    runId = runId,
                    errorCode = RuntimeErrorCode.RUNTIME_BUSY,
                    response = "Runtime is busy.",
                )
            SessionRunAdmissionReason.DUPLICATE_RUN_ID,
            SessionRunAdmissionReason.INVALID_IDENTITY,
            -> duplicateRunResult(runId)
            else ->
                failedUnknownResult(
                    runId = runId,
                    errorCode = RuntimeErrorCode.MODEL_LOOP_FAILED,
                    response = "Run admission was rejected.",
                )
        }

    fun closedRunResult(runId: String): RuntimeResult =
        RuntimeResult(
            runId = runId,
            status = RuntimeRunStatus.CANCELLED,
            response = "Runtime is closed.",
            errorCode = RuntimeErrorCode.RUNTIME_CLOSED.wireCode,
            recoverable = false,
            completion = RuntimeCompletionSummary.unknown(),
        )

    fun duplicateRunResult(runId: String): RuntimeResult =
        failedUnknownResult(
            runId = runId,
            errorCode = RuntimeErrorCode.DUPLICATE_RUN_ID,
            response = "Duplicate live runId $runId.",
        )

    private suspend fun replaceLiveWaitingOwner(waiting: SessionRunLease): RuntimeBlocker? {
        return when (val outcome = admitResume(waiting.runId)) {
            is ResumeAdmission.Granted -> {
                terminalizeReplacedRun(waiting.runId, outcome.lease)
                null
            }
            is ResumeAdmission.ProcessRestartUnsupported,
            is ResumeAdmission.Closed,
            -> null
            is ResumeAdmission.Rejected -> replaceRejectedBlocker(waiting.runId)
        }
    }

    private fun waitingOwnerBlocker(request: AgentRequest, runId: String): RuntimeBlocker? {
        return when (request.sessionRunConflictPolicy) {
            null -> admissionPolicyRequired(runId)
            SessionRunConflictPolicy.RESUME_CURRENT ->
                resumeFirstController.resumeFirstBlocker(request.sessionId)
                    ?: genericWaitingBlocker(runId)
            SessionRunConflictPolicy.QUEUE_NEW,
            SessionRunConflictPolicy.CANCEL_AND_REPLACE,
            -> null
        }
    }

    private fun admissionPolicyRequired(runId: String): RuntimeBlocker =
        RuntimeBlocker(
            runId = runId,
            code = RuntimeErrorCode.ADMISSION_POLICY_REQUIRED.wireCode,
            message = "A same-session waiting run requires an explicit sessionRunConflictPolicy.",
            suggestedNextAction = SuggestedNextAction.INSPECT_BLOCKER,
        )

    private fun genericWaitingBlocker(runId: String): RuntimeBlocker =
        RuntimeBlocker(
            runId = runId,
            code = WAITING_PROTOCOL_BLOCKER_CODE,
            message = "The current session owner is waiting and must be resumed or replaced.",
            suggestedNextAction = SuggestedNextAction.INSPECT_BLOCKER,
            handoff = runStateController.handoff(runId),
        )

    private fun replaceRejectedBlocker(runId: String): RuntimeBlocker =
        RuntimeBlocker(
            runId = runId,
            code = RuntimeErrorCode.ADMISSION_POLICY_REQUIRED.wireCode,
            message = "The current session owner cannot be replaced because resume admission was rejected.",
            suggestedNextAction = SuggestedNextAction.INSPECT_BLOCKER,
        )

    private fun latestPersistedWaiting(sessionId: String): RuntimeRunSnapshot? =
        runStateController.latestSnapshot(sessionId, RuntimeRunStatus.WAITING_CONFIRMATION)
            ?: runStateController.latestSnapshot(sessionId, RuntimeRunStatus.WAITING_USER_INPUT)
            ?: runStateController.latestSnapshot(sessionId, RuntimeRunStatus.WAITING_PROTOCOL_REQUEST)

    private suspend fun terminalizeReplacedRun(runId: String, lease: SessionRunLease?) {
        markFinalizingIfExecuting(runId, lease)
        val result = RuntimeResult(
            runId = runId,
            status = RuntimeRunStatus.CANCELLED,
            response = "Run was replaced by a new same-session request.",
            errorCode = RuntimeErrorCode.RUN_REPLACED.wireCode,
            recoverable = false,
            completion = cancelledObligationOrUnknown(runId),
        )
        applyRuntimeResult(result)
    }

    private suspend fun bindOrCompleteGrantedLease(
        admission: SessionRunAdmission.Granted,
        job: Job,
    ): SessionRunLease? {
        return when (scheduler.attachJob(admission.lease, job)) {
            SessionRunTransition.Applied -> {
                runLeases[admission.runId] = admission.lease
                admission.lease
            }
            is SessionRunTransition.Rejected -> {
                scheduler.complete(admission.lease)
                runLeases.remove(admission.runId, admission.lease)
                null
            }
        }
    }

    private suspend fun grantedResumeAdmission(
        admission: SessionRunAdmission.Granted,
    ): ResumeAdmission {
        val lease = attachGrantedLease(admission)
        return if (lease == null) {
            ResumeAdmission.Closed
        } else {
            ResumeAdmission.Granted(lease)
        }
    }

    private suspend fun rejectedResumeAdmission(
        admission: SessionRunAdmission.Rejected,
        snapshot: RuntimeRunSnapshot,
        runId: String,
    ): ResumeAdmission {
        return when (admission.reason) {
            SessionRunAdmissionReason.UNKNOWN_RUN -> unknownResumeAdmission(snapshot, runId, admission.reason)
            SessionRunAdmissionReason.CLOSED -> {
                persistClosedRun(runId)
                ResumeAdmission.Closed
            }
            SessionRunAdmissionReason.DUPLICATE_RESUME,
            SessionRunAdmissionReason.NOT_WAITING,
            SessionRunAdmissionReason.MISMATCHED_RUN,
            -> ResumeAdmission.Rejected(admission.reason)
            else -> ResumeAdmission.Rejected(admission.reason)
        }
    }

    private suspend fun unknownResumeAdmission(
        snapshot: RuntimeRunSnapshot,
        runId: String,
        reason: SessionRunAdmissionReason,
    ): ResumeAdmission {
        return if (!snapshot.status.isTerminalStatus()) {
            terminalizeProcessRestartUnsupported(runId)
            ResumeAdmission.ProcessRestartUnsupported
        } else {
            ResumeAdmission.Rejected(reason)
        }
    }

    private suspend fun revalidateAdmittedExecution(lease: SessionRunLease): Boolean {
        if (closed.get()) return false
        val job = currentCoroutineContext()[Job]
        return scheduler.isCurrentExecutingOwner(lease, job)
    }

    private suspend fun persistCancelledOwner(runId: String, lease: SessionRunLease?) {
        val current = runStateController.snapshot(runId)
        if (current == null || current.status.isTerminalStatus()) {
            releaseExactLease(runId, lease)
            return
        }
        markFinalizingIfExecuting(runId, lease)
        val result = RuntimeResult(
            runId = runId,
            status = RuntimeRunStatus.CANCELLED,
            response = "Run cancelled.",
            errorCode = if (closed.get()) RuntimeErrorCode.RUNTIME_CLOSED.wireCode else null,
            recoverable = false,
            completion = cancelledObligationOrUnknown(runId),
        )
        runStateController.applyRuntimeResult(result).also(sessionRecorder::finishRun)
        terminal.coordinateTerminalFinalization(runId, result.status.name, lease = lease)
    }

    private suspend fun persistUnexpectedOwnerFailure(runId: String, lease: SessionRunLease?) {
        val current = runStateController.snapshot(runId)
        if (current == null || current.status.isTerminalStatus()) {
            releaseExactLease(runId, lease)
            return
        }
        markFinalizingIfExecuting(runId, lease)
        val result = failedUnknownResult(
            runId = runId,
            errorCode = RuntimeErrorCode.MODEL_LOOP_FAILED,
            response = UNEXPECTED_RUN_FAILURE_RESPONSE,
        )
        runStateController.applyRuntimeResult(result).also(sessionRecorder::finishRun)
        terminal.coordinateTerminalFinalization(runId, result.status.name, lease = lease)
    }

    private suspend fun terminalizeStalePersistedSessionRuns(sessionId: String) {
        val stale = listOfNotNull(
            runtimeIo { latestPersistedWaiting(sessionId) },
            runtimeIo { runStateController.latestSnapshot(sessionId, RuntimeRunStatus.QUEUED) },
        ).distinctBy { it.runId }
        for (snapshot in stale) {
            val owner = scheduler.ownerQuery(sessionId)
            if (owner?.lease?.runId == snapshot.runId) continue
            terminalizeProcessRestartUnsupported(snapshot.runId)
        }
    }

    private suspend fun terminalizeProcessRestartUnsupported(runId: String) {
        val current = runtimeIo { runStateController.snapshot(runId) }
        if (current == null || current.status.isTerminalStatus()) return
        applyRuntimeResult(
            RuntimeResult(
                runId = runId,
                status = RuntimeRunStatus.CANCELLED,
                response = "Process-restart admission is unsupported.",
                errorCode = RuntimeErrorCode.PROCESS_RESTART_ADMISSION_UNSUPPORTED.wireCode,
                recoverable = false,
                completion = RuntimeCompletionSummary.unknown(),
            ),
        )
    }

    private suspend fun drainClosedSnapshot(snapshot: SessionRunCloseSnapshot) {
        val terminalized = LinkedHashSet<String>()
        persistClosedIdentities(snapshot, terminalized)
        val jobs = collectCloseableJobs(snapshot)
        for (job in jobs) {
            job.cancel()
        }
        awaitClosedJobs(jobs)
        persistRemainingLiveRuns()
    }

    private suspend fun persistClosedIdentities(
        snapshot: SessionRunCloseSnapshot,
        terminalized: MutableSet<String>,
    ) {
        for (queued in snapshot.queuedIdentities) {
            persistClosedRun(queued.runId)
            terminalized += queued.runId
        }
        for (waiting in snapshot.waitingOwnerLeases) {
            if (waiting.runId in terminalized) continue
            persistClosedRun(waiting.runId)
            terminalized += waiting.runId
        }
    }

    private suspend fun collectCloseableJobs(snapshot: SessionRunCloseSnapshot): LinkedHashSet<Job> {
        val closer = currentCoroutineContext()[Job]
        val jobs = LinkedHashSet<Job>()
        for (attached in snapshot.attachedJobs) {
            val job = attached.job.takeIf { it.isActive } ?: continue
            if (closer != null && (job === closer || job.isAncestorOf(closer))) continue
            jobs += job
        }
        return jobs
    }

    private suspend fun awaitClosedJobs(jobs: Set<Job>) {
        try {
            withTimeoutOrNull(CLOSE_JOB_AWAIT_TIMEOUT_MILLIS) {
                for (job in jobs) {
                    job.join()
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun persistRemainingLiveRuns() {
        for (runId in runLeases.keys.toList()) {
            val status = runStateController.snapshot(runId)?.status
            if (status != null && !status.isTerminalStatus()) {
                persistClosedRun(runId)
            }
        }
    }

    private suspend fun fallbackCloseFinalize(snapshot: SessionRunCloseSnapshot) {
        val seen = LinkedHashSet<String>()
        for (attached in snapshot.attachedJobs) {
            ensureClosedResourcesReleased(attached.runId, attached.lease)
            seen += attached.runId
        }
        releaseUnseenCloseTargets(snapshot, seen)
    }

    private suspend fun releaseUnseenCloseTargets(
        snapshot: SessionRunCloseSnapshot,
        seen: MutableSet<String>,
    ) {
        for (waiting in snapshot.waitingOwnerLeases) {
            if (waiting.runId in seen) continue
            ensureClosedResourcesReleased(waiting.runId, waiting)
            seen += waiting.runId
        }
        for (queued in snapshot.queuedIdentities) {
            if (queued.runId in seen) continue
            ensureClosedResourcesReleased(queued.runId, runLeases[queued.runId])
            seen += queued.runId
        }
        for (runId in runLeases.keys.toList()) {
            if (runId in seen) continue
            ensureClosedResourcesReleased(runId, runLeases[runId])
        }
    }

    private suspend fun ensureClosedResourcesReleased(runId: String, lease: SessionRunLease?) {
        persistClosedIfLive(runId, lease)
        if (!terminal.tryBeginProbeCleanup(runId)) {
            releaseExactLease(runId, lease)
            return
        }
        val status = runStateController.snapshot(runId)?.status?.name
            ?: RuntimeRunStatus.CANCELLED.name
        val incomplete = !terminal.hasCompletedTurn(runId)
        terminal.finalizeProbeTerminal(
            runId = runId,
            status = status,
            incompleteReason = if (incomplete) OBSERVER_DELIVERY_INCOMPLETE_REASON else null,
        )
        cleanupTerminalRun(runId, null)
        releaseExactLease(runId, lease)
    }

    private suspend fun persistClosedIfLive(runId: String, lease: SessionRunLease?) {
        val current = runtimeIo { runStateController.snapshot(runId) }
        if (current == null || current.status.isTerminalStatus()) {
            return
        }
        markFinalizingIfExecuting(runId, lease)
        runtimeIo {
            runStateController.applyRuntimeResult(closedRunResult(runId)).also(sessionRecorder::finishRun)
        }
    }

    private fun failedUnknownResult(
        runId: String,
        errorCode: RuntimeErrorCode,
        response: String,
    ): RuntimeResult =
        RuntimeResult(
            runId = runId,
            status = RuntimeRunStatus.FAILED,
            response = response,
            errorCode = errorCode.wireCode,
            recoverable = false,
            completion = RuntimeCompletionSummary.unknown(),
        )

    private suspend fun <T> runtimeIo(block: suspend () -> T): T =
        withContext(runtimeIoDispatcher) { block() }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun Job.isAncestorOf(other: Job): Boolean {
        var current: Job? = other.parent
        while (current != null) {
            if (current === this) return true
            current = current.parent
        }
        return false
    }
}

internal sealed class ResumeAdmission {
    data class Granted(val lease: SessionRunLease) : ResumeAdmission()
    data class Rejected(val reason: SessionRunAdmissionReason) : ResumeAdmission()
    data object ProcessRestartUnsupported : ResumeAdmission()
    data object Closed : ResumeAdmission()
}

private fun RuntimeRunStatus.isTerminalStatus(): Boolean =
    this == RuntimeRunStatus.COMPLETED ||
        this == RuntimeRunStatus.FAILED ||
        this == RuntimeRunStatus.CANCELLED

private fun RuntimeRunStatus.isWaitingControlStatus(): Boolean =
    this == RuntimeRunStatus.WAITING_CONFIRMATION ||
        this == RuntimeRunStatus.WAITING_USER_INPUT ||
        this == RuntimeRunStatus.WAITING_PROTOCOL_REQUEST

private fun RuntimeRunStatus.isResumableExecutionStatus(): Boolean =
    isWaitingControlStatus() || this == RuntimeRunStatus.RUNNING

/** Bounded join for attached run jobs during runtime close. Never throws to the host. */
private const val CLOSE_JOB_AWAIT_TIMEOUT_MILLIS = 250L

/** Total bound for scheduled-run shutdown before fallback Probe/cleanup/release. */
private const val CLOSE_SCHEDULED_RUNS_TIMEOUT_MILLIS = 500L

/** Sanitized host-visible response for unexpected ordinary-run failures. No exception text. */
private const val UNEXPECTED_RUN_FAILURE_RESPONSE = "Run failed unexpectedly."

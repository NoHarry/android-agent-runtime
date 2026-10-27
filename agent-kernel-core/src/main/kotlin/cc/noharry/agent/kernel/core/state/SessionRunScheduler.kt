package cc.noharry.agent.kernel.core.state
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Process-local session/run admission and fair execution permits.
 *
 * This scheduler owns lane state only. It does not launch a protocol loop, persist
 * admission, or reconstruct queues after process death. Process-restored admission
 * is unsupported: a new instance always starts empty.
 *
 * Ownership is lease-scoped: every grant returns an immutable generation token, and
 * every owner transition must present the exact current lease. Resume rotates the
 * lease so a stale pre-resume complete cannot release the resumed owner.
 */
class SessionRunScheduler(
    private val config: SessionRunSchedulerConfig,
) {
    private val mutex = Mutex()
    private val lanes = LinkedHashMap<String, SessionLane>()
    private val liveRuns = LinkedHashMap<String, String>()
    private val readyRing = ArrayDeque<String>()
    private var activeExecutions = 0
    private var nextLeaseId = 0L
    private var nextDrainGeneration = 0L
    private var closed = false

    /**
     * Test-only seam: invoked after a grant is decided and before the lease is
     * claimed. Production callers must leave this null.
     */
    @Volatile
    var beforeClaimGrantedForTest: (suspend () -> Unit)? = null

    /**
     * Test-only seam: invoked inside NonCancellable cleanup immediately before
     * the mutex is acquired. Production callers must leave this null.
     */
    @Volatile
    var beforeNonCancellableMutexForTest: (() -> Unit)? = null

    /**
     * Test-only seam: invoked after [attachJob] applies and releases the mutex,
     * before the caller persists RUNNING. Production callers must leave this null.
     */
    @Volatile
    var afterAttachJobForTest: (suspend () -> Unit)? = null

    init {
        require(config.maxActiveSessions > 0) { "maxActiveSessions must be positive." }
        require(config.maxQueuedRunsPerSession > 0) { "maxQueuedRunsPerSession must be positive." }
        require(config.maxQueuedRunsGlobal > 0) { "maxQueuedRunsGlobal must be positive." }
    }

    /**
     * Admits an ordinary run. Same session is FIFO with at most one owner.
     * Cross-session ready work is granted from a round-robin ring.
     */
    suspend fun admitOrdinary(sessionId: String, runId: String): SessionRunAdmission {
        val parked = mutate { signals -> decideOrdinaryLocked(sessionId, runId, signals) }
        return settleAdmission(parked)
    }

    /**
     * Reacquires execution for the current waiting owner through the ready ring.
     * Never creates a second owner. A successful resume rotates the owner lease.
     */
    suspend fun admitResume(sessionId: String, runId: String): SessionRunAdmission {
        val parked = mutate { signals -> decideResumeLocked(sessionId, runId, signals) }
        return settleAdmission(parked)
    }

    suspend fun attachJob(lease: SessionRunLease, job: Job): SessionRunTransition {
        val result = mutate { attachJobLocked(lease, job) }
        if (result == SessionRunTransition.Applied) {
            afterAttachJobForTest?.invoke()
        }
        return result
    }

    suspend fun markWaitingControl(lease: SessionRunLease): SessionRunTransition =
        mutate { signals -> markWaitingControlLocked(lease, signals) }

    suspend fun markFinalizing(lease: SessionRunLease): SessionRunTransition =
        mutate { markFinalizingLocked(lease) }

    suspend fun complete(lease: SessionRunLease): SessionRunTransition =
        mutate { signals -> completeLocked(lease, signals) }

    /**
     * Releases [lease] and, when the lane is idle, marks it DRAINING and returns
     * a [SessionCapabilityDrainLease]. Close session capabilities outside this
     * lock, then call [finishSessionDrain] with the exact token.
     */
    suspend fun completeAndQueryDrain(lease: SessionRunLease): SessionRunCompletion =
        mutate { signals ->
            val releasedOwner = currentOwner(lease) != null
            val transition = completeLocked(lease, signals)
            val drain = if (releasedOwner && transition == SessionRunTransition.Applied) {
                beginDrainIfIdleLocked(lease.sessionId)
            } else {
                lanes[lease.sessionId]?.drainLease
            }
            SessionRunCompletion(
                transition = transition,
                sessionDrained = drain != null,
                drainLease = drain,
            )
        }

    /**
     * Completes an exact drain. A stale token cannot release a newer drain.
     * After success, queued ordinary admissions dispatch fairly.
     */
    suspend fun finishSessionDrain(token: SessionCapabilityDrainLease) {
        mutate { signals -> finishSessionDrainLocked(token, signals) }
    }

    /**
     * True when the session has no owner, queued ordinary run, or pending resume.
     * A missing lane is drained. Must not perform provider I/O.
     */
    suspend fun isSessionDrained(sessionId: String): Boolean =
        mutex.withLock { isSessionIdleLocked(sessionId) }

    /**
     * Stops admission, completes parked waiters with [SessionRunAdmissionReason.CLOSED],
     * and returns current leases with attached active jobs, waiting owner leases, and
     * bounded queued identities collected exactly once before parked ordinary requests
     * are rejected. A later close returns no already-drained queued identities.
     * Attach after close is rejected, so a later attach cannot escape this snapshot.
     * In-flight [complete] of the current lease stays caller-owned and remains idempotent.
     */
    suspend fun close(): SessionRunCloseSnapshot = mutate { signals -> closeLocked(signals) }

    /** Bounded diagnostic snapshot: counts and ids only, no payload or business data. */
    suspend fun snapshot(): SessionRunSchedulerSnapshot = mutex.withLock { snapshotLocked() }

    /**
     * Atomic owner query for the live lane owner, if any. Used by runtime admission
     * to decide same-session conflict policy without a second protocol.
     */
    suspend fun ownerQuery(sessionId: String): SessionRunOwnerQuery? =
        mutex.withLock {
            val owner = lanes[sessionId]?.owner ?: return@withLock null
            SessionRunOwnerQuery(lease = owner.lease, phase = owner.phase)
        }

    /**
     * True only when [lease] is the live EXECUTING owner, the scheduler is open,
     * and [job] matches the attached job when both are present.
     */
    suspend fun isCurrentExecutingOwner(lease: SessionRunLease, job: Job?): Boolean =
        mutex.withLock {
            if (closed) return@withLock false
            val owner = currentOwner(lease) ?: return@withLock false
            if (owner.phase != SessionLaneOwnerPhase.EXECUTING) return@withLock false
            if (job != null && owner.job != null && owner.job !== job) return@withLock false
            true
        }

    /**
     * Atomic query for the current WAITING_CONTROL owner lease. Null when the
     * session has no owner or the owner is still executing/finalizing.
     */
    suspend fun waitingOwnerLease(sessionId: String): SessionRunLease? =
        mutex.withLock {
            val owner = lanes[sessionId]?.owner ?: return@withLock null
            if (owner.phase == SessionLaneOwnerPhase.WAITING_CONTROL) owner.lease else null
        }

    /**
     * Test-only: hold the scheduler mutex while [block] runs so cancellation can
     * race cleanup that is queued on the same mutex. Production must not call this.
     */
    suspend fun holdMutexForTest(block: suspend () -> Unit) {
        mutex.withLock { block() }
    }

    private suspend fun <T> mutate(action: (PendingSignals) -> T): T {
        val signals = PendingSignals()
        val result = mutex.withLock { action(signals) }
        signals.flush()
        return result
    }

    private suspend fun <T> mutateNonCancellable(action: (PendingSignals) -> T): T {
        return withContext(NonCancellable) {
            beforeNonCancellableMutexForTest?.invoke()
            mutate(action)
        }
    }

    private suspend fun settleAdmission(decision: AdmissionDecision): SessionRunAdmission {
        val admission = when (decision) {
            is AdmissionDecision.Done -> decision.admission
            is AdmissionDecision.Parked -> awaitParked(decision.request)
        }
        if (admission !is SessionRunAdmission.Granted) {
            return admission
        }
        return claimGrantedAdmission(admission)
    }

    private suspend fun claimGrantedAdmission(
        admission: SessionRunAdmission.Granted,
    ): SessionRunAdmission {
        return try {
            beforeClaimGrantedForTest?.invoke()
            val claimed = mutateNonCancellable { signals ->
                claimGrantedLocked(admission, signals)
            }
            if (claimed is SessionRunAdmission.Granted) {
                coroutineContext.ensureActive()
            }
            claimed
        } catch (cancelled: CancellationException) {
            mutateNonCancellable { signals ->
                revertGrantedAdmissionLocked(admission, signals)
            }
            throw cancelled
        }
    }

    private suspend fun awaitParked(request: ParkedRequest): SessionRunAdmission {
        return try {
            request.deferred.await()
        } catch (cancelled: CancellationException) {
            mutateNonCancellable { signals -> onAwaitCancelledLocked(request, signals) }
            throw cancelled
        }
    }

    private fun decideOrdinaryLocked(
        sessionId: String,
        runId: String,
        signals: PendingSignals,
    ): AdmissionDecision {
        rejectNewAdmission(sessionId, runId)?.let { return AdmissionDecision.Done(it) }
        val lane = laneOf(sessionId)
        if (canGrantImmediately(lane)) {
            val request = OrdinaryRequest(sessionId, runId)
            grantOrdinaryLocked(lane, request, signals)
            return AdmissionDecision.Done(request.grantedAdmission())
        }
        if (lane.ordinaryQueue.size >= config.maxQueuedRunsPerSession) {
            return AdmissionDecision.Done(
                SessionRunAdmission.Rejected(
                    reason = SessionRunAdmissionReason.SESSION_QUEUE_FULL,
                    sessionId = sessionId,
                    runId = runId,
                ),
            )
        }
        if (globalQueuedCount() >= config.maxQueuedRunsGlobal) {
            return AdmissionDecision.Done(
                SessionRunAdmission.Rejected(
                    reason = SessionRunAdmissionReason.RUNTIME_BUSY,
                    sessionId = sessionId,
                    runId = runId,
                ),
            )
        }
        val request = OrdinaryRequest(sessionId, runId)
        lane.ordinaryQueue.addLast(request)
        liveRuns[runId] = sessionId
        enqueueReadyIfEligible(lane)
        dispatchAvailableLocked(signals)
        if (request.granted) {
            return AdmissionDecision.Done(request.grantedAdmission())
        }
        return AdmissionDecision.Parked(request)
    }

    private fun decideResumeLocked(
        sessionId: String,
        runId: String,
        signals: PendingSignals,
    ): AdmissionDecision {
        if (sessionId.isBlank() || runId.isBlank()) {
            return AdmissionDecision.Done(invalidIdentity(sessionId, runId))
        }
        if (closed) {
            return AdmissionDecision.Done(closedAdmission(sessionId, runId))
        }
        val lane = lanes[sessionId]
        val owner = lane?.owner
        val rejection = resumeRejection(lane, owner, sessionId, runId)
        if (rejection != null) {
            return AdmissionDecision.Done(rejection)
        }
        checkNotNull(lane)
        checkNotNull(owner)
        val request = ResumeRequest(sessionId, runId)
        lane.pendingResume = request
        enqueueReadyIfEligible(lane)
        dispatchAvailableLocked(signals)
        if (request.granted) {
            return AdmissionDecision.Done(request.grantedAdmission())
        }
        return AdmissionDecision.Parked(request)
    }

    private fun resumeRejection(
        lane: SessionLane?,
        owner: LaneOwner?,
        sessionId: String,
        runId: String,
    ): SessionRunAdmission.Rejected? {
        if (lane == null || owner == null) {
            return SessionRunAdmission.Rejected(
                reason = SessionRunAdmissionReason.UNKNOWN_RUN,
                sessionId = sessionId,
                runId = runId,
            )
        }
        if (owner.runId != runId) {
            return SessionRunAdmission.Rejected(
                reason = SessionRunAdmissionReason.MISMATCHED_RUN,
                sessionId = sessionId,
                runId = runId,
            )
        }
        if (owner.phase != SessionLaneOwnerPhase.WAITING_CONTROL) {
            return SessionRunAdmission.Rejected(
                reason = SessionRunAdmissionReason.NOT_WAITING,
                sessionId = sessionId,
                runId = runId,
            )
        }
        if (lane.pendingResume != null) {
            return SessionRunAdmission.Rejected(
                reason = SessionRunAdmissionReason.DUPLICATE_RESUME,
                sessionId = sessionId,
                runId = runId,
            )
        }
        return null
    }

    private fun attachJobLocked(lease: SessionRunLease, job: Job): SessionRunTransition {
        rejectNonterminal(lease)?.let { return it }
        val owner = checkNotNull(currentOwner(lease))
        owner.replaceJob(job)
        return SessionRunTransition.Applied
    }

    private fun markWaitingControlLocked(
        lease: SessionRunLease,
        signals: PendingSignals,
    ): SessionRunTransition {
        rejectNonterminal(lease)?.let { return it }
        val owner = checkNotNull(currentOwner(lease))
        return when (owner.phase) {
            SessionLaneOwnerPhase.WAITING_CONTROL -> SessionRunTransition.Applied
            SessionLaneOwnerPhase.FINALIZING -> {
                SessionRunTransition.Rejected(SessionRunTransitionReason.INVALID_PHASE)
            }
            SessionLaneOwnerPhase.EXECUTING -> {
                owner.phase = SessionLaneOwnerPhase.WAITING_CONTROL
                owner.dropInactiveJob()
                releaseExecutionLocked()
                dispatchAvailableLocked(signals)
                SessionRunTransition.Applied
            }
        }
    }

    private fun markFinalizingLocked(lease: SessionRunLease): SessionRunTransition {
        rejectNonterminal(lease)?.let { return it }
        val owner = checkNotNull(currentOwner(lease))
        return when (owner.phase) {
            SessionLaneOwnerPhase.FINALIZING -> SessionRunTransition.Applied
            SessionLaneOwnerPhase.EXECUTING -> {
                owner.phase = SessionLaneOwnerPhase.FINALIZING
                SessionRunTransition.Applied
            }
            SessionLaneOwnerPhase.WAITING_CONTROL -> {
                SessionRunTransition.Rejected(SessionRunTransitionReason.INVALID_PHASE)
            }
        }
    }

    private fun completeLocked(
        lease: SessionRunLease,
        signals: PendingSignals,
    ): SessionRunTransition {
        if (lease.sessionId.isBlank() || lease.runId.isBlank()) {
            return SessionRunTransition.Rejected(SessionRunTransitionReason.INVALID_IDENTITY)
        }
        val lane = lanes[lease.sessionId] ?: return SessionRunTransition.Applied
        val owner = lane.owner
        if (owner == null || owner.runId != lease.runId) {
            return if (liveRuns[lease.runId] == lease.sessionId) {
                SessionRunTransition.Rejected(SessionRunTransitionReason.NOT_OWNER)
            } else {
                SessionRunTransition.Applied
            }
        }
        if (owner.lease != lease) {
            return SessionRunTransition.Rejected(SessionRunTransitionReason.STALE_LEASE)
        }
        if (owner.completionReleased) {
            return SessionRunTransition.Applied
        }
        owner.completionReleased = true
        owner.job = null
        owner.previousLease = null
        if (owner.holdsExecutionPermit) {
            releaseExecutionLocked()
        }
        lane.pendingResume?.takeUnless { it.granted }?.let { pending ->
            signals.complete(
                pending.deferred,
                SessionRunAdmission.Rejected(
                    reason = SessionRunAdmissionReason.UNKNOWN_RUN,
                    sessionId = lease.sessionId,
                    runId = lease.runId,
                ),
            )
        }
        lane.pendingResume = null
        lane.owner = null
        liveRuns.remove(lease.runId)
        enqueueReadyIfEligible(lane)
        clearLaneIfIdle(lease.sessionId)
        dispatchAvailableLocked(signals)
        return SessionRunTransition.Applied
    }

    private fun beginDrainIfIdleLocked(sessionId: String): SessionCapabilityDrainLease? {
        if (closed) return null
        val existing = lanes[sessionId]?.drainLease
        if (existing != null) return existing
        if (!isSessionIdleLocked(sessionId)) return null
        return startDrainLocked(laneOf(sessionId))
    }

    private fun startDrainLocked(lane: SessionLane): SessionCapabilityDrainLease {
        nextDrainGeneration += 1L
        val drain = SessionCapabilityDrainLease(lane.sessionId, nextDrainGeneration)
        lane.drainLease = drain
        if (lane.inReadyRing) {
            readyRing.remove(lane.sessionId)
            lane.inReadyRing = false
        }
        return drain
    }

    private fun finishSessionDrainLocked(
        token: SessionCapabilityDrainLease,
        signals: PendingSignals,
    ) {
        if (closed) return
        val lane = lanes[token.sessionId] ?: return
        if (lane.drainLease != token) return
        lane.drainLease = null
        enqueueReadyIfEligible(lane)
        clearLaneIfIdle(token.sessionId)
        dispatchAvailableLocked(signals)
    }

    private fun closeLocked(signals: PendingSignals): SessionRunCloseSnapshot {
        val attachedJobs = ArrayList<SessionRunAttachedJob>()
        val waitingOwnerLeases = ArrayList<SessionRunLease>()
        if (closed) {
            for (lane in lanes.values) {
                collectCloseTargets(lane, attachedJobs, waitingOwnerLeases)
            }
            return SessionRunCloseSnapshot(
                attachedJobs = attachedJobs.toList(),
                waitingOwnerLeases = waitingOwnerLeases.toList(),
                queuedIdentities = emptyList(),
            )
        }
        closed = true
        val queuedIdentities = ArrayList<SessionRunQueuedIdentity>()
        // Over a copy: reverting an unclaimed grant completes it, which removes its lane once idle, and removing from
        // the map being iterated threw ConcurrentModificationException whenever another lane came after it.
        for (lane in lanes.values.toList()) {
            lane.drainLease = null
            revertUnclaimedOwnerLocked(lane, signals)
            collectCloseTargets(lane, attachedJobs, waitingOwnerLeases)
            collectQueuedIdentities(lane, queuedIdentities)
            rejectParkedLocked(lane, signals)
        }
        readyRing.clear()
        lanes.values.forEach { it.inReadyRing = false }
        return SessionRunCloseSnapshot(
            attachedJobs = attachedJobs.toList(),
            waitingOwnerLeases = waitingOwnerLeases.toList(),
            queuedIdentities = queuedIdentities.toList(),
        )
    }

    private fun revertUnclaimedOwnerLocked(lane: SessionLane, signals: PendingSignals) {
        val owner = lane.owner ?: return
        if (owner.claimed) {
            return
        }
        if (owner.previousLease != null) {
            revertResumeGrantLocked(lane.sessionId, owner.lease, signals)
            return
        }
        completeLocked(owner.lease, signals)
    }

    private fun collectQueuedIdentities(
        lane: SessionLane,
        queuedIdentities: MutableList<SessionRunQueuedIdentity>,
    ) {
        for (request in lane.ordinaryQueue) {
            if (request.granted) {
                continue
            }
            queuedIdentities.add(
                SessionRunQueuedIdentity(
                    sessionId = lane.sessionId,
                    runId = request.runId,
                ),
            )
        }
    }

    private fun collectCloseTargets(
        lane: SessionLane,
        attachedJobs: MutableList<SessionRunAttachedJob>,
        waitingOwnerLeases: MutableList<SessionRunLease>,
    ) {
        val owner = lane.owner ?: return
        owner.dropInactiveJob()
        owner.job?.let { job ->
            attachedJobs.add(SessionRunAttachedJob(owner.lease, job))
        }
        if (owner.phase == SessionLaneOwnerPhase.WAITING_CONTROL) {
            waitingOwnerLeases.add(owner.lease)
        }
    }

    private fun rejectParkedLocked(lane: SessionLane, signals: PendingSignals) {
        val resume = lane.pendingResume
        lane.pendingResume = null
        if (resume != null && !resume.granted) {
            signals.complete(resume.deferred, closedAdmission(lane.sessionId, resume.runId))
        }
        while (lane.ordinaryQueue.isNotEmpty()) {
            val request = lane.ordinaryQueue.removeFirst()
            liveRuns.remove(request.runId)
            if (!request.granted) {
                signals.complete(request.deferred, closedAdmission(lane.sessionId, request.runId))
            }
        }
    }

    private fun snapshotLocked(): SessionRunSchedulerSnapshot {
        val owners = LinkedHashMap<String, String>()
        val ownerPhases = LinkedHashMap<String, SessionLaneOwnerPhase>()
        val waitingOwnerRunIds = ArrayList<String>()
        for (lane in lanes.values) {
            val owner = lane.owner ?: continue
            owners[lane.sessionId] = owner.runId
            ownerPhases[lane.sessionId] = owner.phase
            if (owner.phase == SessionLaneOwnerPhase.WAITING_CONTROL) {
                waitingOwnerRunIds.add(owner.runId)
            }
        }
        val drainingSessionIds = lanes.values.mapNotNull { lane ->
            lane.sessionId.takeIf { lane.drainLease != null }
        }
        return SessionRunSchedulerSnapshot(
            activeExecutionCount = activeExecutions,
            queuedCount = globalQueuedCount(),
            owners = owners.toMap(),
            readySessionIds = readyRing.toList(),
            waitingOwnerRunIds = waitingOwnerRunIds.toList(),
            ownerPhases = ownerPhases.toMap(),
            drainingSessionIds = drainingSessionIds,
        )
    }

    private fun onAwaitCancelledLocked(request: ParkedRequest, signals: PendingSignals) {
        if (request.granted) {
            revertGrantedCancellationLocked(request, signals)
            return
        }
        when (request) {
            is OrdinaryRequest -> removeOrdinaryLocked(request)
            is ResumeRequest -> {
                val lane = lanes[request.sessionId] ?: return
                if (lane.pendingResume === request) {
                    lane.pendingResume = null
                }
                lane.owner?.dropInactiveJob()
                removeFromReadyIfIneligible(lane)
            }
        }
    }

    private fun revertGrantedCancellationLocked(request: ParkedRequest, signals: PendingSignals) {
        val lease = request.lease ?: return
        revertGrantedAdmissionLocked(
            SessionRunAdmission.Granted(
                sessionId = request.sessionId,
                runId = request.runId,
                kind = request.kind,
                lease = lease,
            ),
            signals,
        )
    }

    private fun claimGrantedLocked(
        admission: SessionRunAdmission.Granted,
        signals: PendingSignals,
    ): SessionRunAdmission {
        val owner = lanes[admission.sessionId]?.owner
        if (closed) {
            if (owner != null && owner.lease == admission.lease && !owner.claimed) {
                revertGrantedAdmissionLocked(admission, signals)
            }
            return closedAdmission(admission.sessionId, admission.runId)
        }
        if (owner == null || owner.lease != admission.lease) {
            return closedAdmission(admission.sessionId, admission.runId)
        }
        owner.claimed = true
        return admission
    }

    private fun revertGrantedAdmissionLocked(
        admission: SessionRunAdmission.Granted,
        signals: PendingSignals,
    ) {
        when (admission.kind) {
            SessionRunGrantKind.ORDINARY -> {
                completeLocked(admission.lease, signals)
            }
            SessionRunGrantKind.RESUME -> {
                if (closed) {
                    completeLocked(admission.lease, signals)
                } else {
                    revertResumeGrantLocked(admission.sessionId, admission.lease, signals)
                }
            }
        }
    }

    private fun revertResumeGrantLocked(
        sessionId: String,
        lease: SessionRunLease,
        signals: PendingSignals,
    ) {
        val lane = lanes[sessionId] ?: return
        val owner = lane.owner ?: return
        if (owner.lease != lease || owner.completionReleased) {
            return
        }
        val previous = owner.previousLease
        if (previous != null) {
            owner.lease = previous
            owner.previousLease = null
        }
        owner.claimed = true
        owner.job = null
        if (owner.phase != SessionLaneOwnerPhase.EXECUTING) {
            owner.dropInactiveJob()
            return
        }
        owner.phase = SessionLaneOwnerPhase.WAITING_CONTROL
        owner.dropInactiveJob()
        releaseExecutionLocked()
        dispatchAvailableLocked(signals)
    }

    private fun removeOrdinaryLocked(request: OrdinaryRequest) {
        val lane = lanes[request.sessionId] ?: return
        lane.ordinaryQueue.removeAll { it === request }
        liveRuns.remove(request.runId)
        removeFromReadyIfIneligible(lane)
        clearLaneIfIdle(request.sessionId)
    }

    private fun rejectNewAdmission(sessionId: String, runId: String): SessionRunAdmission.Rejected? {
        if (sessionId.isBlank() || runId.isBlank()) {
            return invalidIdentity(sessionId, runId)
        }
        if (closed) {
            return closedAdmission(sessionId, runId)
        }
        if (liveRuns.containsKey(runId)) {
            return SessionRunAdmission.Rejected(
                reason = SessionRunAdmissionReason.DUPLICATE_RUN_ID,
                sessionId = sessionId,
                runId = runId,
            )
        }
        return null
    }

    private fun canGrantImmediately(lane: SessionLane): Boolean {
        return !closed &&
            lane.drainLease == null &&
            lane.owner == null &&
            lane.ordinaryQueue.isEmpty() &&
            activeExecutions < config.maxActiveSessions &&
            readyRing.isEmpty()
    }

    private fun grantOrdinaryLocked(
        lane: SessionLane,
        request: OrdinaryRequest,
        signals: PendingSignals,
    ) {
        val lease = nextLease(lane.sessionId, request.runId)
        request.granted = true
        request.lease = lease
        liveRuns[request.runId] = lane.sessionId
        lane.owner = LaneOwner(
            runId = request.runId,
            lease = lease,
            phase = SessionLaneOwnerPhase.EXECUTING,
        )
        acquireExecutionLocked()
        signals.complete(request.deferred, request.grantedAdmission())
    }

    private fun grantResumeLocked(
        lane: SessionLane,
        request: ResumeRequest,
        signals: PendingSignals,
    ) {
        val owner = checkNotNull(lane.owner)
        val lease = nextLease(lane.sessionId, request.runId)
        request.granted = true
        request.lease = lease
        lane.pendingResume = null
        owner.previousLease = owner.lease
        owner.lease = lease
        owner.claimed = false
        owner.job = null
        owner.phase = SessionLaneOwnerPhase.EXECUTING
        acquireExecutionLocked()
        signals.complete(request.deferred, request.grantedAdmission())
    }

    private fun dispatchAvailableLocked(signals: PendingSignals) {
        if (closed) {
            return
        }
        while (activeExecutions < config.maxActiveSessions && readyRing.isNotEmpty()) {
            val sessionId = readyRing.removeFirst()
            val lane = lanes[sessionId]
            if (lane == null) {
                continue
            }
            lane.inReadyRing = false
            if (!grantReadyLaneLocked(lane, signals)) {
                continue
            }
        }
    }

    private fun grantReadyLaneLocked(lane: SessionLane, signals: PendingSignals): Boolean {
        val resume = lane.pendingResume
        val owner = lane.owner
        if (resume != null && owner?.phase == SessionLaneOwnerPhase.WAITING_CONTROL) {
            grantResumeLocked(lane, resume, signals)
            return true
        }
        if (owner == null && lane.ordinaryQueue.isNotEmpty()) {
            val request = lane.ordinaryQueue.removeFirst()
            grantOrdinaryLocked(lane, request, signals)
            return true
        }
        return false
    }

    private fun enqueueReadyIfEligible(lane: SessionLane) {
        if (closed || lane.inReadyRing || !isReadyEligible(lane)) {
            return
        }
        readyRing.addLast(lane.sessionId)
        lane.inReadyRing = true
    }

    private fun removeFromReadyIfIneligible(lane: SessionLane) {
        if (lane.inReadyRing && !isReadyEligible(lane)) {
            readyRing.remove(lane.sessionId)
            lane.inReadyRing = false
        }
        clearLaneIfIdle(lane.sessionId)
    }

    private fun isReadyEligible(lane: SessionLane): Boolean {
        if (lane.drainLease != null) {
            return false
        }
        val owner = lane.owner
        if (owner == null) {
            return lane.ordinaryQueue.isNotEmpty()
        }
        return owner.phase == SessionLaneOwnerPhase.WAITING_CONTROL && lane.pendingResume != null
    }

    private fun currentOwner(lease: SessionRunLease): LaneOwner? {
        if (lease.sessionId.isBlank() || lease.runId.isBlank()) {
            return null
        }
        val owner = lanes[lease.sessionId]?.owner ?: return null
        return owner.takeIf { it.runId == lease.runId && it.lease == lease }
    }

    private fun rejectNonterminal(lease: SessionRunLease): SessionRunTransition.Rejected? {
        if (lease.sessionId.isBlank() || lease.runId.isBlank()) {
            return SessionRunTransition.Rejected(SessionRunTransitionReason.INVALID_IDENTITY)
        }
        if (closed) {
            return SessionRunTransition.Rejected(SessionRunTransitionReason.CLOSED)
        }
        if (currentOwner(lease) == null) {
            return rejectOwner(lease)
        }
        return null
    }

    private fun rejectOwner(lease: SessionRunLease): SessionRunTransition.Rejected {
        if (lease.sessionId.isBlank() || lease.runId.isBlank()) {
            return SessionRunTransition.Rejected(SessionRunTransitionReason.INVALID_IDENTITY)
        }
        val owner = lanes[lease.sessionId]?.owner
        if (owner != null && owner.runId == lease.runId && owner.lease != lease) {
            return SessionRunTransition.Rejected(SessionRunTransitionReason.STALE_LEASE)
        }
        return SessionRunTransition.Rejected(SessionRunTransitionReason.NOT_OWNER)
    }

    private fun laneOf(sessionId: String): SessionLane =
        lanes.getOrPut(sessionId) { SessionLane(sessionId) }

    private fun isSessionIdleLocked(sessionId: String): Boolean {
        val lane = lanes[sessionId] ?: return true
        return lane.owner == null &&
            lane.ordinaryQueue.isEmpty() &&
            lane.pendingResume == null &&
            lane.drainLease == null
    }

    private fun clearLaneIfIdle(sessionId: String) {
        val lane = lanes[sessionId] ?: return
        if (!isSessionIdleLocked(sessionId)) {
            return
        }
        if (lane.inReadyRing) {
            readyRing.remove(sessionId)
            lane.inReadyRing = false
        }
        lanes.remove(sessionId)
    }

    private fun globalQueuedCount(): Int = lanes.values.sumOf { it.ordinaryQueue.size }

    private fun nextLease(sessionId: String, runId: String): SessionRunLease {
        nextLeaseId += 1L
        return SessionRunLease(
            sessionId = sessionId,
            runId = runId,
            id = nextLeaseId,
        )
    }

    private fun acquireExecutionLocked() {
        check(activeExecutions < config.maxActiveSessions)
        activeExecutions += 1
    }

    private fun releaseExecutionLocked() {
        check(activeExecutions > 0)
        activeExecutions -= 1
    }

    private fun closedAdmission(sessionId: String, runId: String): SessionRunAdmission.Rejected =
        SessionRunAdmission.Rejected(
            reason = SessionRunAdmissionReason.CLOSED,
            sessionId = sessionId,
            runId = runId,
        )

    private fun invalidIdentity(sessionId: String, runId: String): SessionRunAdmission.Rejected =
        SessionRunAdmission.Rejected(
            reason = SessionRunAdmissionReason.INVALID_IDENTITY,
            sessionId = sessionId.ifBlank { null },
            runId = runId.ifBlank { null },
        )
}

private sealed interface ParkedRequest {
    val sessionId: String
    val runId: String
    val deferred: CompletableDeferred<SessionRunAdmission>
    var granted: Boolean
    var lease: SessionRunLease?
    val kind: SessionRunGrantKind

    fun grantedAdmission(): SessionRunAdmission.Granted =
        SessionRunAdmission.Granted(
            sessionId = sessionId,
            runId = runId,
            kind = kind,
            lease = checkNotNull(lease),
        )
}

private class OrdinaryRequest(
    override val sessionId: String,
    override val runId: String,
) : ParkedRequest {
    override val deferred = CompletableDeferred<SessionRunAdmission>()
    override var granted: Boolean = false
    override var lease: SessionRunLease? = null
    override val kind: SessionRunGrantKind = SessionRunGrantKind.ORDINARY
}

private class ResumeRequest(
    override val sessionId: String,
    override val runId: String,
) : ParkedRequest {
    override val deferred = CompletableDeferred<SessionRunAdmission>()
    override var granted: Boolean = false
    override var lease: SessionRunLease? = null
    override val kind: SessionRunGrantKind = SessionRunGrantKind.RESUME
}

private class SessionLane(
    val sessionId: String,
) {
    var owner: LaneOwner? = null
    val ordinaryQueue = ArrayDeque<OrdinaryRequest>()
    var pendingResume: ResumeRequest? = null
    var inReadyRing: Boolean = false
    var drainLease: SessionCapabilityDrainLease? = null
}

private class LaneOwner(
    val runId: String,
    var lease: SessionRunLease,
    var phase: SessionLaneOwnerPhase,
    var job: Job? = null,
    var completionReleased: Boolean = false,
    var claimed: Boolean = false,
    var previousLease: SessionRunLease? = null,
) {
    val holdsExecutionPermit: Boolean
        get() = phase == SessionLaneOwnerPhase.EXECUTING ||
            phase == SessionLaneOwnerPhase.FINALIZING

    fun dropInactiveJob() {
        val current = job ?: return
        if (!current.isActive) {
            job = null
        }
    }

    fun replaceJob(next: Job) {
        val current = job
        if (current != null && current.isActive && !next.isActive) {
            return
        }
        job = next.takeIf { it.isActive }
    }
}

private sealed interface AdmissionDecision {
    data class Done(val admission: SessionRunAdmission) : AdmissionDecision
    data class Parked(val request: ParkedRequest) : AdmissionDecision
}

private class PendingSignals {
    private val admissions = ArrayList<Pair<CompletableDeferred<SessionRunAdmission>, SessionRunAdmission>>()

    fun complete(
        deferred: CompletableDeferred<SessionRunAdmission>,
        admission: SessionRunAdmission,
    ) {
        admissions.add(deferred to admission)
    }

    fun flush() {
        for ((deferred, admission) in admissions) {
            deferred.complete(admission)
        }
    }
}

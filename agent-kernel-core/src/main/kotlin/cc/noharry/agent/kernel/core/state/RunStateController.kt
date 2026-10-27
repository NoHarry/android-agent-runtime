package cc.noharry.agent.kernel.core.state
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.reliability.OpenConfusion
import cc.noharry.agent.kernel.api.reliability.RuntimeBlocker
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidence
import cc.noharry.agent.kernel.api.reliability.RuntimeHandoff
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunEnvelope
import cc.noharry.agent.kernel.api.reliability.RuntimeRunPhase
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeTraceEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeWorkpad
import cc.noharry.agent.kernel.api.reliability.SuggestedNextAction
import cc.noharry.agent.kernel.core.loop.NativeLoopSummary
import cc.noharry.agent.kernel.core.tools.McpDebugCallRecord
import cc.noharry.agent.kernel.core.trace.DefaultTraceRedactor
import cc.noharry.agent.kernel.core.trace.TraceRedactor
import java.util.concurrent.ConcurrentHashMap

class RunStateController(
    private val nowEpochMillis: () -> Long,
    traceRedactor: TraceRedactor = DefaultTraceRedactor,
    private val storage: ReliabilityStorage? = null,
    private val locks: RunStateLockRegistry = RunStateLockRegistry(),
    private val retentionPolicy: RuntimeStateRetentionPolicy = RuntimeStateRetentionPolicy(),
) {
    private val runs = ConcurrentHashMap<String, CachedRunState>()
    private val artifacts = RunArtifactsController(
        nowEpochMillis = nowEpochMillis,
        traceRedactor = traceRedactor,
        storage = storage,
        locks = locks,
        retentionPolicy = retentionPolicy,
    )
    private val openConfusions = ConcurrentHashMap<String, OpenConfusion>()

    fun startRun(request: AgentRequest, runId: String): RuntimeRunSnapshot =
        createRun(
            request = request,
            runId = runId,
            status = RuntimeRunStatus.RUNNING,
            phase = RuntimeRunPhase.MODEL_RUNNING,
        )

    fun queueRun(request: AgentRequest, runId: String): RuntimeRunSnapshot =
        createRun(
            request = request,
            runId = runId,
            status = RuntimeRunStatus.QUEUED,
            phase = RuntimeRunPhase.QUEUED,
        )

    fun markRunAdmitted(runId: String): RuntimeRunSnapshot {
        return locks.withRunLock(runId) {
            val current = runs[runId] ?: loadRunState(runId)?.also { runs[runId] = it }
                ?: error("Cannot admit unknown run $runId.")
            admitQueuedLocked(current, runId)
                ?: error("Cannot admit run $runId from ${current.snapshot.status}.")
        }
    }

    /** Fail-closed admit: terminal/unknown/non-queued states return null instead of throwing. */
    fun tryMarkRunAdmitted(runId: String): RuntimeRunSnapshot? {
        return locks.withRunLock(runId) {
            val current = runs[runId] ?: loadRunState(runId)?.also { runs[runId] = it }
                ?: return@withRunLock null
            admitQueuedLocked(current, runId)
        }
    }

    private fun admitQueuedLocked(
        current: CachedRunState,
        runId: String,
    ): RuntimeRunSnapshot? {
        return when (current.snapshot.status) {
            RuntimeRunStatus.RUNNING -> current.snapshot
            RuntimeRunStatus.QUEUED -> {
                val updated = current.transition(
                    status = RuntimeRunStatus.RUNNING,
                    phase = RuntimeRunPhase.MODEL_RUNNING,
                    suggestedNextAction = null,
                    pendingConfirmationId = null,
                    now = nowEpochMillis(),
                )
                runs[runId] = updated
                persistLatestRun(runId, updated)
                artifacts.recordStatusChanged(runId)
                updated.snapshot
            }
            else -> null
        }
    }

    private fun createRun(
        request: AgentRequest,
        runId: String,
        status: RuntimeRunStatus,
        phase: RuntimeRunPhase,
    ): RuntimeRunSnapshot {
        val now = nowEpochMillis()
        val created = locks.withRunLock(runId) {
            val existing = runs[runId] ?: loadRunState(runId)
            if (existing != null) {
                error("Duplicate live runId $runId.")
            }
            val snapshot = RuntimeRunSnapshot(
                runId = runId,
                sessionId = request.sessionId,
                status = status,
                updatedAtEpochMillis = now,
            )
            val envelope = RuntimeRunEnvelope(
                runId = runId,
                requestId = request.requestId,
                sessionId = request.sessionId,
                status = status,
                phase = phase,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
            val cached = CachedRunState(snapshot, envelope)
            runs[runId] = cached
            persistLatestRun(runId, cached)
            cached
        }
        artifacts.createWorkpad(runId, now)
        artifacts.recordRunCreated(runId)
        return created.snapshot
    }

    fun envelope(runId: String): RuntimeRunEnvelope? =
        runState(runId)?.envelope

    fun snapshot(runId: String): RuntimeRunSnapshot? =
        runState(runId)?.snapshot

    fun workpad(runId: String): RuntimeWorkpad? = artifacts.workpad(runId)

    fun result(runId: String): RuntimeResult? = artifacts.result(runId)

    fun evidence(evidenceId: String): RuntimeEvidence? = artifacts.evidence(evidenceId)

    fun handoff(runId: String): RuntimeHandoff? = artifacts.handoff(runId)

    fun openConfusion(runId: String): OpenConfusion? =
        openConfusions[runId] ?: storage?.readOpenConfusion(runId)

    fun traceEvents(runId: String): List<RuntimeTraceEvent> =
        artifacts.traceEvents(runId)

    fun latestSnapshot(sessionId: String, status: RuntimeRunStatus): RuntimeRunSnapshot? =
        listOfNotNull(
            runs.values.asSequence()
                .map(CachedRunState::snapshot)
                .filter { it.sessionId == sessionId && it.status == status }
                .maxWithOrNull(snapshotComparator),
            storage?.latestSnapshot(sessionId, status),
        ).maxWithOrNull(snapshotComparator)

    fun recordNativeLoopSummary(runId: String, summary: NativeLoopSummary) {
        artifacts.recordNativeLoopSummary(runId, summary)
    }

    fun recordMcpDebugCall(record: McpDebugCallRecord) {
        artifacts.recordMcpDebugCall(record)
    }

    fun markWaitingConfirmation(
        started: RuntimeRunSnapshot,
        prompt: String,
        pendingConfirmationId: String,
        summary: NativeLoopSummary? = null,
    ): RuntimeHandoff {
        transition(
            runId = started.runId,
            status = RuntimeRunStatus.WAITING_CONFIRMATION,
            phase = RuntimeRunPhase.WAITING_CONFIRMATION,
            suggestedNextAction = SuggestedNextAction.RESUME_CONFIRMATION,
            pendingConfirmationId = pendingConfirmationId,
        )
        val structured = summary.toStructuredHandoff("Confirmation required: $prompt")
        return artifacts.recordHandoff(
            runId = started.runId,
            sessionId = started.sessionId,
            summary = "Run is waiting for host confirmation.",
            done = emptyList(),
            nextActions = listOf(prompt),
            task = "Resolve confirmation for run ${started.runId}.",
            decisions = structured.decisions,
            triedAndAbandoned = structured.triedAndAbandoned,
            doNotRepeat = structured.doNotRepeat,
        )
    }

    fun markWaitingUserInput(
        started: RuntimeRunSnapshot,
        question: String,
        summary: NativeLoopSummary? = null,
    ): WaitingUserInputState {
        transition(
            runId = started.runId,
            status = RuntimeRunStatus.WAITING_USER_INPUT,
            phase = RuntimeRunPhase.WAITING_USER_INPUT,
            suggestedNextAction = SuggestedNextAction.START_NEW_AGENT_REQUEST,
        )
        val structured = summary.toStructuredHandoff("NeedsUserInput: $question")
        val handoff = artifacts.recordHandoff(
            runId = started.runId,
            sessionId = started.sessionId,
            summary = "Run is waiting for user input.",
            done = emptyList(),
            nextActions = listOf(question),
            task = "Resume run ${started.runId} with the requested user input.",
            decisions = structured.decisions,
            triedAndAbandoned = structured.triedAndAbandoned,
            doNotRepeat = structured.doNotRepeat,
        )
        val confusion = OpenConfusion(
            runId = started.runId,
            sessionId = started.sessionId,
            question = question,
            suggestedNextAction = SuggestedNextAction.START_NEW_AGENT_REQUEST,
            createdAtEpochMillis = nowEpochMillis(),
        )
        locks.withRunLock(started.runId) { openConfusions[started.runId] = confusion }
        storage?.saveOpenConfusion(confusion)
        val blocker = artifacts.recordBlocker(
            RuntimeBlocker(
                runId = started.runId,
                code = RuntimeErrorCode.WAITING_USER_INPUT.wireCode,
                message = question,
                suggestedNextAction = SuggestedNextAction.START_NEW_AGENT_REQUEST,
                handoff = handoff,
            ),
        )
        return WaitingUserInputState(handoff = handoff, openConfusion = confusion, blocker = blocker)
    }

    fun markWaitingProtocolRequest(started: RuntimeRunSnapshot) {
        transition(
            runId = started.runId,
            status = RuntimeRunStatus.WAITING_PROTOCOL_REQUEST,
            phase = RuntimeRunPhase.WAITING_PROTOCOL_REQUEST,
            suggestedNextAction = SuggestedNextAction.RESOLVE_PROTOCOL_REQUEST,
        )
    }

    fun markUserInputResumed(runId: String) {
        markExecutionResumed(runId)
    }

    fun markExecutionResumed(runId: String): RuntimeRunSnapshot {
        return locks.withRunLock(runId) {
            val current = runs[runId] ?: loadRunState(runId)?.also { runs[runId] = it }
                ?: error("Cannot resume unknown run $runId.")
            resumeWaitingLocked(current, runId)
                ?: error("Cannot resume run $runId from ${current.snapshot.status}.")
        }
    }

    /** Fail-closed resume: terminal/unknown/non-waiting states return null instead of throwing. */
    fun tryMarkExecutionResumed(runId: String): RuntimeRunSnapshot? {
        return locks.withRunLock(runId) {
            val current = runs[runId] ?: loadRunState(runId)?.also { runs[runId] = it }
                ?: return@withRunLock null
            resumeWaitingLocked(current, runId)
        }
    }

    private fun resumeWaitingLocked(
        current: CachedRunState,
        runId: String,
    ): RuntimeRunSnapshot? {
        return when (current.snapshot.status) {
            RuntimeRunStatus.RUNNING -> current.snapshot
            RuntimeRunStatus.WAITING_CONFIRMATION,
            RuntimeRunStatus.WAITING_USER_INPUT,
            RuntimeRunStatus.WAITING_PROTOCOL_REQUEST,
            -> {
                val updated = current.transition(
                    status = RuntimeRunStatus.RUNNING,
                    phase = RuntimeRunPhase.MODEL_RUNNING,
                    suggestedNextAction = null,
                    pendingConfirmationId = null,
                    now = nowEpochMillis(),
                )
                runs[runId] = updated
                persistLatestRun(runId, updated)
                artifacts.recordStatusChanged(runId)
                updated.snapshot
            }
            else -> null
        }
    }

    fun markProtocolRequestResolved(runId: String) {
        transition(
            runId = runId,
            status = RuntimeRunStatus.RUNNING,
            phase = RuntimeRunPhase.MODEL_RUNNING,
            suggestedNextAction = null,
        )
    }

    fun markProtocolRequestTerminal(runId: String, status: RuntimeRunStatus) {
        locks.withRunLock(runId) {
            transition(
                runId = runId,
                status = status,
                phase = RuntimeRunPhase.TERMINAL,
                suggestedNextAction = null,
            )
        }
        evictTerminalRuns()
    }

    fun applyTerminalState(
        runId: String?,
        status: RuntimeRunStatus?,
        response: String?,
        completion: cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary =
            cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary.unknown(),
    ) {
        if (runId == null || status == null || response == null) {
            return
        }
        locks.withRunLock(runId) {
            val transition = transition(
                runId = runId,
                status = status,
                phase = RuntimeRunPhase.TERMINAL,
                suggestedNextAction = null,
            )
            artifacts.recordTerminalResult(
                RuntimeResult(
                    runId = runId,
                    status = transition.terminalStatus ?: status,
                    response = response,
                    completion = completion,
                ),
            )
        }
        evictTerminalRuns()
    }

    fun applyRuntimeResult(result: RuntimeResult): RuntimeResult {
        val recorded = locks.withRunLock(result.runId) {
            val transition = transition(
                runId = result.runId,
                status = result.status,
                phase = RuntimeRunPhase.TERMINAL,
                suggestedNextAction = null,
            )
            artifacts.recordTerminalResult(
                result.copy(status = transition.terminalStatus ?: result.status),
            )
        }
        evictTerminalRuns()
        return recorded
    }

    private fun transition(
        runId: String,
        status: RuntimeRunStatus,
        phase: RuntimeRunPhase,
        suggestedNextAction: SuggestedNextAction?,
        pendingConfirmationId: String? = null,
    ): RunTransition = locks.withRunLock(runId) {
        val current = runs[runId] ?: loadRunState(runId)
            ?: return@withRunLock RunTransition()
        if (current.snapshot.status.isTerminalRunStatus()) {
            return@withRunLock RunTransition(state = current)
        }
        val updated = current.transition(
            status = status,
            phase = phase,
            suggestedNextAction = suggestedNextAction,
            pendingConfirmationId = pendingConfirmationId,
            now = nowEpochMillis(),
        )
        runs[runId] = updated
        persistLatestRun(runId, updated)
        artifacts.recordStatusChanged(runId)
        RunTransition(state = updated, applied = true)
    }

    fun cachedRunCount(): Int = runs.size

    fun cachedTerminalRunCount(): Int =
        runs.values.count { it.snapshot.status.isTerminalRunStatus() }

    fun cachedTraceCount(runId: String): Int = artifacts.cachedTraceCount(runId)

    fun cachedEvidenceCount(runId: String): Int = artifacts.cachedEvidenceCount(runId)

    fun cachedResultCount(): Int = artifacts.cachedResultCount()

    fun cachedWorkpadCount(): Int = artifacts.cachedWorkpadCount()

    private fun runState(runId: String): CachedRunState? {
        runs[runId]?.let { return it }
        val loaded = loadRunState(runId) ?: return null
        val cached = locks.withRunLock(runId) { runs[runId] ?: loaded.also { runs[runId] = it } }
        if (cached.snapshot.status.isTerminalRunStatus()) {
            evictTerminalRuns()
        }
        return cached
    }

    private fun loadRunState(runId: String): CachedRunState? {
        val snapshot = storage?.readSnapshot(runId) ?: return null
        val envelope = storage.readEnvelope(runId) ?: return null
        return CachedRunState(snapshot, envelope)
    }

    private fun persistLatestRun(runId: String, initial: CachedRunState) {
        val targetStorage = storage ?: return
        var candidate = initial
        while (true) {
            targetStorage.saveRun(candidate.snapshot, candidate.envelope)
            val latest = runs[runId] ?: return
            if (latest === candidate) {
                return
            }
            candidate = latest
        }
    }

    private fun evictTerminalRuns() {
        while (true) {
            val terminal = runs.values
                .filter { it.snapshot.status.isTerminalRunStatus() }
                .sortedWith(cachedRunComparator)
            val overflow = terminal.size - retentionPolicy.maxTerminalRuns
            if (overflow <= 0) {
                return
            }
            if (!terminal.take(overflow).any(::evictRun)) {
                return
            }
        }
    }

    private fun evictRun(cached: CachedRunState): Boolean {
        val runId = cached.snapshot.runId
        return locks.withRunLock(runId) {
            if (!runs.remove(runId, cached)) {
                return@withRunLock false
            }
            openConfusions.remove(runId)
            artifacts.evictRun(runId)
            locks.retire(runId)
            true
        }
    }
}

private data class CachedRunState(
    val snapshot: RuntimeRunSnapshot,
    val envelope: RuntimeRunEnvelope,
) {
    fun transition(
        status: RuntimeRunStatus,
        phase: RuntimeRunPhase,
        suggestedNextAction: SuggestedNextAction?,
        pendingConfirmationId: String?,
        now: Long,
    ): CachedRunState = copy(
        snapshot = snapshot.copy(
            status = status,
            updatedAtEpochMillis = now,
            suggestedNextAction = suggestedNextAction,
            pendingConfirmationId = pendingConfirmationId,
        ),
        envelope = envelope.copy(
            status = status,
            phase = phase,
            updatedAtEpochMillis = now,
        ),
    )
}

private data class RunTransition(
    val state: CachedRunState? = null,
    val applied: Boolean = false,
) {
    val terminalStatus: RuntimeRunStatus?
        get() = state?.snapshot?.status?.takeIf(RuntimeRunStatus::isTerminalRunStatus)
}

private val snapshotComparator = compareBy<RuntimeRunSnapshot>(
    RuntimeRunSnapshot::updatedAtEpochMillis,
    RuntimeRunSnapshot::runId,
)

private val cachedRunComparator = compareBy<CachedRunState>(
    { it.snapshot.updatedAtEpochMillis },
    { it.snapshot.runId },
)

private data class StructuredHandoffFields(
    val decisions: List<String>,
    val triedAndAbandoned: List<String>,
    val doNotRepeat: List<String>,
)

private fun NativeLoopSummary?.toStructuredHandoff(reason: String): StructuredHandoffFields {
    val summary = this
    return StructuredHandoffFields(
        decisions = (listOf(reason) + summary?.toolRegistryFailures.orEmpty().map { "Encountered: $it" })
            .distinct(),
        triedAndAbandoned = summary?.planSummaries.orEmpty(),
        doNotRepeat = summary?.toolRegistryFailures.orEmpty(),
    )
}

fun RuntimeRunStatus.isTerminalRunStatus(): Boolean =
    this == RuntimeRunStatus.COMPLETED ||
        this == RuntimeRunStatus.FAILED ||
        this == RuntimeRunStatus.CANCELLED

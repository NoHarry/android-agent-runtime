package cc.noharry.agent.kernel.core.state
import cc.noharry.agent.spi.InternalAgentRuntimeApi
import kotlinx.coroutines.Job

internal const val DEFAULT_MAX_ACTIVE_SESSIONS = 4
internal const val DEFAULT_MAX_QUEUED_RUNS_PER_SESSION = 16
internal const val DEFAULT_MAX_QUEUED_RUNS_GLOBAL = 64

internal data class SessionRunSchedulerConfig(
    val maxActiveSessions: Int,
    val maxQueuedRunsPerSession: Int,
    val maxQueuedRunsGlobal: Int,
)

internal fun defaultSessionRunSchedulerConfig(): SessionRunSchedulerConfig =
    SessionRunSchedulerConfig(
        maxActiveSessions = DEFAULT_MAX_ACTIVE_SESSIONS,
        maxQueuedRunsPerSession = DEFAULT_MAX_QUEUED_RUNS_PER_SESSION,
        maxQueuedRunsGlobal = DEFAULT_MAX_QUEUED_RUNS_GLOBAL,
    )

internal enum class SessionLaneOwnerPhase {
    EXECUTING,
    WAITING_CONTROL,
    FINALIZING,
}

internal enum class SessionRunGrantKind {
    ORDINARY,
    RESUME,
}

internal enum class SessionRunAdmissionReason {
    CLOSED,
    DUPLICATE_RUN_ID,
    SESSION_QUEUE_FULL,
    RUNTIME_BUSY,
    UNKNOWN_RUN,
    NOT_WAITING,
    MISMATCHED_RUN,
    DUPLICATE_RESUME,
    INVALID_IDENTITY,
}

internal enum class SessionRunTransitionReason {
    NOT_OWNER,
    INVALID_PHASE,
    INVALID_IDENTITY,
    STALE_LEASE,
    CLOSED,
}

internal data class SessionRunLease(
    val sessionId: String,
    val runId: String,
    val id: Long,
)

internal data class SessionCapabilityDrainLease(
    val sessionId: String,
    val generation: Long,
)

internal data class SessionRunOwnerQuery(
    val lease: SessionRunLease,
    val phase: SessionLaneOwnerPhase,
)

internal sealed interface SessionRunAdmission {
    data class Granted(
        val sessionId: String,
        val runId: String,
        val kind: SessionRunGrantKind,
        val lease: SessionRunLease,
    ) : SessionRunAdmission

    data class Rejected(
        val reason: SessionRunAdmissionReason,
        val sessionId: String? = null,
        val runId: String? = null,
    ) : SessionRunAdmission
}

internal sealed interface SessionRunTransition {
    data object Applied : SessionRunTransition
    data class Rejected(val reason: SessionRunTransitionReason) : SessionRunTransition
}

/** @suppress */
@InternalAgentRuntimeApi
internal data class SessionRunSchedulerSnapshot(
    val activeExecutionCount: Int,
    val queuedCount: Int,
    val owners: Map<String, String>,
    val readySessionIds: List<String>,
    val waitingOwnerRunIds: List<String>,
    val ownerPhases: Map<String, SessionLaneOwnerPhase>,
    val drainingSessionIds: List<String> = emptyList(),
)

internal data class SessionRunAttachedJob(
    val lease: SessionRunLease,
    val job: Job,
) {
    val sessionId: String get() = lease.sessionId
    val runId: String get() = lease.runId
}

internal data class SessionRunQueuedIdentity(
    val sessionId: String,
    val runId: String,
)

internal data class SessionRunCloseSnapshot(
    val attachedJobs: List<SessionRunAttachedJob>,
    val waitingOwnerLeases: List<SessionRunLease>,
    val queuedIdentities: List<SessionRunQueuedIdentity> = emptyList(),
)

internal data class SessionRunCompletion(
    val transition: SessionRunTransition,
    val sessionDrained: Boolean,
    val drainLease: SessionCapabilityDrainLease? = null,
)

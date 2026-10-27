package cc.noharry.agent.kernel.core.state
import kotlinx.coroutines.Job

const val DEFAULT_MAX_ACTIVE_SESSIONS = 4
const val DEFAULT_MAX_QUEUED_RUNS_PER_SESSION = 16
const val DEFAULT_MAX_QUEUED_RUNS_GLOBAL = 64

data class SessionRunSchedulerConfig(
    val maxActiveSessions: Int,
    val maxQueuedRunsPerSession: Int,
    val maxQueuedRunsGlobal: Int,
)

fun defaultSessionRunSchedulerConfig(): SessionRunSchedulerConfig =
    SessionRunSchedulerConfig(
        maxActiveSessions = DEFAULT_MAX_ACTIVE_SESSIONS,
        maxQueuedRunsPerSession = DEFAULT_MAX_QUEUED_RUNS_PER_SESSION,
        maxQueuedRunsGlobal = DEFAULT_MAX_QUEUED_RUNS_GLOBAL,
    )

enum class SessionLaneOwnerPhase {
    EXECUTING,
    WAITING_CONTROL,
    FINALIZING,
}

enum class SessionRunGrantKind {
    ORDINARY,
    RESUME,
}

enum class SessionRunAdmissionReason {
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

enum class SessionRunTransitionReason {
    NOT_OWNER,
    INVALID_PHASE,
    INVALID_IDENTITY,
    STALE_LEASE,
    CLOSED,
}

data class SessionRunLease(
    val sessionId: String,
    val runId: String,
    val id: Long,
)

data class SessionCapabilityDrainLease(
    val sessionId: String,
    val generation: Long,
)

data class SessionRunOwnerQuery(
    val lease: SessionRunLease,
    val phase: SessionLaneOwnerPhase,
)

sealed interface SessionRunAdmission {
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

sealed interface SessionRunTransition {
    data object Applied : SessionRunTransition
    data class Rejected(val reason: SessionRunTransitionReason) : SessionRunTransition
}

data class SessionRunSchedulerSnapshot(
    val activeExecutionCount: Int,
    val queuedCount: Int,
    val owners: Map<String, String>,
    val readySessionIds: List<String>,
    val waitingOwnerRunIds: List<String>,
    val ownerPhases: Map<String, SessionLaneOwnerPhase>,
    val drainingSessionIds: List<String> = emptyList(),
)

data class SessionRunAttachedJob(
    val lease: SessionRunLease,
    val job: Job,
) {
    val sessionId: String get() = lease.sessionId
    val runId: String get() = lease.runId
}

data class SessionRunQueuedIdentity(
    val sessionId: String,
    val runId: String,
)

data class SessionRunCloseSnapshot(
    val attachedJobs: List<SessionRunAttachedJob>,
    val waitingOwnerLeases: List<SessionRunLease>,
    val queuedIdentities: List<SessionRunQueuedIdentity> = emptyList(),
)

data class SessionRunCompletion(
    val transition: SessionRunTransition,
    val sessionDrained: Boolean,
    val drainLease: SessionCapabilityDrainLease? = null,
)

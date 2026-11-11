@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory

import cc.noharry.agent.appserver.protocol.SessionTurn
import cc.noharry.agent.appserver.protocol.TrajectoryKind
import cc.noharry.agent.appserver.protocol.TrajectoryRecord

/**
 * Run lifecycle taken from canonical EventLog rows, not SessionTurn / RunEvidence.
 * Catalog discovery may still use turns; loaded content must not.
 */
internal data class CanonicalRunLifecycle(
    val startedAtEpochMillis: Long? = null,
    val completedAtEpochMillis: Long? = null,
    val lifecycleStatus: String = TRAJECTORY_STATUS_UNKNOWN,
    val completionOutcome: String? = null,
)

internal fun canonicalRunLifecycle(records: List<TrajectoryRecord>): CanonicalRunLifecycle {
    val started = records.firstOrNull { it.kind == TrajectoryKind.RUN_START }?.occurredAtEpochMillis
        ?: records.mapNotNull { it.occurredAtEpochMillis }.minOrNull()
    val finished = records.lastOrNull { it.kind == TrajectoryKind.RUN_END }
    val lastStatus = records.lastOrNull {
        it.kind == TrajectoryKind.STATUS || it.kind == TrajectoryKind.RUN_END
    }
    val status = lastStatus?.status?.takeIf { it.isNotBlank() }
        ?: lastStatus?.safeMetadata?.get("status")?.takeIf { it.isNotBlank() }
        ?: TRAJECTORY_STATUS_UNKNOWN
    return CanonicalRunLifecycle(
        startedAtEpochMillis = started,
        completedAtEpochMillis = finished?.occurredAtEpochMillis,
        lifecycleStatus = status,
        completionOutcome = finished?.status ?: finished?.safeMetadata?.get("status"),
    )
}

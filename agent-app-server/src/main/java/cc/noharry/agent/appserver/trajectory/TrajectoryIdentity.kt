@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory

import cc.noharry.agent.appserver.protocol.TrajectoryKind
import cc.noharry.agent.appserver.protocol.TrajectoryRecord
import cc.noharry.agent.spi.SessionEvent
import cc.noharry.agent.spi.SessionEventType

/** The UI follows public turns; raw events retain the distinct execution UUID in [SessionEvent.runId]. */
internal val SessionEvent.logicalTurnId: String get() = requestId.ifBlank { runId }

internal const val TRAJECTORY_EXECUTION_ID = "executionId"

/** A diagnostic replay normally returns one attempt; mixed history is projected from its latest start only. */
internal fun latestAttemptEvents(events: List<SessionEvent>): List<SessionEvent> =
    events.groupBy { it.runId }.values.toList().asReversed().maxByOrNull { attempt ->
        attempt.firstOrNull { it.type == SessionEventType.RUN_STARTED }?.recordedAtEpochMillis
            ?: attempt.minOf { it.recordedAtEpochMillis }
    }.orEmpty()

internal data class TrajectoryAttempt(val id: String, val startedAt: Long)

internal fun List<TrajectoryRecord>.attempt(): TrajectoryAttempt? {
    val start = firstOrNull { it.kind == TrajectoryKind.RUN_START } ?: firstOrNull() ?: return null
    return TrajectoryAttempt(start.safeMetadata[TRAJECTORY_EXECUTION_ID] ?: start.runId, start.occurredAtEpochMillis ?: 0L)
}

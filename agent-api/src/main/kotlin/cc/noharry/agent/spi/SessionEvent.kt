@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.spi

/**
 * Immutable envelope returned by [SessionEventLog].
 *
 * [schemaVersion] is 2 for lossless typed payloads. [sequence] is allocated within one
 * `(sessionId, runId)` stream. Causal source sequences use that same stream and always refer
 * to earlier events.
 */
data class SessionEvent(
    val schemaVersion: Int,
    val eventId: String,
    /** Public conversation id ([cc.noharry.agent.AgentThread.id]). */
    val sessionId: String,
    /** Unique execution-attempt id; it can change when a public turn is retried after process loss. */
    val runId: String,
    /** Public turn id ([cc.noharry.agent.TurnResult.turnId]), scoped to [sessionId]. */
    val requestId: String,
    val sequence: Long,
    val recordedAtEpochMillis: Long,
    val type: SessionEventType,
    val payload: SessionEventPayload,
    val sourceEventSequences: List<Long>,
)

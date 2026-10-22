package cc.noharry.agent.kernel.api

/**
 * Candidate immutable envelope returned by [SessionEventLog].
 *
 * [schemaVersion] is 2 for lossless typed payloads. [sequence] is allocated within one
 * `(sessionId, runId)` stream. Causal source sequences use that same stream and always refer
 * to earlier events.
 */
@ExperimentalAgentRuntimeApi
data class SessionEvent(
    val schemaVersion: Int,
    val eventId: String,
    val sessionId: String,
    val runId: String,
    val requestId: String,
    val sequence: Long,
    val recordedAtEpochMillis: Long,
    val type: SessionEventType,
    val payload: SessionEventPayload,
    val sourceEventSequences: List<Long>,
)

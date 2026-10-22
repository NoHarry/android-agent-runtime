package cc.noharry.agent.kernel.api

/**
 * Candidate input for appending one event; identity, sequence, and timestamp are log-owned.
 */
@ExperimentalAgentRuntimeApi
data class SessionEventAppend(
    val sessionId: String,
    val runId: String,
    val requestId: String,
    val type: SessionEventType,
    val payload: SessionEventPayload = SessionEventPayload.Empty,
    val sourceEventSequences: List<Long> = emptyList(),
)

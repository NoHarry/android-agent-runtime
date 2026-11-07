package cc.noharry.agent.spi

/**
 * Input for appending one event; identity, sequence, and timestamp are log-owned.
 */
data class SessionEventAppend(
    val sessionId: String,
    val runId: String,
    val requestId: String,
    val type: SessionEventType,
    val payload: SessionEventPayload = SessionEventPayload.Empty,
    val sourceEventSequences: List<Long> = emptyList(),
)

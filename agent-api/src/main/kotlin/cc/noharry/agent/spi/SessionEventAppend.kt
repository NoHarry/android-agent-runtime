@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.spi

/**
 * Input for appending one event; identity, sequence, and timestamp are log-owned.
 */
data class SessionEventAppend(
    /** Public conversation id ([cc.noharry.agent.AgentThread.id]). */
    val sessionId: String,
    /** Unique execution-attempt id; it can change when a public turn is retried after process loss. */
    val runId: String,
    /** Public turn id ([cc.noharry.agent.TurnResult.turnId]), scoped to [sessionId]. */
    val requestId: String,
    val type: SessionEventType,
    val payload: SessionEventPayload = SessionEventPayload.Empty,
    val sourceEventSequences: List<Long> = emptyList(),
)

package cc.noharry.agent.kernel.api

/**
 * Candidate filter for querying a session event history.
 *
 * [afterSequenceExclusive] is stream-local and therefore requires [runId]. A null [limit]
 * returns the complete matching snapshot.
 */
@ExperimentalAgentRuntimeApi
data class SessionEventQuery(
    val sessionId: String,
    val runId: String? = null,
    val requestId: String? = null,
    val types: Set<SessionEventType> = emptySet(),
    val afterSequenceExclusive: Long? = null,
    val limit: Int? = null,
)

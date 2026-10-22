package cc.noharry.agent.kernel.api

/**
 * Candidate append-only authority for runtime session events.
 *
 * Implementations allocate event identity and stream-local sequence atomically. The contract has
 * no update or delete operation; query and replay return stable snapshots in append order.
 */
@ExperimentalAgentRuntimeApi
interface SessionEventLog {
    fun append(event: SessionEventAppend): SessionEvent

    fun query(query: SessionEventQuery): List<SessionEvent>

    fun replay(sessionId: String, runId: String): List<SessionEvent>

    /** Persistence class. Custom and legacy backends default to [SessionEventLogDurability.UNKNOWN]. */
    fun durability(): SessionEventLogDurability = SessionEventLogDurability.UNKNOWN

    /**
     * Bounded health for one `(sessionId, runId)` stream. Custom and legacy backends that do
     * not report health default to [SessionEventLogHealthStatus.UNKNOWN].
     */
    fun health(sessionId: String, runId: String): SessionEventLogHealth = SessionEventLogHealth.unknown()

    /**
     * Publishes each accepted canonical event after identity is allocated and the append
     * attempt has a stored result. Listeners must not re-enter the same log.
     */
    fun subscribe(listener: (SessionEvent) -> Unit): AutoCloseable = AutoCloseable { }

    /**
     * Candidate: stores attachment bytes once per session, content-addressed by [ref]'s sha256.
     * Returns false when this log keeps no attachment store or the write failed; callers then keep
     * the bytes inline in the event so nothing model-visible is lost.
     */
    fun putAttachment(sessionId: String, ref: SessionAttachmentRef, bytes: ByteArray): Boolean = false

    /** Candidate: attachment bytes verified against [sha256], or null when missing or corrupt. */
    fun readAttachment(sessionId: String, sha256: String): ByteArray? = null
}

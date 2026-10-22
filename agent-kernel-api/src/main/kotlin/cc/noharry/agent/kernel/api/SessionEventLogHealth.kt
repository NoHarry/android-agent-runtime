package cc.noharry.agent.kernel.api

/**
 * Bounded live or sidecar health for one EventLog stream.
 *
 * [COMPLETE] is only valid for a durable backend whose attempted events all appended.
 * Missing, empty, corrupt, or custom evidence must classify as [UNKNOWN], never [COMPLETE].
 */
@ExperimentalAgentRuntimeApi
enum class SessionEventLogHealthStatus {
    COMPLETE,
    DEGRADED,
    UNAVAILABLE,
    VOLATILE,
    UNKNOWN,
}

/** Persistence class of an EventLog backend. Custom or unreported backends are [UNKNOWN]. */
@ExperimentalAgentRuntimeApi
enum class SessionEventLogDurability {
    DURABLE,
    VOLATILE,
    UNKNOWN,
}

/**
 * Bounded EventLog health. Values must stay counts, safe codes, sequence, and durability.
 * Paths, exception text, and credentials are forbidden.
 */
@ExperimentalAgentRuntimeApi
data class SessionEventLogHealth(
    val status: SessionEventLogHealthStatus,
    val attemptedCount: Int = 0,
    val appendedCount: Int = 0,
    val failedCount: Int = 0,
    val firstFailureCode: String? = null,
    val lastAcceptedSequence: Long? = null,
    val lastPersistedSequence: Long? = null,
    val backendDurability: SessionEventLogDurability = SessionEventLogDurability.UNKNOWN,
    val recorderCoverageVersion: Int = RECORDER_COVERAGE_VERSION,
) {
    companion object {
        const val RECORDER_COVERAGE_VERSION: Int = 1

        fun unknown(
            backendDurability: SessionEventLogDurability = SessionEventLogDurability.UNKNOWN,
        ): SessionEventLogHealth = SessionEventLogHealth(
            status = SessionEventLogHealthStatus.UNKNOWN,
            backendDurability = backendDurability,
        )

        /**
         * Offline completeness classifier. Missing stream/sidecar, empty stream, corrupt
         * evidence, or a custom/unknown status token is [UNKNOWN] and cannot be [COMPLETE].
         */
        fun classifyOffline(
            streamExists: Boolean?,
            sidecarExists: Boolean?,
            eventsEmpty: Boolean,
            health: String?,
            streamCorrupt: Boolean = false,
            sidecarCorrupt: Boolean = false,
            identityMatched: Boolean = true,
            lastPersistedMatchesLastSequence: Boolean = true,
            coverageGap: Boolean = false,
            countersConsistent: Boolean = true,
            schemaValid: Boolean = true,
        ): SessionEventLogHealthStatus {
            if (streamExists != true || sidecarExists != true || eventsEmpty) {
                return SessionEventLogHealthStatus.UNKNOWN
            }
            if (!schemaValid || streamCorrupt || sidecarCorrupt || health.isNullOrBlank()) {
                return SessionEventLogHealthStatus.UNKNOWN
            }
            val status = healthStatusOrNull(health) ?: return SessionEventLogHealthStatus.UNKNOWN
            val evidenceIntact = identityMatched &&
                lastPersistedMatchesLastSequence &&
                !coverageGap &&
                countersConsistent
            if (!evidenceIntact) {
                return if (status == SessionEventLogHealthStatus.COMPLETE) {
                    SessionEventLogHealthStatus.DEGRADED
                } else {
                    status
                }
            }
            if (status == SessionEventLogHealthStatus.COMPLETE && eventsEmpty) {
                return SessionEventLogHealthStatus.UNKNOWN
            }
            return status
        }

        fun healthStatusOrNull(token: String): SessionEventLogHealthStatus? =
            SessionEventLogHealthStatus.entries.firstOrNull { it.name == token }
    }
}

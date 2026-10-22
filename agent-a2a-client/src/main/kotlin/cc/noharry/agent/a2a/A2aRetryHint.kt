package cc.noharry.agent.a2a

/** Stable guidance for host-owned retry decisions. */
@ExperimentalA2aClientApi
public enum class A2aRetryHint {
    /** Retrying the same request is not advised. */
    NEVER,
    /** Retry may be appropriate after policy or connectivity evaluation. */
    CONDITIONAL,
    /** Retry only after refreshing authentication. */
    AFTER_AUTH_REFRESH,
}

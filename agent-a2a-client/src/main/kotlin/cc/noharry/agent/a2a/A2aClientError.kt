package cc.noharry.agent.a2a

/** Stable, redacted client error taxonomy. No variant contains raw headers or bodies. */
@ExperimentalA2aClientApi
public sealed interface A2aClientError {
    /** Non-sensitive machine-readable reason. */
    public val reason: String
    /** Host retry guidance. */
    public val retryHint: A2aRetryHint

    /** Invalid configuration, URI, header, limit, or closed-client state. */
    public data class Configuration(override val reason: String) : A2aClientError {
        override val retryHint: A2aRetryHint = A2aRetryHint.NEVER
    }
    /** Agent Card fetch or validation failed. */
    public data class Discovery(override val reason: String) : A2aClientError {
        override val retryHint: A2aRetryHint = A2aRetryHint.CONDITIONAL
    }
    /** Authentication was rejected or could not be supplied. */
    public data class Authentication(override val reason: String) : A2aClientError {
        override val retryHint: A2aRetryHint = A2aRetryHint.AFTER_AUTH_REFRESH
    }
    /** HTTP or network transport failed. */
    public data class Transport(override val reason: String) : A2aClientError {
        override val retryHint: A2aRetryHint = A2aRetryHint.CONDITIONAL
    }
    /** The remote returned a JSON-RPC error. */
    public data class Rpc(public val code: Int, override val reason: String = "rpc_error") : A2aClientError {
        override val retryHint: A2aRetryHint = A2aRetryHint.CONDITIONAL
    }
    /** A response violated the expected A2A/JSON-RPC/SSE shape. */
    public data class Protocol(override val reason: String) : A2aClientError {
        override val retryHint: A2aRetryHint = A2aRetryHint.NEVER
    }
    /** A configured deadline elapsed. */
    public data class Timeout(override val reason: String = "deadline_exceeded") : A2aClientError {
        override val retryHint: A2aRetryHint = A2aRetryHint.CONDITIONAL
    }
    /** The caller cancelled local work. */
    public data class Cancelled(override val reason: String = "caller_cancelled") : A2aClientError {
        override val retryHint: A2aRetryHint = A2aRetryHint.NEVER
    }
    /** A stream ended without a provable protocol terminal state. */
    public data class Incomplete(override val reason: String = "stream_disconnected") : A2aClientError {
        override val retryHint: A2aRetryHint = A2aRetryHint.CONDITIONAL
    }
}

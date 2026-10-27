package cc.noharry.agent.a2a

/** Explicit success/failure result for non-streaming A2A operations. */
@ExperimentalA2aClientApi
public sealed interface A2aResult<out T> {
    /** Successful operation value. */
    public data class Success<T>(public val value: T) : A2aResult<T>
    /** Stable redacted operation failure. */
    public data class Failure(public val error: A2aClientError) : A2aResult<Nothing>
}

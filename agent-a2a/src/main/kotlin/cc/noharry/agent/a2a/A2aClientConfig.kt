package cc.noharry.agent.a2a

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Configures timeouts, resource limits, authentication, and debug URI policy. */
@ExperimentalA2aClientApi
public data class A2aClientConfig(
    public val connectTimeout: Duration = 10.seconds,
    public val requestTimeout: Duration = 60.seconds,
    public val streamReadTimeout: Duration = 60.seconds,
    public val maxDocumentBytes: Long = DEFAULT_MAX_DOCUMENT_BYTES,
    public val maxStreamBufferEvents: Int = DEFAULT_MAX_STREAM_BUFFER_EVENTS,
    public val authProvider: A2aAuthProvider = A2aAuthProvider.None,
    public val allowLoopbackHttpForDebug: Boolean = false,
) {
    /** Validates configuration before a transport is created. */
    public fun validate(): A2aClientError.Configuration? = when {
        connectTimeout <= Duration.ZERO -> A2aClientError.Configuration("invalid_connect_timeout")
        requestTimeout <= Duration.ZERO -> A2aClientError.Configuration("invalid_request_timeout")
        streamReadTimeout <= Duration.ZERO -> A2aClientError.Configuration("invalid_stream_timeout")
        maxDocumentBytes !in 1..HARD_MAX_DOCUMENT_BYTES ->
            A2aClientError.Configuration("invalid_document_limit")
        maxStreamBufferEvents !in 1..HARD_MAX_STREAM_BUFFER_EVENTS ->
            A2aClientError.Configuration("invalid_stream_buffer_limit")
        else -> null
    }

    private companion object {
        const val DEFAULT_MAX_DOCUMENT_BYTES = 1L * 1024L * 1024L
        const val HARD_MAX_DOCUMENT_BYTES = 8L * 1024L * 1024L
        const val DEFAULT_MAX_STREAM_BUFFER_EVENTS = 256
        const val HARD_MAX_STREAM_BUFFER_EVENTS = 1024
    }
}

package cc.noharry.agent.appserver.model

import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelClientException
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRef
import cc.noharry.agent.spi.model.ModelRequest
import java.io.InterruptedIOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * [delegate], whose failed calls carry the stable code of what they were: as the adapter of the service at the
 * profile's base URL reads them ([ProviderAdapter.classify]), else as the wire protocols say ([genericKind]). A failure
 * neither reads keeps the runtime's code. On a host no adapter knows ([knownHost] false), a 429 keeps the runtime's own
 * decision to try again: the wire's codes only name it, as a relay's 429 may be a service's per-minute limit (one
 * service sends `insufficient_quota` for that) as well as an empty balance.
 */
internal class ClassifiedModelClient(
    private val delegate: ModelClient,
    private val knownHost: Boolean = false,
    private val classify: (ModelFailure) -> ModelFailureKind?,
) : ModelClient {
    @OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)
    override val providerConcurrency: ProviderConcurrencyDeclaration
        get() = delegate.providerConcurrency

    override fun supportsImageInput(model: ModelRef?): Boolean = delegate.supportsImageInput(model)

    override fun complete(request: ModelRequest): Flow<ModelEvent> =
        delegate.complete(request).catch { error -> throw if (error is ModelClientException) reclassified(error) else error }

    private fun reclassified(error: ModelClientException): ModelClientException {
        val failure = error.toFailure()
        val read = classify(failure)
        val kind = read ?: failure.genericKind() ?: return error
        val recoverable = if (read == null && !knownHost && failure.status == HTTP_TOO_MANY_REQUESTS) error.recoverable else kind.recoverable
        if (kind.code == error.code && recoverable == error.recoverable) return error
        return ModelClientException(code = kind.code, recoverable = recoverable, message = error.message, cause = error)
    }
}

/** [this] client's failures read by the adapter of the service at [baseUrl], if one knows it, and the wire protocols. */
internal fun ModelClient.classifiedAt(baseUrl: String): ModelClient {
    val known = serviceAt(baseUrl)
    return ClassifiedModelClient(this, knownHost = known != null) { failure -> known?.let { (adapter, service) -> adapter.classify(service, failure) } }
}

/** The failure as the runtime reported it: its message names the HTTP status and the answer's start (keys redacted). */
internal fun ModelClientException.toFailure(): ModelFailure {
    val http = HTTP_FAILURE.find(message)
    return ModelFailure(
        status = http?.groupValues?.get(1)?.toIntOrNull(),
        body = http?.groupValues?.get(2).orEmpty(),
        code = code,
        timedOut = generateSequence(cause) { it.cause }.any { it is InterruptedIOException },
    )
}

/**
 * What the wire protocols themselves say of a failure: the HTTP status (401 and 403 a key refused, 402 nothing left to
 * pay with, 408 and 504 no answer in time, 429 too many requests, 5xx the service's own trouble), a call that timed out,
 * and the error codes of the OpenAI-compatible APIs (`insufficient_quota`, `context_length_exceeded`) and the types of
 * the Messages API (`authentication_error`, `rate_limit_error`, `overloaded_error`). Null when none of them applies.
 */
internal fun ModelFailure.genericKind(): ModelFailureKind? = when {
    timedOut || status == HTTP_REQUEST_TIMEOUT || status == HTTP_GATEWAY_TIMEOUT -> ModelFailureKind.TIMEOUT
    body.contains(QUOTA_CODE, ignoreCase = true) || status == HTTP_PAYMENT_REQUIRED -> ModelFailureKind.QUOTA
    body.contains(CONTEXT_CODE, ignoreCase = true) -> ModelFailureKind.CONTEXT_WINDOW
    status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN || body.contains(AUTH_TYPE) -> ModelFailureKind.AUTH
    status == HTTP_TOO_MANY_REQUESTS || body.contains(RATE_LIMIT_TYPE) -> ModelFailureKind.RATE_LIMIT
    status != null && status in HTTP_SERVER_ERRORS || body.contains(OVERLOADED_TYPE) -> ModelFailureKind.SERVER
    else -> null
}

/** The kind [rules] give [this] failure: the first that matches; null when none does. */
@cc.noharry.agent.appserver.AppServerPluginApi
fun ModelFailure.kindBy(rules: List<ErrorRule>): ModelFailureKind? = rules.firstOrNull { it.matches(this) }?.kind

/** The runtime's message for an answered call: `<wire> provider HTTP <status>.` and, when it said something, ` Response: <start>`. */
private val HTTP_FAILURE = Regex("""provider HTTP (\d{3})\.(?: Response: (.*))?""", RegexOption.DOT_MATCHES_ALL)
private const val QUOTA_CODE = "insufficient_quota"
private const val CONTEXT_CODE = "context_length_exceeded"
private const val AUTH_TYPE = "authentication_error"
private const val RATE_LIMIT_TYPE = "rate_limit_error"
private const val OVERLOADED_TYPE = "overloaded_error"
private const val HTTP_PAYMENT_REQUIRED = 402
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_GATEWAY_TIMEOUT = 504
private val HTTP_SERVER_ERRORS = 500..599

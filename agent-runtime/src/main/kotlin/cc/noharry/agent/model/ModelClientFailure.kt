package cc.noharry.agent.model

import cc.noharry.agent.kernel.api.model.ModelClientException
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.core.loop.failed
import kotlinx.coroutines.CancellationException

internal fun providerHttpFailure(
    provider: String,
    statusCode: Int,
    body: ByteArray = ByteArray(0),
): ModelClientException {
    val preview = body.providerErrorPreview()
    val detail = preview.takeIf { it.isNotBlank() }?.let { " Response: $it" }.orEmpty()
    return ModelClientException(
        code = providerHttpErrorCode(statusCode),
        recoverable = providerHttpRecoverable(statusCode),
        message = "$provider provider HTTP $statusCode.$detail",
    )
}

internal fun providerTransportFailure(provider: String, cause: Throwable): ModelClientException =
    ModelClientException(
        code = RuntimeErrorCode.PROVIDER_TRANSPORT_FAILED,
        recoverable = true,
        message = "$provider provider transport failed.",
        cause = cause,
    )

internal fun providerMalformedResponse(provider: String, cause: RuntimeException): ModelClientException =
    ModelClientException(
        code = RuntimeErrorCode.PROVIDER_RESPONSE_MALFORMED,
        recoverable = true,
        message = "$provider provider response was malformed.",
        cause = cause,
    )

internal fun requireProviderApiKey(provider: String, apiKeyProvider: () -> String): String =
    try {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) throw providerCredentialFailure(provider)
        apiKey
    } catch (error: CancellationException) {
        throw error
    } catch (error: ModelClientException) {
        throw error
    } catch (error: RuntimeException) {
        throw providerCredentialFailure(provider, error)
    }

private fun providerCredentialFailure(
    provider: String,
    cause: RuntimeException? = null,
): ModelClientException =
    ModelClientException(
        code = RuntimeErrorCode.PROVIDER_AUTH_FAILED,
        recoverable = false,
        message = "$provider provider credential is missing or blank.",
        cause = cause,
    )

private fun providerHttpErrorCode(statusCode: Int): RuntimeErrorCode =
    when (statusCode) {
        401, 403 -> RuntimeErrorCode.PROVIDER_AUTH_FAILED
        429 -> RuntimeErrorCode.PROVIDER_RATE_LIMITED
        in 500..599 -> RuntimeErrorCode.PROVIDER_SERVER_ERROR
        else -> RuntimeErrorCode.PROVIDER_HTTP_ERROR
    }

private fun providerHttpRecoverable(statusCode: Int): Boolean =
    when (statusCode) {
        408, 409, 425, 429 -> true
        in 500..599 -> true
        else -> false
    }

private fun ByteArray.providerErrorPreview(): String {
    if (isEmpty()) return ""
    val normalized = toString(Charsets.UTF_8)
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .redactProviderSecrets()
    return if (normalized.length <= PROVIDER_ERROR_PREVIEW_CHARS) {
        normalized
    } else {
        normalized.take(PROVIDER_ERROR_PREVIEW_CHARS - PROVIDER_ERROR_TRUNCATION.length) +
            PROVIDER_ERROR_TRUNCATION
    }
}

private fun String.redactProviderSecrets(): String {
    var output = replace(SECRET_TOKEN, "sk-REDACTED")
    output = output.replace(BEARER_TOKEN) { match -> match.groupValues[1] + "<redacted>" }
    output = output.replace(API_KEY_FIELD) { match ->
        match.groupValues[1] + "<redacted>" + match.groupValues[3]
    }
    return output
}

private const val PROVIDER_ERROR_PREVIEW_CHARS = 512
private const val PROVIDER_ERROR_TRUNCATION = "..."
private val SECRET_TOKEN = Regex("""sk-[A-Za-z0-9_-]{8,}""")
private val BEARER_TOKEN = Regex("""(?i)(bearer\s+)[A-Za-z0-9._~+/=-]+""")
private val API_KEY_FIELD = Regex("""(?i)("(?:api[_-]?key|token|secret)"\s*:\s*")([^"]+)(")""")

package cc.noharry.agent.kernel.api.model

import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode

/**
 * Recoverable or terminal model-provider failure observed by the Kernel loop.
 *
 * Implementations throw this type from [ModelClient.complete]; the loop maps
 * [recoverable] into retry/fallback without importing provider packages.
 */
class ModelClientException(
    val code: RuntimeErrorCode,
    val recoverable: Boolean,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

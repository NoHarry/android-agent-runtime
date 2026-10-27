package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.kernel.api.model.ModelRef

data class ModelRetryConfig(
    val maxAttempts: Int = DEFAULT_MODEL_RETRY_MAX_ATTEMPTS,
    val initialBackoffMillis: Long = DEFAULT_MODEL_RETRY_INITIAL_BACKOFF_MILLIS,
    val maxBackoffMillis: Long = DEFAULT_MODEL_RETRY_MAX_BACKOFF_MILLIS,
    val fallbackChain: List<ModelRef> = emptyList(),
) {
    init {
        require(maxAttempts > 0) { "model retry maxAttempts must be positive." }
        require(initialBackoffMillis >= 0) { "model retry initialBackoffMillis must be non-negative." }
        require(maxBackoffMillis >= 0) { "model retry maxBackoffMillis must be non-negative." }
        require(maxBackoffMillis == 0L || maxBackoffMillis >= initialBackoffMillis) {
            "model retry maxBackoffMillis must be zero or at least initialBackoffMillis."
        }
    }

    fun candidates(primary: ModelRef?): List<ModelRef?> {
        val result = mutableListOf<ModelRef?>()
        val seen = linkedSetOf<String>()
        val normalizedPrimary = primary?.normalized()
        result += normalizedPrimary
        normalizedPrimary?.wireValue()?.takeIf { it.isNotBlank() }?.let(seen::add)
        for (fallback in fallbackChain) {
            val normalized = fallback.normalized()
            require(normalized.providerId.isNotBlank()) { "fallback providerId must not be blank." }
            require(normalized.modelId.isNotBlank()) { "fallback modelId must not be blank." }
            if (seen.add(normalized.wireValue())) result += normalized
        }
        return result
    }

    fun delayMillis(failureCount: Int): Long {
        if (initialBackoffMillis == 0L || maxBackoffMillis == 0L) return 0L
        val multiplier = 1L shl (failureCount - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        return (initialBackoffMillis * multiplier).coerceAtMost(maxBackoffMillis)
    }
}

/** Three retries after the first attempt (0.5 s, 1 s, 2 s apart); dsh retries five times, a device's network drops briefly. */
const val DEFAULT_MODEL_RETRY_MAX_ATTEMPTS = 4
private const val DEFAULT_MODEL_RETRY_INITIAL_BACKOFF_MILLIS = 500L
private const val DEFAULT_MODEL_RETRY_MAX_BACKOFF_MILLIS = 4_000L
private const val MAX_BACKOFF_SHIFT = 20

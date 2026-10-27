package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.core.state.ProtocolRequestTemplate

internal data class ConfirmationTemplate(
    val expiresInMillis: Long,
)

internal const val DEFAULT_CONFIRMATION_TIMEOUT_MILLIS = 60_000L

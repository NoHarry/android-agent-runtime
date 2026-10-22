package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode

/** Core-owned network-tool seam. Transport stays in runtime. */
interface NetworkToolPort {
    fun authorize(runId: String, argumentsJson: String): NetworkToolPolicyDecision

    /** [authorize] for one tool: a port with more than web_fetch (web_search) decides per tool. */
    fun authorize(runId: String, toolName: String, argumentsJson: String): NetworkToolPolicyDecision =
        authorize(runId, argumentsJson)

    fun rememberConfirmed(runId: String, argumentsJson: String)

    fun activityDomain(toolName: String, argumentsJson: String): String

    fun activityResult(resultJson: String): NetworkToolActivityResult

    suspend fun execute(toolName: String, argumentsJson: String): String

    companion object {
        fun disabled(): NetworkToolPort = DisabledNetworkToolPort
    }
}

sealed interface NetworkToolPolicyDecision {
    data object Allowed : NetworkToolPolicyDecision

    data class Denied(val resultJson: String) : NetworkToolPolicyDecision

    data class RequiresConfirmation(val prompt: String) : NetworkToolPolicyDecision
}

data class NetworkToolActivityResult(
    val statusCode: Int? = null,
    val truncated: Boolean = false,
)

private object DisabledNetworkToolPort : NetworkToolPort {
    override fun authorize(runId: String, argumentsJson: String): NetworkToolPolicyDecision =
        NetworkToolPolicyDecision.Denied(notConfigured())

    override fun rememberConfirmed(runId: String, argumentsJson: String) = Unit

    override fun activityDomain(toolName: String, argumentsJson: String): String = ""

    override fun activityResult(resultJson: String): NetworkToolActivityResult = NetworkToolActivityResult()

    override suspend fun execute(toolName: String, argumentsJson: String): String = notConfigured()

    private fun notConfigured(): String =
        """{"ok":false,"code":"${RuntimeErrorCode.CAPABILITY_BACKEND_NOT_CONFIGURED.wireCode}"}"""
}

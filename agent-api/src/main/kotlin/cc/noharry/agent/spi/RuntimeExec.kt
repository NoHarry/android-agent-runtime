package cc.noharry.agent.spi

/**
 * Host-provided JavaScript exec engine boundary.
 *
 * [providerConcurrency] is metadata only. Implementations must declare
 * threadSafe, serialized, or sessionFactory explicitly. Execution limiting is
 * attached by the runtime limiter around [run].
 */
interface ExecEngine {
    val providerConcurrency: ProviderConcurrencyDeclaration

    suspend fun run(request: ExecRequest, bridge: ExecToolBridge): ExecResult
}

/** Exec request passed to optional engine modules. */
data class ExecRequest(
    val runId: String,
    val code: String,
    val timeoutMillis: Long,
    val outputTokenBudget: Int,
)

/** Bridge exposed to exec engines for nested runtime tool calls. */
interface ExecToolBridge {
    suspend fun callTool(name: String, argumentsJson: String): String

    suspend fun emitOutput(text: String)
}

/** Exec result returned by optional engine modules. */
data class ExecResult(
    val ok: Boolean,
    val output: String = "",
    val errorCode: String? = null,
    val message: String = "",
)

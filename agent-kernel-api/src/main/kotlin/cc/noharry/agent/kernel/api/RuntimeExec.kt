package cc.noharry.agent.kernel.api

/**
 * Candidate host-provided JavaScript exec engine boundary. Not frozen until API review closes.
 *
 * [providerConcurrency] is metadata only. Implementations must declare
 * threadSafe, serialized, or sessionFactory explicitly. Execution limiting is
 * attached by the runtime limiter around [run].
 */
@ExperimentalAgentRuntimeApi
interface ExecEngine {
    val providerConcurrency: ProviderConcurrencyDeclaration

    suspend fun run(request: ExecRequest, bridge: ExecToolBridge): ExecResult
}

/** Candidate exec request passed to optional engine modules. Not frozen until API review closes. */
@ExperimentalAgentRuntimeApi
data class ExecRequest(
    val runId: String,
    val code: String,
    val timeoutMillis: Long,
    val outputTokenBudget: Int,
)

/** Candidate bridge exposed to exec engines for nested runtime tool calls. */
@ExperimentalAgentRuntimeApi
interface ExecToolBridge {
    suspend fun callTool(name: String, argumentsJson: String): String

    suspend fun emitOutput(text: String)
}

/** Candidate exec result returned by optional engine modules. Not frozen until API review closes. */
@ExperimentalAgentRuntimeApi
data class ExecResult(
    val ok: Boolean,
    val output: String = "",
    val errorCode: String? = null,
    val message: String = "",
)

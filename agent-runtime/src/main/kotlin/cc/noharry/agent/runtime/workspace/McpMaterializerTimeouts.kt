package cc.noharry.agent.runtime.workspace
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.kernel.core.tools.MaterializerListResult
import cc.noharry.agent.kernel.core.tools.MaterializerReadResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal suspend fun mcpCatalogRead(
    context: MaterializerContext,
    block: suspend () -> MaterializerReadResult,
): MaterializerReadResult = try {
    withTimeout(context.timeoutMillis) { block() }
} catch (_: TimeoutCancellationException) {
    MaterializerReadResult.Error(
        code = RuntimeErrorCode.CAPABILITY_CATALOG_TIMEOUT.wireCode,
        message = "Timed out while materializing MCP catalog.",
        retryable = true,
    )
}

internal suspend fun mcpCatalogList(
    context: MaterializerContext,
    block: suspend () -> MaterializerListResult,
): MaterializerListResult = try {
    withTimeout(context.timeoutMillis) { block() }
} catch (_: TimeoutCancellationException) {
    MaterializerListResult.Error(
        code = RuntimeErrorCode.CAPABILITY_CATALOG_TIMEOUT.wireCode,
        message = "Timed out while listing MCP catalog.",
        retryable = true,
    )
}

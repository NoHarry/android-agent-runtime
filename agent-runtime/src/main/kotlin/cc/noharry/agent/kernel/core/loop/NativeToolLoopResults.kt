package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.spi.reliability.RuntimeErrorCode
import cc.noharry.agent.spi.reliability.RuntimeResult
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import cc.noharry.agent.spi.ToolUseResult

internal fun modelClientNotConfiguredResult(runId: String): RuntimeResult = RuntimeResult(
    runId = runId,
    status = RuntimeRunStatus.FAILED,
    response = MODEL_CLIENT_NOT_CONFIGURED_MESSAGE,
    errorCode = RuntimeErrorCode.MODEL_LOOP_FAILED.wireCode,
    recoverable = false,
    completion = cc.noharry.agent.spi.reliability.RuntimeCompletionSummary(
        outcome = cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome.UNKNOWN,
    ),
)

internal const val MODEL_CLIENT_NOT_CONFIGURED_MESSAGE = "Model client is not configured."

internal fun completed(
    runId: String,
    response: String,
    completion: cc.noharry.agent.spi.reliability.RuntimeCompletionSummary =
        cc.noharry.agent.spi.reliability.RuntimeCompletionSummary(
            outcome = cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome.SUCCEEDED,
        ),
): RuntimeResult = RuntimeResult(
    runId = runId,
    status = RuntimeRunStatus.COMPLETED,
    response = response,
    completion = completion,
)

internal fun failed(runId: String, failure: ToolLoopFailure?): RuntimeResult = RuntimeResult(
    runId = runId,
    status = RuntimeRunStatus.FAILED,
    response = failure?.message ?: "Model loop failed.",
    errorCode = failure?.code,
    recoverable = failure?.recoverable ?: true,
    completion = cc.noharry.agent.spi.reliability.RuntimeCompletionSummary(
        outcome = cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome.FAILED,
    ),
)

internal fun failed(runId: String, result: ToolUseResult): RuntimeResult = RuntimeResult(
    runId = runId,
    status = RuntimeRunStatus.FAILED,
    response = result.resultJson,
    errorCode = result.code,
    recoverable = result.retryable,
)

internal fun userInputHistoryMissingFailure(): ToolLoopFailure =
    ToolLoopFailure(
        RuntimeErrorCode.MODEL_LOOP_FAILED.wireCode,
        "Loop history is missing for user input resume.",
    )

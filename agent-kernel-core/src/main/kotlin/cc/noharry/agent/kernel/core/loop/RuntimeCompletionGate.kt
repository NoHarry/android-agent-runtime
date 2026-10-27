@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class FinishDecision(
    val accept: Boolean,
    val outcome: RuntimeCompletionOutcome,
    val retryable: Boolean,
    val resultJson: String?,
    val safeCode: String? = null,
)

fun decideFinish(
    argumentsJson: String,
    ledger: RuntimeObligationLedger,
    correctionAlreadyUsed: Boolean,
): FinishDecision {
    if (invalidFinishArguments(argumentsJson)) return FinishDecision(
        accept = false,
        outcome = RuntimeCompletionOutcome.FAILED,
        retryable = !correctionAlreadyUsed,
        resultJson = invalidFinishResponseResult(),
        safeCode = INVALID_FINISH_RESPONSE_CODE,
    )
    val requestedToken = extractFinishOutcomeToken(argumentsJson)
    val response = extractFinishResponse(argumentsJson).trim()
    val requested = when (requestedToken) {
        FINISH_OUTCOME_PARTIAL -> RuntimeCompletionOutcome.PARTIAL
        FINISH_OUTCOME_BLOCKED -> RuntimeCompletionOutcome.BLOCKED
        else -> RuntimeCompletionOutcome.SUCCEEDED
    }
    val summary = ledger.summarize(requested)
    if (requested == RuntimeCompletionOutcome.PARTIAL || requested == RuntimeCompletionOutcome.BLOCKED) {
        if (response.isBlank()) {
            return FinishDecision(
                accept = false,
                outcome = RuntimeCompletionOutcome.FAILED,
                retryable = !correctionAlreadyUsed,
                resultJson = invalidFinishProjectionResult(),
                safeCode = COMPLETION_PROJECTION_INVALID,
            )
        }
        return FinishDecision(accept = true, outcome = requested, retryable = false, resultJson = null)
    }
    if (summary.requiredUnresolved == 0) {
        return FinishDecision(accept = true, outcome = RuntimeCompletionOutcome.SUCCEEDED, retryable = false, resultJson = null)
    }
    if (!correctionAlreadyUsed) {
        return FinishDecision(
            accept = false,
            outcome = RuntimeCompletionOutcome.FAILED,
            retryable = true,
            resultJson = unresolvedRequiredActionResult(summary.unresolvedCodes),
            safeCode = REQUIRED_ACTION_UNRESOLVED,
        )
    }
    return FinishDecision(
        accept = false,
        outcome = RuntimeCompletionOutcome.FAILED,
        retryable = false,
        resultJson = unresolvedRequiredActionResult(summary.unresolvedCodes),
        safeCode = REQUIRED_ACTION_UNRESOLVED,
    )
}

fun extractFinishResponse(argumentsJson: String): String {
    val root = runCatching { Json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull()
    val response = root?.get("response") as? JsonPrimitive
    return response?.takeIf { it.isString }?.content.orEmpty()
}

fun extractFinishOutcomeToken(argumentsJson: String): String? {
    val root = runCatching { Json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull()
    return (root?.get("outcome") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}

fun unresolvedRequiredActionResult(codes: List<String>): String {
    val codeList = codes.joinToString(",") { "\"${it.replace("\"", "'")}\"" }
    return """{"ok":false,"code":"$REQUIRED_ACTION_UNRESOLVED","unresolvedCodes":[$codeList],"message":"Required mutating actions remain unresolved."}"""
}

fun invalidFinishProjectionResult(): String =
    """{"ok":false,"code":"$COMPLETION_PROJECTION_INVALID","message":"partial or blocked finish requires a nonblank incompleteness response."}"""

@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.state
import cc.noharry.agent.kernel.api.RuntimeActionOutcome
import cc.noharry.agent.kernel.api.RuntimeActionStatus
import cc.noharry.agent.kernel.api.RuntimeCommitStatus
import cc.noharry.agent.kernel.api.RuntimeEffectKind
import cc.noharry.agent.kernel.api.TurnInputObservation
import cc.noharry.agent.kernel.core.tools.backendToolCallResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

fun normalizeTransportResult(
    obligationId: String,
    actionName: String,
    success: Boolean,
    resultJson: String,
    retryable: Boolean = false,
): RuntimeActionOutcome {
    val root = resultJson.asJsonObject()
    val backend = backendToolCallResult(resultJson)
    val innerOk = root.boolField("ok")
    val committed = root.boolField("committed") ?: root.boolField("writeCommitted")
    val noop = root.boolField("noop")
    val isError = root.boolField("isError")
    val code = backend.code ?: root.stringField("code")
    val commitToken = root.stringField("commitStatus") ?: root.stringField("commit")
    val resolvedOk =
        success &&
            backend.ok &&
            innerOk != false &&
            isError != true &&
            !isUnsatisfyingTransportCode(code)
    val resolvedCommit = when {
        !resolvedOk ->
            if (commitToken == RuntimeCommitStatus.UNKNOWN.name) {
                RuntimeCommitStatus.UNKNOWN
            } else {
                RuntimeCommitStatus.NOT_COMMITTED
            }
        commitToken == RuntimeCommitStatus.NO_CHANGE_REQUIRED.name ||
            (noop == true && committed == true) -> RuntimeCommitStatus.NO_CHANGE_REQUIRED
        committed == false || commitToken == RuntimeCommitStatus.NOT_COMMITTED.name ->
            RuntimeCommitStatus.NOT_COMMITTED
        committed == true || commitToken == RuntimeCommitStatus.COMMITTED.name ->
            RuntimeCommitStatus.COMMITTED
        else -> RuntimeCommitStatus.NOT_COMMITTED
    }
    val status = when {
        !resolvedOk -> RuntimeActionStatus.FAILED
        resolvedCommit == RuntimeCommitStatus.COMMITTED ||
            resolvedCommit == RuntimeCommitStatus.NO_CHANGE_REQUIRED -> RuntimeActionStatus.SUCCEEDED
        else -> RuntimeActionStatus.FAILED
    }
    return RuntimeActionOutcome(
        obligationId = obligationId,
        actionName = actionName,
        status = status,
        commitStatus = resolvedCommit,
        safeCode = code,
        retryable = retryable || root.boolField("retryable") == true,
    )
}

fun TurnInputObservation.toRequiredOutcome(
    obligationId: String,
    actionName: String,
): RuntimeActionOutcome? {
    if (effectKind != RuntimeEffectKind.MUTATING || completionImpact != cc.noharry.agent.kernel.api.CompletionImpact.REQUIRED_ON_CALL) {
        return null
    }
    if (!handled) return null
    val commit = commitStatus
        ?: if (writeCommitted) RuntimeCommitStatus.COMMITTED else RuntimeCommitStatus.NOT_COMMITTED
    val status = if (commit == RuntimeCommitStatus.COMMITTED || commit == RuntimeCommitStatus.NO_CHANGE_REQUIRED) {
        RuntimeActionStatus.SUCCEEDED
    } else {
        RuntimeActionStatus.FAILED
    }
    return RuntimeActionOutcome(
        obligationId = obligationId,
        actionName = actionName,
        status = status,
        commitStatus = commit,
        safeCode = safeCode,
        retryable = false,
    )
}

fun canonicalActionArguments(argumentsJson: String): String {
    val element = runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull()
    return if (element is JsonObject) {
        element.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"$key\":$value"
        }
    } else {
        argumentsJson.trim()
    }
}

private fun String.asJsonObject(): JsonObject? =
    runCatching { Json.parseToJsonElement(this) as? JsonObject }.getOrNull()

private fun isUnsatisfyingTransportCode(code: String?): Boolean {
    val token = code?.uppercase() ?: return false
    return token in UNSATISFYING_TRANSPORT_CODES
}

private val UNSATISFYING_TRANSPORT_CODES = setOf(
    "TIMEOUT",
    "TIMED_OUT",
    "CANCEL",
    "CANCELLED",
    "CANCELED",
    "DENIED",
    "PERMISSION_DENIED",
    "FAILURE",
    "FAILED",
    "NOT_COMMITTED",
    "WRITE_FAILED",
    "ENGINE_CLOSED",
)

private fun JsonObject?.boolField(name: String): Boolean? {
    val primitive = this?.get(name) as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.booleanOrNull
}

private fun JsonObject?.stringField(name: String): String? {
    val primitive = this?.get(name) as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content.takeIf { it.isNotBlank() }
}

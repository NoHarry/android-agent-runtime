package cc.noharry.agent.kernel.core.loop

import cc.noharry.agent.kernel.api.ServiceAnswerCheck
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.core.tools.RuntimeServiceAnswerCheck
import cc.noharry.agent.kernel.core.tools.answers
import cc.noharry.agent.kernel.core.tools.backendToolCallResult
import cc.noharry.agent.kernel.core.tools.structuredFailure

/** A failure the provider already explains (`{"ok":false,"code":…}`) reaches the model as it is, not as an escaped string. */
internal fun ToolCallResult.toBackendToolResult(serviceAnswer: ServiceAnswerCheck = RuntimeServiceAnswerCheck): String =
    backendOutcome(serviceAnswer).json

/** What reaches the model ([json]), and whether it is a result the service gave as its answer to a problem. */
internal data class BackendOutcome(val json: String, val serviceAnswered: Boolean = false)

/**
 * A service's answer to a problem ([ServiceAnswerCheck]) reaches the model as written; any other failure as the runtime's
 * report. Only a result that itself reports the problem (`"ok":false`, MCP `isError`) can be one, so what is passed on is
 * still read as a failure afterwards (the run's summary, hooks).
 */
internal fun ToolCallResult.backendOutcome(serviceAnswer: ServiceAnswerCheck): BackendOutcome {
    val answered = !backendToolCallResult(resultJson).ok && serviceAnswer.answers(resultJson)
    // A call that went through can still report a problem in its result: the service's answer too, if the check says so.
    if (success || answered) return BackendOutcome(resultJson, serviceAnswered = answered)
    return BackendOutcome(structuredFailure(resultJson, retryable) ?: backendFailedResult(resultJson, retryable))
}

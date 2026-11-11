@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)
package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.*
import cc.noharry.agent.appserver.AppServerPluginApi
import cc.noharry.agent.appserver.protocol.view.displayChoiceReply
import cc.noharry.agent.spi.reliability.*

/**
 * The code a turn's result carries ([TurnResult.error], its message the reply) when the model answered that part of what
 * was asked was not done (`finish` with outcome `partial`): the turn is COMPLETED, or WAITING_FOR_ANSWER when the reply
 * asks the person something.
 */
@AppServerPluginApi
const val PARTIAL_ANSWER_CODE = "HOST_PARTIAL_OUTCOME"

/**
 * The code a turn's result carries when the model answered that what was asked could not be done, something standing in
 * the way (`finish` with outcome `blocked`): COMPLETED, or WAITING_FOR_ANSWER when the reply asks the person something.
 */
@AppServerPluginApi
const val BLOCKED_ANSWER_CODE = "HOST_BLOCKED_OUTCOME"

/**
 * A turn as the app reports it: the runtime's status says the turn's lifecycle ended, this says whether it answered. A
 * verified success stays COMPLETED. A reply the model gave as `partial` or `blocked` is an answer, not a failed run:
 * COMPLETED with [PARTIAL_ANSWER_CODE] or [BLOCKED_ANSWER_CODE], WAITING_FOR_ANSWER when it asks the person. Anything
 * else that completed (no reply, no outcome the runtime verified) is FAILED; a run the runtime failed (a tool loop
 * that broke, a required action left undone under a claimed success) never reaches here as completed.
 */
internal fun hostTurnOutcome(event: ThreadEvent.TurnCompleted, diagnostic: RuntimeResult?): ThreadEvent.TurnCompleted {
    if (event.result.status != TurnStatus.COMPLETED) return event
    if (diagnostic != null && RuntimeCompletionSummary.firstPartyPass(diagnostic.status, diagnostic.completion)) return event
    val reply = event.result.finalResponse
    val answered = when (diagnostic?.completion?.outcome) {
        RuntimeCompletionOutcome.PARTIAL -> PARTIAL_ANSWER_CODE
        RuntimeCompletionOutcome.BLOCKED -> BLOCKED_ANSWER_CODE
        else -> null
    }?.takeIf { reply.isNotBlank() }
    val status = when {
        answered == null -> TurnStatus.FAILED
        reply.asksPerson() -> TurnStatus.WAITING_FOR_ANSWER
        else -> TurnStatus.COMPLETED
    }
    val code = answered ?: diagnostic?.errorCode ?: "HOST_OUTCOME_UNVERIFIED"
    return event.copy(result = event.result.copy(status = status, error = TurnError(code, reply)))
}

/**
 * How a turn [hostTurnOutcome] reported ended, as the run's records keep it: PARTIAL or BLOCKED for an answer that did
 * not do everything, SUCCEEDED for any other completed turn, UNKNOWN for the rest (the runtime's own result, read once
 * the run ends, says more).
 */
internal fun TurnResult.hostCompletion(): RuntimeCompletionOutcome = when {
    error?.code == PARTIAL_ANSWER_CODE -> RuntimeCompletionOutcome.PARTIAL
    error?.code == BLOCKED_ANSWER_CODE -> RuntimeCompletionOutcome.BLOCKED
    status == TurnStatus.COMPLETED -> RuntimeCompletionOutcome.SUCCEEDED
    else -> RuntimeCompletionOutcome.UNKNOWN
}

internal suspend fun AgentRuntime.hostEvent(event: ThreadEvent, sessionId: String): ThreadEvent {
    if (event !is ThreadEvent.TurnCompleted) return event
    val result = runCatching { diagnostics.getRuntimeResult(sessionId, event.result.turnId) }.getOrNull()
    return hostTurnOutcome(event, result)
}

/** Whether a reply asks the person something: a question in what is spoken, or choices to pick from. */
internal fun String.asksPerson(): Boolean {
    val shown = trim()
    val spoken = shown.substringBefore("\n\n")
    return spoken.contains('？') || spoken.contains('?') || shown.displayChoiceReply().choices.isNotEmpty()
}

@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)
package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.reliability.*
import org.junit.Assert.*
import org.junit.Test

class HostTurnOutcomeTest {
    private val completed = ThreadEvent.TurnCompleted(TurnResult("turn", TurnStatus.COMPLETED, "done", emptyList()))
    @Test fun aCompletedLifecycleNeedsVerifiedBusinessSuccess() {
        assertEquals(TurnStatus.FAILED, hostTurnOutcome(completed, null).result.status)
        for (outcome in listOf(RuntimeCompletionOutcome.UNKNOWN, RuntimeCompletionOutcome.FAILED)) {
            assertEquals(TurnStatus.FAILED, hostTurnOutcome(completed, result(outcome)).result.status)
        }
        assertEquals(TurnStatus.COMPLETED, hostTurnOutcome(completed, result(RuntimeCompletionOutcome.SUCCEEDED)).result.status)
        assertNull(hostTurnOutcome(completed, result(RuntimeCompletionOutcome.SUCCEEDED)).result.error)
    }

    @Test fun aPartialOrBlockedReplyIsAnAnswerMarkedAsSuchAndWaitsWhenItAsks() {
        val partial = hostTurnOutcome(reply("Route set; the stop at the bakery could not be added."), result(RuntimeCompletionOutcome.PARTIAL)).result
        assertEquals(TurnStatus.COMPLETED, partial.status)
        assertEquals(PARTIAL_ANSWER_CODE, partial.error?.code)
        assertEquals(RuntimeCompletionOutcome.PARTIAL, partial.hostCompletion())
        val blocked = hostTurnOutcome(reply("The habit list is turned off, so I cannot list your habits."), result(RuntimeCompletionOutcome.BLOCKED)).result
        assertEquals(TurnStatus.COMPLETED, blocked.status)
        assertEquals(BLOCKED_ANSWER_CODE, blocked.error?.code)
        assertEquals(RuntimeCompletionOutcome.BLOCKED, blocked.hostCompletion())
        for (outcome in listOf(RuntimeCompletionOutcome.PARTIAL, RuntimeCompletionOutcome.BLOCKED)) {
            val asking = hostTurnOutcome(reply("Which destination?"), result(outcome)).result
            assertEquals(outcome.name, TurnStatus.WAITING_FOR_ANSWER, asking.status)
            assertEquals(outcome.name, outcome, asking.hostCompletion())
        }
    }

    @Test fun aPartialOrBlockedEndWithoutAReplyStillFails() {
        for (outcome in listOf(RuntimeCompletionOutcome.PARTIAL, RuntimeCompletionOutcome.BLOCKED)) {
            val silent = hostTurnOutcome(reply("  "), result(outcome)).result
            assertEquals(outcome.name, TurnStatus.FAILED, silent.status)
            assertEquals(outcome.name, "HOST_OUTCOME_UNVERIFIED", silent.error?.code)
        }
    }

    private fun reply(text: String) = completed.copy(result = completed.result.copy(finalResponse = text))

    private fun result(outcome: RuntimeCompletionOutcome) = RuntimeResult("turn", RuntimeRunStatus.COMPLETED, "done", completion = RuntimeCompletionSummary(outcome))
}

@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** A host that changes a thread's approval policy while a turn waits decides the waiting approval by the new policy. */
class ThreadLivePolicyTest {
    @Test fun approveAllApprovesTheWaitingApprovalAutomatically() = runBlocking {
        val events = waitThenPatch(ApprovalPolicy.APPROVE_ALL)
        assertEquals(ApprovalState.APPROVED, completed<Approval>(events).single().state)
        assertEquals(ApprovalSource.AUTOMATIC, setTemperature(events).approvedBy)
        assertTrue(events.last() is ThreadEvent.TurnCompleted)
        assertTurnEvents(events)
    }

    @Test fun denyAllDeniesTheWaitingApprovalAndTheTurnGoesOn() = runBlocking {
        val events = waitThenPatch(ApprovalPolicy.DENY_ALL)
        assertEquals(ApprovalState.DENIED, completed<Approval>(events).single().state)
        assertEquals(ItemStatus.DECLINED, setTemperature(events).status)
        assertTrue(events.last() is ThreadEvent.TurnCompleted)
        assertTurnEvents(events)
    }

    @Test fun rememberWithoutAnApprovedAreaKeepsWaitingForThePerson() = runBlocking {
        val events = waitThenPatch(ApprovalPolicy.REMEMBER) { approval -> approval.approve() }
        assertEquals(ApprovalState.APPROVED, completed<Approval>(events).single().state)
        assertEquals(ApprovalSource.USER, setTemperature(events).approvedBy)
        assertTurnEvents(events)
    }

    private suspend fun waitThenPatch(
        policy: ApprovalPolicy,
        thenRespond: suspend (Approval) -> Unit = {},
    ): List<ThreadEvent> = kotlinx.coroutines.coroutineScope {
        approvalBuilder().build().use { runtime ->
            val thread = runtime.startThread(ThreadOptions(threadId = "live"))
            val waiting = CompletableDeferred<Approval>()
            val events = mutableListOf<ThreadEvent>()
            val turn = async {
                thread.runStreamed("set temperature").collect { event ->
                    events += event
                    if (event is ThreadEvent.ItemStarted && event.item is Approval) waiting.complete(event.item as Approval)
                }
            }
            val approval = withTimeout(10_000) { waiting.await() }
            runtime.resumeThread("live", ThreadOptions(approvalPolicy = policy))
            thenRespond(approval)
            withTimeout(10_000) { turn.await() }
            events
        }
    }

    private fun setTemperature(events: List<ThreadEvent>): ThreadItem.ToolCall =
        completed<ThreadItem.ToolCall>(events).single { it.tool == "mcp__default__set_temperature" }

    private inline fun <reified T : ThreadItem> completed(events: List<ThreadEvent>): List<T> =
        events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }.filterIsInstance<T>()
}

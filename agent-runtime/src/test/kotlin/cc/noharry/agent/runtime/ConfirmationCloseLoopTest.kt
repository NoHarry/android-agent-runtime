@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.model.ModelEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConfirmationCloseLoopTest {
    @Test fun pendingApprovalBelongsToTheActiveTurnAndIsVisibleFromAnotherHandle() = runBlocking {
        approvalBuilder().build().use { runtime ->
            val thread = runtime.startThread(ThreadOptions(threadId = "session-1"))
            thread.runStreamed("set temperature", TurnOptions(turnId = "request-1")).collect { event ->
                if (event is ThreadEvent.ItemStarted && event.item is Approval) {
                    val pending = event.item as Approval
                    assertEquals("request-1", pending.turnId)
                    assertEquals(ThreadStatus.WAITING_FOR_APPROVAL, thread.status())
                    assertEquals(pending, runtime.resumeThread(thread.id).pendingRequests().single())
                    pending.approve()
                }
            }
            assertTrue(thread.pendingRequests().isEmpty())
        }
    }

    @Test fun successiveApprovalIdsAreUniqueAndTheCurrentRequestCannotConsumeThePreviousOne() = runBlocking {
        val model = ThreadScriptModel(listOf(
            listOf(schemaRead("set_temperature"), ModelEvent.Completed),
            listOf(ModelEvent.ToolCall("first", qualifiedMcpTool("set_temperature"), "{}"), ModelEvent.Completed),
            listOf(ModelEvent.ToolCall("second", qualifiedMcpTool("set_temperature"), "{}"), ModelEvent.Completed),
            finishEvents("done"),
        ))
        threadBuilder(model).tools(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .hook(confirmUntilApproved()).build().use { runtime ->
                val thread = runtime.startThread()
                val approvals = mutableListOf<Approval>()
                thread.runStreamed("set it twice").collect { event ->
                    if (event is ThreadEvent.ItemStarted && event.item is Approval) {
                        val pending = event.item as Approval
                        approvals.firstOrNull()?.let { assertEquals(RespondResult.ALREADY_ANSWERED, it.approve()) }
                        approvals += pending
                        assertEquals(pending, thread.pendingRequests().single())
                        assertEquals(RespondResult.ACCEPTED, pending.approve())
                    }
                }
                assertEquals(2, approvals.size)
                assertEquals(2, approvals.map { it.id }.toSet().size)
            }
    }

    @Test fun approvingCompletesTheOriginalTurnAndOnlyTheFirstResponseWins() = runBlocking {
        approvalBuilder().build().use { runtime ->
            val thread = runtime.startThread()
            val events = mutableListOf<ThreadEvent>()
            thread.runStreamed("set temperature").collect { event ->
                events += event
                if (event is ThreadEvent.ItemStarted && event.item is Approval) {
                    val pending = event.item as Approval
                    assertEquals(RespondResult.ACCEPTED, pending.approve())
                    assertEquals(RespondResult.ALREADY_ANSWERED, pending.approve())
                }
            }
            assertTurnEvents(events)
            assertEquals(TurnStatus.COMPLETED, (events.last() as ThreadEvent.TurnCompleted).result.status)
            assertEquals(ThreadStatus.IDLE, thread.status())
            assertTrue(thread.pendingRequests().isEmpty())
        }
    }

    @Test fun denialDoesNotExecuteTheToolAndLetsTheModelExplain() = runBlocking {
        val provider = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        threadBuilder(backendThenFinishModel(mutableListOf())).tools(provider).hook(confirmUntilApproved()).build().use { runtime ->
            val thread = runtime.startThread()
            val events = mutableListOf<ThreadEvent>()
            thread.runStreamed("set temperature").collect { event ->
                events += event
                if (event is ThreadEvent.ItemStarted && event.item is Approval) {
                    assertEquals(RespondResult.ACCEPTED, (event.item as Approval).deny())
                }
            }
            assertTurnEvents(events)
            assertEquals("recovered", (events.last() as ThreadEvent.TurnCompleted).result.finalResponse)
            assertTrue(provider.calledTools.isEmpty())
            assertTrue(thread.pendingRequests().isEmpty())
        }
    }

    @Test fun interruptWithdrawsApprovalAndAStaleHandleCannotExecuteTheTool() = runBlocking {
        approvalBuilder().build().use { runtime ->
            val thread = runtime.startThread()
            var stale: Approval? = null
            val events = mutableListOf<ThreadEvent>()
            thread.runStreamed("set temperature").collect { event ->
                events += event
                if (event is ThreadEvent.ItemStarted && event.item is Approval) {
                    stale = event.item as Approval
                    thread.interrupt()
                }
            }
            assertTurnEvents(events)
            assertEquals(TurnStatus.INTERRUPTED, (events.last() as ThreadEvent.TurnCompleted).result.status)
            assertEquals(RespondResult.NOT_FOUND, checkNotNull(stale).approve())
            assertTrue(thread.pendingRequests().isEmpty())
        }
    }

    @Test fun anExpiredApprovalRefusesLateResponsesAndTheTurnGoesOn() = runBlocking {
        var now = 1_000L
        approvalBuilder().nowEpochMillis { now }.build().use { runtime ->
            val thread = runtime.startThread()
            val events = mutableListOf<ThreadEvent>()
            thread.runStreamed("set temperature").collect { event ->
                events += event
                if (event is ThreadEvent.ItemStarted && event.item is Approval) {
                    val pending = event.item as Approval
                    now = checkNotNull(pending.expiresAt) + 1
                    assertEquals(RespondResult.EXPIRED, pending.approve())
                }
            }
            assertTurnEvents(events)
            assertTrue(events.last() is ThreadEvent.TurnCompleted)
            assertEquals(ItemStatus.DECLINED, events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }
                .filterIsInstance<ThreadItem.ToolCall>().single { it.tool == "mcp__default__set_temperature" }.status)
            assertTrue(thread.pendingRequests().isEmpty())
        }
    }
}

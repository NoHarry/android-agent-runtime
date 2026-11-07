@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.SessionRunConflictPolicy
import cc.noharry.agent.spi.model.ModelEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class ThreadReviewRegressionTest {
    @Test fun replayWhileBusyPreservesOriginalResultAndDoesNotCancelTheActiveTurn() = runBlocking {
        val root = threadRoot()
        threadBuilder(ThreadScriptModel(listOf(finishEvents("saved"))), root).build().use { first ->
            first.startThread(ThreadOptions(threadId = "shared")).run("one", TurnOptions(turnId = "saved-turn"))
        }
        approvalBuilder(root).build().use { runtime ->
            val thread = runtime.resumeThread("shared")
            val active = async { thread.runStreamed("set temperature", TurnOptions(turnId = "active")).toList() }
            withTimeout(5000) { while (thread.pendingRequests().isEmpty()) yield() }
            for (policy in listOf(SessionRunConflictPolicy.RESUME_CURRENT, SessionRunConflictPolicy.CANCEL_AND_REPLACE)) {
                val replay = withTimeout(1000) { thread.run("retry", TurnOptions(turnId = "saved-turn", onConflict = policy)) }
                assertEquals("saved", replay.finalResponse)
                assertEquals(TurnStatus.COMPLETED, replay.status)
                assertEquals(ThreadStatus.WAITING_FOR_APPROVAL, thread.status())
            }
            (thread.pendingRequests().single() as Approval).approve()
            active.await()
            assertEquals("saved", thread.run("retry", TurnOptions(turnId = "saved-turn")).finalResponse)
        }
    }

    @Test fun rejectedBusyTurnCanRetryWithTheSameId() = runBlocking {
        val model = ThreadScriptModel(listOf(
            listOf(ModelEvent.ToolCall("search", "search_tools", """{"query":"set_temperature"}"""), ModelEvent.Completed),
            listOf(ModelEvent.ToolCall("set", qualifiedMcpTool("set_temperature"), "{}"), ModelEvent.Completed),
            finishEvents("first"), finishEvents("second"),
        ))
        threadBuilder(model).tools(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .hook(confirmUntilApproved()).build().use { runtime ->
            val thread = runtime.startThread()
            val active = async { thread.runStreamed("set temperature", TurnOptions(turnId = "active")).toList() }
            withTimeout(5000) { while (thread.pendingRequests().isEmpty()) yield() }
            val options = TurnOptions(turnId = "retry", onConflict = SessionRunConflictPolicy.RESUME_CURRENT)
            assertEquals("THREAD_BUSY", thread.run("next", options).error?.code)
            (thread.pendingRequests().single() as Approval).approve()
            active.await()
            assertEquals(TurnStatus.COMPLETED, thread.run("next", options).status)
        }
    }

    @Test fun approveOnceDoesNotApproveAnotherCallInTheSameTurn() = runBlocking {
        val model = ThreadScriptModel(listOf(
            listOf(ModelEvent.ToolCall("search", "search_tools", """{"query":"set_temperature"}"""), ModelEvent.Completed),
            listOf(ModelEvent.ToolCall("first", qualifiedMcpTool("set_temperature"), "{}"), ModelEvent.Completed),
            listOf(ModelEvent.ToolCall("second", qualifiedMcpTool("set_temperature"), "{}"), ModelEvent.Completed),
            finishEvents("done"),
        ))
        val provider = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        threadBuilder(model).tools(provider).hook(confirmUntilApproved()).build().use { runtime ->
            var approvals = 0
            val events = mutableListOf<ThreadEvent>()
            runtime.startThread().runStreamed("change twice").collect { event ->
                events += event
                if (event is ThreadEvent.ItemStarted && event.item is Approval) {
                    approvals++
                    val approval = event.item as Approval
                    if (approvals == 1) approval.approve(ApprovalScope.ONCE) else approval.deny()
                }
            }
            assertEquals(2, approvals)
            assertEquals("CONFIRMATION_DENIED", (events.last() as ThreadEvent.TurnFailed).error.code)
            assertTurnEvents(events)
        }
    }
}

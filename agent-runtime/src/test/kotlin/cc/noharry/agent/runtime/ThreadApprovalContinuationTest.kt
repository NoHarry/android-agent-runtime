@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.model.ModelRequest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ThreadApprovalContinuationTest {
    @Test fun denialBecomesAToolResultAndTheModelCanAnswerWithoutExecuting() = runBlocking {
        deniedTurn(ApprovalPolicy.ASK)
    }

    @Test fun denyAllStillLetsTheModelAnswerWithoutExecuting() = runBlocking {
        deniedTurn(ApprovalPolicy.DENY_ALL)
    }

    @Test fun interruptedApprovalIsCancelledInTheNextModelRequestAfterRestart() = runBlocking {
        cancelledApproval(collectorCancelled = false)
    }

    @Test fun cancelledCollectorDoesNotLeaveAnAwaitingConfirmationPlaceholder() = runBlocking {
        cancelledApproval(collectorCancelled = true)
    }

    private suspend fun deniedTurn(policy: ApprovalPolicy) {
        val requests = mutableListOf<ModelRequest>()
        val provider = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        threadBuilder(backendThenFinishModel(requests)).tools(provider).hook(confirmUntilApproved()).build().use { runtime ->
            val events = mutableListOf<ThreadEvent>()
            runtime.startThread(ThreadOptions(approvalPolicy = policy)).runStreamed("set temperature").collect { event ->
                events += event
                if (event is ThreadEvent.ItemStarted && event.item is Approval) (event.item as Approval).deny()
            }
            assertTurnEvents(events)
            val result = (events.last() as ThreadEvent.TurnCompleted).result
            assertEquals("recovered", result.finalResponse)
            assertTrue(provider.calledTools.isEmpty())
            assertTrue(requests.last().messages.any { it.role == "tool" && "CONFIRMATION_DENIED" in it.content })
            assertFalse(requests.last().messages.any { "awaiting_confirmation" in it.content })
            val denied = result.items.filterIsInstance<ThreadItem.ToolCall>().single { it.tool == qualifiedMcpTool("set_temperature") }
            assertEquals(ItemStatus.DECLINED, denied.status)
        }
    }

    private suspend fun cancelledApproval(collectorCancelled: Boolean) = coroutineScope {
        val root = threadRoot()
        approvalBuilder(root).build().use { runtime ->
            val thread = runtime.startThread(ThreadOptions(threadId = "cancelled"))
            val pending = CompletableDeferred<Unit>()
            val running = launch { thread.runStreamed("set temperature").collect { event ->
                if (event is ThreadEvent.ItemStarted && event.item is Approval) pending.complete(Unit)
            } }
            withTimeout(5000) { pending.await() }
            if (collectorCancelled) running.cancelAndJoin() else { thread.interrupt(); running.join() }
        }
        val model = ThreadScriptModel(listOf(finishEvents("next")))
        threadBuilder(model, root).build().use { runtime ->
            runtime.resumeThread("cancelled").run("what happened?")
            val history = model.requests.single().messages
            assertTrue(history.any { it.role == "tool" && "CONFIRMATION_CANCELLED" in it.content })
            assertFalse(history.any { "awaiting_confirmation" in it.content })
        }
    }
}

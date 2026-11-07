@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.runtime.thread.DefaultThreadRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class ThreadApprovalFlowTest {
    @Test fun inputQueuedDuringApprovalSurvivesDenial() = runBlocking { acceptedInputSurvivesApprovalEnd("deny") }

    @Test fun inputQueuedDuringApprovalSurvivesInterruption() = runBlocking { acceptedInputSurvivesApprovalEnd("interrupt") }

    @Test fun inputQueuedDuringApprovalSurvivesExpiry() = runBlocking { acceptedInputSurvivesApprovalEnd("expire") }

    @Test fun inputWhileWaitingForApprovalQueuesUntilExecutionContinues() = runBlocking {
        val requests = mutableListOf<cc.noharry.agent.spi.model.ModelRequest>()
        approvalBuilder(requests = requests).build().use { runtime ->
            val thread = runtime.startThread()
            val events = mutableListOf<ThreadEvent>()
            thread.runStreamed("set temperature").collect { event ->
                events += event
                if (event is ThreadEvent.ItemStarted && event.item is Approval) {
                    assertEquals(ThreadStatus.WAITING_FOR_APPROVAL, thread.status())
                    val before = requests.size
                    assertEquals(SteerResult.ACCEPTED, thread.steer("Then explain the setting", SteerOptions("during-approval")))
                    assertEquals(before, requests.size)
                    (event.item as Approval).approve()
                }
            }
            assertTurnEvents(events)
            assertTrue(requests.last().messages.any { it.role == "user" && it.content.contains("Then explain the setting") })
            val input = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }
                .filterIsInstance<ThreadItem.UserMessage>().single { it.steer }
            assertEquals("during-approval", input.requestId)
        }
    }

    @Test fun approvingContinuesTheOriginalFlowAndOnlyOneResponderWins() = runBlocking {
        approvalBuilder().build().use { runtime ->
            val thread = runtime.startThread()
            val events = mutableListOf<ThreadEvent>()
            thread.runStreamed("set temperature").collect { event ->
                events += event
                if (event is ThreadEvent.ItemStarted && event.item is Approval) {
                    assertEquals(ThreadStatus.WAITING_FOR_APPROVAL, thread.status())
                    val otherScreen = runtime.resumeThread(thread.id).pendingRequests().single() as Approval
                    assertEquals(RespondResult.ACCEPTED, otherScreen.approve())
                    assertEquals(RespondResult.ALREADY_ANSWERED, (event.item as Approval).deny())
                }
            }
            assertTurnEvents(events)
            val approval = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }.filterIsInstance<Approval>().single()
            assertEquals(ApprovalState.APPROVED, approval.state)
            assertEquals("recovered", (events.last() as ThreadEvent.TurnCompleted).result.finalResponse)
            assertTrue(events.indexOfFirst { it is ThreadEvent.ItemCompleted && it.item is Approval } < events.lastIndex)
            assertTrue(thread.pendingRequests().isEmpty())
        }
    }

    @Test fun denialClosesApprovalAndToolWithoutExecutingIt() = runBlocking {
        approvalBuilder().build().use { runtime ->
            val events = mutableListOf<ThreadEvent>()
            runtime.startThread().runStreamed("set temperature").collect { event ->
                events += event
                if (event is ThreadEvent.ItemStarted && event.item is Approval) (event.item as Approval).deny("No")
            }
            assertTurnEvents(events)
            assertEquals(TurnStatus.COMPLETED, (events.last() as ThreadEvent.TurnCompleted).result.status)
            val completed = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }
            assertEquals(ApprovalState.DENIED, completed.filterIsInstance<Approval>().single().state)
            assertEquals(ItemStatus.DECLINED, completed.filterIsInstance<ThreadItem.ToolCall>().single { it.tool == qualifiedMcpTool("set_temperature") }.status)
        }
    }

    @Test fun expiryRejectsLateResponsesAndTheTurnGoesOnWithoutTheCall() = runBlocking {
        val built = approvalBuilder().build() as DefaultThreadRuntime
        DefaultThreadRuntime(built.engine, built.store, confirmationTimeoutMillis = 20).use { runtime ->
            val events = runtime.startThread().runStreamed("set temperature").toList()
            assertTurnEvents(events)
            val pending = events.filterIsInstance<ThreadEvent.ItemStarted>().map { it.item }.filterIsInstance<Approval>().single()
            assertEquals(RespondResult.EXPIRED, pending.approve())
            assertTrue(events.last() is ThreadEvent.TurnCompleted)
            val call = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }
                .filterIsInstance<ThreadItem.ToolCall>().single { it.tool == "mcp__default__set_temperature" }
            assertEquals(ItemStatus.DECLINED, call.status)
            assertTrue(call.output.orEmpty().contains("CONFIRMATION_EXPIRED"))
        }
    }

    @Test fun cancellingCollectorClosesPendingApprovalAndPersistsInterruptedItems() = runBlocking {
        approvalBuilder().build().use { runtime ->
            val thread = runtime.startThread()
            val pending = CompletableDeferred<Approval>()
            val job = launch { thread.runStreamed("set temperature").collect {
                if (it is ThreadEvent.ItemStarted && it.item is Approval) pending.complete(it.item as Approval)
            } }
            val approval = withTimeout(5000) { pending.await() }
            job.cancelAndJoin()
            assertEquals(RespondResult.NOT_FOUND, approval.approve())
            assertEquals(ThreadStatus.IDLE, thread.status())
            assertTrue(thread.pendingRequests().isEmpty())
            assertTrue(thread.items().none { it.status == ItemStatus.IN_PROGRESS })
        }
    }

    @Test fun interruptCompletesTheActiveFlowAndThreadCanRunAgain() = runBlocking {
        approvalBuilder().build().use { runtime ->
            val thread = runtime.startThread()
            val result = async { thread.runStreamed("set temperature").toList() }
            withTimeout(5000) { while (thread.pendingRequests().isEmpty()) yield() }
            thread.interrupt()
            val events = result.await()
            assertTurnEvents(events)
            assertEquals(TurnStatus.INTERRUPTED, (events.last() as ThreadEvent.TurnCompleted).result.status)
            assertEquals(ThreadStatus.IDLE, thread.status())
        }
    }

    private suspend fun acceptedInputSurvivesApprovalEnd(action: String) {
        val requests = mutableListOf<cc.noharry.agent.spi.model.ModelRequest>()
        val built = approvalBuilder(requests = requests).build() as DefaultThreadRuntime
        DefaultThreadRuntime(built.engine, built.store, confirmationTimeoutMillis = 100).use { runtime ->
            val thread = runtime.startThread()
            val events = mutableListOf<ThreadEvent>()
            thread.runStreamed("set temperature").collect { event ->
                events += event
                if (event is ThreadEvent.ItemStarted && event.item is Approval) {
                    assertEquals(SteerResult.ACCEPTED, thread.steer("Remember this input", SteerOptions("approval-end")))
                    when (action) {
                        "deny" -> (event.item as Approval).deny()
                        "interrupt" -> thread.interrupt()
                    }
                }
            }
            assertTurnEvents(events)
            val inputs = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }
                .filterIsInstance<ThreadItem.UserMessage>().filter { it.steer }
            assertEquals("approval-end", inputs.single().requestId)
            assertEquals("Remember this input", (inputs.single().input.single() as InputPart.Text).text)
            assertEquals(inputs, thread.items().filterIsInstance<ThreadItem.UserMessage>().filter { it.steer })
            if (action != "interrupt") {
                assertTrue("the model reads what was said while the approval waited",
                    requests.last().messages.any { it.role == "user" && it.content.contains("Remember this input") })
            }
        }
    }
}

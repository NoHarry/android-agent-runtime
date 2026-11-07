@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.*
import cc.noharry.agent.spi.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class ThreadConcurrencyTest {
    @Test fun sameStoredThreadSerializesAcrossRuntimeInstancesAndRestoresLatestHistory() = runBlocking {
        val root = threadRoot()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val firstModel = object : ModelClient {
            override val providerConcurrency = ProviderConcurrencyDeclaration.threadSafe()
            override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
                entered.complete(Unit)
                release.await()
                finishEvents("first answer").forEach { emit(it) }
            }
        }
        val secondModel = ThreadScriptModel(listOf(finishEvents("second answer")))
        threadBuilder(firstModel, root).build().use { first ->
            threadBuilder(secondModel, root).build().use { second ->
                val a = first.startThread(ThreadOptions(threadId = "shared"))
                val b = second.resumeThread("shared")
                val one = async { a.run("first question") }
                entered.await()
                val two = async { b.run("second question") }
                yield()
                assertTrue(secondModel.requests.isEmpty())
                release.complete(Unit)
                one.await(); two.await()
                assertTrue(secondModel.requests.single().messages.any { it.content == "first question" })
                assertEquals(2, b.items().filterIsInstance<ThreadItem.UserMessage>().size)
            }
        }
    }

    @Test fun closingRuntimeCancelsPendingApprovalAndClosesAllItems() = runBlocking {
        val runtime = approvalBuilder().build()
        val thread = runtime.startThread()
        val collection = async { thread.runStreamed("set temperature").toList() }
        withTimeout(5000) { while (thread.pendingRequests().isEmpty()) yield() }
        val approval = thread.pendingRequests().single() as Approval
        runtime.close()
        assertEquals(RespondResult.NOT_FOUND, approval.approve())
        collection.await()
        assertTrue(thread.items().none { it.status == ItemStatus.IN_PROGRESS })
    }

    @Test fun automaticPoliciesDoNotEmitApprovalCards() = runBlocking {
        for (policy in listOf(ApprovalPolicy.APPROVE_ALL, ApprovalPolicy.DENY_ALL)) {
            approvalBuilder().build().use { runtime ->
                val events = runtime.startThread(ThreadOptions(approvalPolicy = policy)).runStreamed("set temperature").toList()
                assertTurnEvents(events)
                assertFalse(events.any { it is ThreadEvent.ItemStarted && it.item is Approval })
                val tools = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }.filterIsInstance<ThreadItem.ToolCall>()
                if (policy == ApprovalPolicy.APPROVE_ALL) assertTrue(tools.any { it.approvedBy == ApprovalSource.AUTOMATIC })
                else assertTrue(tools.all { it.approvedBy == null })
                assertTrue(events.last() is ThreadEvent.TurnCompleted)
            }
        }
    }
}

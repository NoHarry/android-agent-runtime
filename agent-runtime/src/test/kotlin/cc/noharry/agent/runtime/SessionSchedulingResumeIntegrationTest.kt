@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.runtime.thread.DefaultThreadRuntime
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.SessionRunConflictPolicy
import cc.noharry.agent.spi.model.*
import cc.noharry.agent.spi.reliability.RuntimeRunPhase
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class SessionSchedulingResumeIntegrationTest {
    @Test fun approvalRetainsTheSameOwnerAndQueuesTheNextTurnOfThatThread() = runSchedulingTest {
        threadApprovalRuntime().use { runtime ->
            val a = runtime.startThread(ThreadOptions(threadId = SESSION_A))
            val b = runtime.startThread(ThreadOptions(threadId = SESSION_B))
            val a1 = async { a.run("first", TurnOptions(turnId = RUN_A1)) }
            val pa = a.awaitApproval(RUN_A1)
            val b1 = async { b.run("other", TurnOptions(turnId = RUN_B1)) }
            b.awaitApproval(RUN_B1)
            val original = runtime.engine.sessionSchedulerSnapshotForTest().owners
            assertEquals(2, runtime.engine.sessionSchedulerSnapshotForTest().activeExecutionCount)
            val a2 = async { a.run("second", TurnOptions(turnId = RUN_A2)) }
            runtime.awaitQueued(1)
            assertEquals(original, runtime.engine.sessionSchedulerSnapshotForTest().owners)
            pa.approve()
            assertEquals(TurnStatus.COMPLETED, a1.await().status)
            a.awaitApproval(RUN_A2)
            val advanced = runtime.engine.sessionSchedulerSnapshotForTest().owners
            assertNotEquals(original[SESSION_A], advanced[SESSION_A])
            assertEquals(original[SESSION_B], advanced[SESSION_B])
            a.interrupt(); b.interrupt()
            a2.await(); b1.await()
            assertIdle(runtime.engine)
        }
    }

    @Test fun approvalUsesTheExistingExecutionAndRunningStateBeforeToolWork() = runSchedulingTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val provider = GatedMcpProvider(gate, entered)
        threadApprovalRuntime(provider).use { runtime ->
            val thread = runtime.startThread()
            val running = async { thread.run("action", TurnOptions(turnId = RUN_A1)) }
            val pending = thread.awaitApproval(RUN_A1)
            val execution = checkNotNull(runtime.diagnostics.getRunState(thread.id, RUN_A1)).runId
            assertEquals(RespondResult.ACCEPTED, pending.approve())
            entered.await()
            assertEquals(RuntimeRunStatus.RUNNING, runtime.engine.getRunState(execution)?.status)
            assertEquals(RuntimeRunPhase.MODEL_RUNNING, runtime.engine.getRunEnvelope(execution)?.phase)
            assertEquals(execution, runtime.engine.sessionSchedulerSnapshotForTest().owners[thread.id])
            assertEquals(1, runtime.engine.sessionSchedulerSnapshotForTest().activeExecutionCount)
            assertEquals(RespondResult.ALREADY_ANSWERED, pending.approve())
            assertEquals(1, provider.callCount)
            gate.complete(Unit)
            assertEquals(TurnStatus.COMPLETED, running.await().status)
            assertIdle(runtime.engine)
        }
    }

    @Test fun concurrentApprovalResponsesCannotStartASecondExecution() = runSchedulingTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val provider = GatedMcpProvider(gate, entered)
        threadApprovalRuntime(provider).use { runtime ->
            val thread = runtime.startThread()
            val running = async { thread.run("action") }
            val pending = thread.awaitApproval()
            val replies = listOf(async(Dispatchers.Default) { pending.approve() }, async(Dispatchers.Default) { pending.approve() }).awaitAll()
            assertEquals(setOf(RespondResult.ACCEPTED, RespondResult.ALREADY_ANSWERED), replies.toSet())
            entered.await()
            assertEquals(1, provider.callCount)
            gate.complete(Unit)
            assertEquals(TurnStatus.COMPLETED, running.await().status)
            assertIdle(runtime.engine)
        }
    }

    @Test fun interruptAfterApprovalBeforeExecutionCannotReviveTheTurn() = runSchedulingTest {
        val provider = GatedMcpProvider(CompletableDeferred(Unit), CompletableDeferred())
        threadApprovalRuntime(provider).use { runtime ->
            val thread = runtime.startThread()
            val events = mutableListOf<ThreadEvent>()
            thread.runStreamed("action", TurnOptions(turnId = RUN_A1)).collect { event ->
                events += event
                if (event is ThreadEvent.ItemStarted && event.item is Approval) (event.item as Approval).approve()
                if (event is ThreadEvent.ItemCompleted && event.item is Approval) thread.interrupt()
            }
            assertTurnEvents(events)
            assertEquals(TurnStatus.INTERRUPTED, (events.last() as ThreadEvent.TurnCompleted).result.status)
            assertEquals(0, provider.callCount)
            assertEquals(RuntimeRunStatus.CANCELLED, runtime.diagnostics.getRunState(thread.id, RUN_A1)?.status)
            assertIdle(runtime.engine)
        }
    }

    @Test fun cancelAndReplaceInterruptsTheApprovedExecutionBeforeStartingItsReplacement() = runSchedulingTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val provider = GatedMcpProvider(gate, entered)
        threadApprovalRuntime(provider).use { runtime ->
            val thread = runtime.startThread()
            val original = async { thread.run("original") }
            thread.awaitApproval().approve()
            entered.await()
            val replacement = thread.collectApproving("replacement",
                TurnOptions(onConflict = SessionRunConflictPolicy.CANCEL_AND_REPLACE)) { gate.complete(Unit) }
            assertEquals(TurnStatus.INTERRUPTED, original.await().status)
            assertEquals(TurnStatus.COMPLETED, (replacement.last() as ThreadEvent.TurnCompleted).result.status)
            assertEquals(2, provider.callCount)
            assertIdle(runtime.engine)
        }
    }

    @Test fun aBlockingQuestionReleasesExecutionBeforeItsAnswerCanReachApproval() = runSchedulingTest {
        val model = ThreadScriptModel(listOf(
            listOf(ModelEvent.ToolCall("ask", "ask", """{"question":"Which temperature?"}"""), ModelEvent.Completed),
            listOf(schemaRead("set_temperature"), ModelEvent.Completed),
            listOf(setTemperatureBackendCall(), ModelEvent.Completed), finishEvents("done"),
        ))
        threadBuilder(model).tools(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .hook(confirmUntilApproved()).sessionConcurrency(maxActiveSessions = 1).build().use { runtime ->
                val thread = runtime.startThread()
                val first = thread.run("set temperature")
                assertEquals(TurnStatus.WAITING_FOR_ANSWER, first.status)
                assertIdle((runtime as DefaultThreadRuntime).engine)
                val events = mutableListOf<ThreadEvent>()
                thread.runStreamed(TurnInput(listOf(InputPart.Text("18")), answering = first.pending!!.id)).collect { event ->
                    events += event
                    if (event is ThreadEvent.ItemStarted && event.item is Approval) (event.item as Approval).approve()
                }
                assertTurnEvents(events)
                val result = (events.last() as ThreadEvent.TurnCompleted).result
                assertNotEquals(first.turnId, result.turnId)
                assertEquals(TurnStatus.COMPLETED, result.status)
                assertEquals(1, events.filterIsInstance<ThreadEvent.ItemStarted>().count { it.item is Approval })
                assertIdle(runtime.engine)
            }
    }
}

internal suspend fun AgentThread.awaitApproval(turnId: String? = null): Approval = withTimeout(5000) {
    while (true) {
        pendingRequests().filterIsInstance<Approval>().firstOrNull { turnId == null || it.turnId == turnId }?.let { return@withTimeout it }
        yield()
    }
    error("Unreachable")
}

internal fun threadApprovalRuntime(
    provider: cc.noharry.agent.spi.ToolProvider = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))),
): DefaultThreadRuntime = threadBuilder(ApprovalPerTurnModel()).tools(provider).hook(confirmUntilApproved())
    .sessionConcurrency(maxActiveSessions = 2).build() as DefaultThreadRuntime

private class ApprovalPerTurnModel : ModelClient {
    override val providerConcurrency = ProviderConcurrencyDeclaration.threadSafe()
    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        val current = request.messages.drop(request.messages.indexOfLast { it.role == "user" } + 1)
        val event = when {
            current.none { it.role == "tool" && it.toolName == "read_file" } -> schemaRead("set_temperature")
            current.none { it.role == "tool" && it.toolName == qualifiedMcpTool("set_temperature") } -> setTemperatureBackendCall()
            else -> ModelEvent.ToolCall("finish", "finish", """{"response":"done"}""")
        }
        emit(event); emit(ModelEvent.Completed)
    }
}

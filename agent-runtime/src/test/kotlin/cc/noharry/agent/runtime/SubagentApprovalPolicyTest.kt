@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.model.ModelEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test

class SubagentApprovalPolicyTest {
    @Test fun actualParentTurnPolicyReachesChildrenAndUnavailableApprovalNeverWaits() = runBlocking {
        for (policy in ApprovalPolicy.entries) childApproval(policy, shared = true)
    }

    @Test fun customLauncherApprovalGatesAreAnsweredWithoutAUserChannel() = runBlocking {
        for (policy in ApprovalPolicy.entries) childApproval(policy, shared = false)
    }

    private suspend fun childApproval(policy: ApprovalPolicy, shared: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val captured = CompletableDeferred<SubagentSpec>()
        val finished = CompletableDeferred<List<ThreadEvent>>()
        val provider = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val model = ThreadScriptModel(childRounds(shared))
        lateinit var subagents: Subagents
        subagents = Subagents(launcher = { spec ->
            captured.complete(spec)
            runChild(spec, if (shared) subagents else null, provider, model, finished)
        }, scope = scope)
        try {
            runParent(subagents, policy)
            val events = withTimeout(5000) { finished.await() }
            assertEquals(policy, captured.await().approvalPolicy)
            assertTurnEvents(events)
            assertEquals(TurnStatus.COMPLETED, (events.last() as ThreadEvent.TurnCompleted).result.status)
            val approvals = events.filterIsInstance<ThreadEvent.ItemStarted>().count { it.item is Approval }
            assertEquals(if (shared) 0 else 1, approvals)
            assertEquals(if (policy == ApprovalPolicy.APPROVE_ALL) listOf("set_temperature") else emptyList<String>(), provider.calledTools)
            if (shared) {
                assertTrue(events.none { it is ThreadEvent.ItemStarted && it.item is Question })
                val unavailable = model.requests.last().messages.count { it.role == "tool" && "INTERACTION_UNAVAILABLE" in it.content }
                assertEquals(2, unavailable)
            }
        } finally { subagents.cancelSession(); scope.cancel() }
    }

    private suspend fun runParent(subagents: Subagents, policy: ApprovalPolicy) {
        val model = ThreadScriptModel(listOf(
            listOf(ModelEvent.ToolCall("spawn", "spawn_agent", """{"task":"set temperature"}"""), ModelEvent.Completed),
            finishEvents("delegated"),
        ))
        threadBuilder(model).subagents(subagents).build().use { runtime ->
            runtime.startThread(ThreadOptions(approvalPolicy = ApprovalPolicy.DENY_ALL))
                .run("delegate", TurnOptions(approvalPolicy = policy))
        }
    }

    private fun runChild(
        spec: SubagentSpec,
        shared: Subagents?,
        provider: LoopMcpProvider,
        model: ThreadScriptModel,
        finished: CompletableDeferred<List<ThreadEvent>>,
    ): Flow<ThreadEvent> = flow {
        try {
            threadBuilder(model).tools(provider).hook(confirmUntilApproved())
                .apply { shared?.let(::subagents) }.build().use { runtime ->
                    val events = mutableListOf<ThreadEvent>()
                    // The shared registry supplies defaults; a custom launcher intentionally exercises the fallback.
                    runtime.resumeThread(spec.sessionId).runStreamed(spec.task).collect { event ->
                        events += event
                        emit(event)
                    }
                    finished.complete(events)
                }
        } catch (error: Throwable) { finished.completeExceptionally(error); throw error }
    }

    private fun childRounds(questions: Boolean): List<List<ModelEvent>> = buildList {
        add(listOf(schemaRead("set_temperature"), ModelEvent.Completed))
        add(listOf(ModelEvent.ToolCall("action", qualifiedMcpTool("set_temperature"), "{}"), ModelEvent.Completed))
        if (questions) {
            add(listOf(ModelEvent.ToolCall("async", "ask_async", """{"questions":[{"title":"Which room?"}]}"""), ModelEvent.Completed))
            add(listOf(ModelEvent.ToolCall("ask", "ask", """{"question":"Which room?"}"""), ModelEvent.Completed))
        }
        add(finishEvents("child finished"))
    }
}

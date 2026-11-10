@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.HookDecision
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.ToolUse
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

    /** A child has nobody to ask: a call that always asks for the person is refused, even when the parent approves all. */
    @Test fun aCallThatAlwaysAsksIsRefusedInAChildWhateverItsPolicy() = runBlocking {
        for (policy in ApprovalPolicy.entries) for (shared in listOf(true, false)) childApproval(policy, shared, alwaysAsk = true)
    }

    private suspend fun childApproval(policy: ApprovalPolicy, shared: Boolean, alwaysAsk: Boolean = false) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val captured = CompletableDeferred<SubagentSpec>()
        val finished = CompletableDeferred<List<ThreadEvent>>()
        val provider = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val model = ThreadScriptModel(childRounds(shared))
        lateinit var subagents: Subagents
        subagents = Subagents(launcher = { spec ->
            captured.complete(spec)
            runChild(spec, if (shared) subagents else null, provider, model, finished, alwaysAsk)
        }, scope = scope)
        try {
            runParent(subagents, policy)
            val events = withTimeout(5000) { finished.await() }
            assertEquals(policy, captured.await().approvalPolicy)
            assertTurnEvents(events)
            assertEquals(TurnStatus.COMPLETED, (events.last() as ThreadEvent.TurnCompleted).result.status)
            val approvals = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }.filterIsInstance<Approval>()
            // A custom launcher's child asks; the coordinator answers in its place.
            assertEquals(if (shared) 0 else 1, approvals.size)
            if (alwaysAsk) assertTrue(approvals.all { it.alwaysAsk && it.state == ApprovalState.DENIED })
            val runs = policy == ApprovalPolicy.APPROVE_ALL && !alwaysAsk
            assertEquals(if (runs) listOf("set_temperature") else emptyList<String>(), provider.calledTools)
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
        alwaysAsk: Boolean,
    ): Flow<ThreadEvent> = flow {
        try {
            threadBuilder(model).tools(provider).hook(if (alwaysAsk) confirmEveryTime() else confirmUntilApproved())
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

private fun confirmEveryTime(): RuntimeHook = object : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
        if (originalMcpToolName(call.toolName) != "set_temperature" || context.approvedConfirmation) HookDecision.Allow()
        else HookDecision.RequireConfirmation("Needs the person", "Set it?", alwaysAsk = true)
}

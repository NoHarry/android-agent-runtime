@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.*
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.runtime.thread.AutomaticApproval
import cc.noharry.agent.runtime.thread.approvalDomain
import cc.noharry.agent.runtime.thread.automaticApproval
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** A hook that wants the person's own answer each time: no policy, remembered area or earlier yes gives it. */
class ThreadAlwaysAskTest {
    @Test fun askAsksForEachCallAndAYesForTheThreadCountsAsOnce() = runBlocking {
        val provider = provider()
        val hook = AlwaysAskHook()
        runtime(provider, hook).use { runtime ->
            val thread = runtime.startThread(ThreadOptions(approvalPolicy = ApprovalPolicy.ASK))
            val approvals = thread.answerEach(twoCalls(), ApprovalScope.THREAD)
            assertEquals(2, approvals.size)
            assertTrue(approvals.all { it.alwaysAsk })
            assertEquals(2, provider.calledTools.size)
            assertEquals(listOf(ApprovalSource.USER, ApprovalSource.USER), thread.calls().map { it.approvedBy })
            // The saved approvals still say it, for a client that redraws the conversation.
            assertTrue(thread.items().filterIsInstance<Approval>().all { it.alwaysAsk })
            // The yes "for the thread" saved nothing: a later turn that remembers still asks.
            hook.alwaysAsk = false
            val later = thread.answerEach("once more", options = TurnOptions(approvalPolicy = ApprovalPolicy.REMEMBER))
            assertFalse(later.single().alwaysAsk)
        }
    }

    @Test fun approveAllStillAsks() = runBlocking {
        val provider = provider()
        runtime(provider).use { runtime ->
            val thread = runtime.startThread(ThreadOptions(approvalPolicy = ApprovalPolicy.APPROVE_ALL))
            assertEquals(2, thread.answerEach(twoCalls()).size)
            assertEquals(2, provider.calledTools.size)
            assertEquals(listOf(ApprovalSource.USER, ApprovalSource.USER), thread.calls().map { it.approvedBy })
        }
    }

    @Test fun approveAllGivenWhileTheCallWaitsDoesNotAnswerIt() = runBlocking {
        val provider = provider()
        runtime(provider).use { runtime ->
            val thread = runtime.startThread()
            val asked = mutableListOf<Approval>()
            thread.runStreamed(twoCalls()).collect { event ->
                val approval = (event as? ThreadEvent.ItemStarted)?.item as? Approval ?: return@collect
                runtime.resumeThread(thread.id, ThreadOptions(approvalPolicy = ApprovalPolicy.APPROVE_ALL))
                assertEquals(ApprovalState.PENDING, approval.state)
                asked += approval
                approval.approve()
            }
            assertEquals(2, asked.size)
        }
    }

    @Test fun rememberAsksEveryTimeAndKeepsNoYes() = runBlocking {
        val provider = provider()
        val hook = AlwaysAskHook()
        runtime(provider, hook).use { runtime ->
            val thread = runtime.startThread(ThreadOptions(approvalPolicy = ApprovalPolicy.REMEMBER))
            // Two calls of one tool in one turn: the yes to the first, even for the thread, does not answer the second.
            assertEquals(2, thread.answerEach(twoCalls(), ApprovalScope.THREAD).size)
            // Nothing was remembered: the same tool asked for in the ordinary way still asks.
            hook.alwaysAsk = false
            assertEquals(1, thread.answerEach("once more").size)
            // That yes is remembered now, and still does not answer a call that always asks.
            hook.alwaysAsk = true
            assertEquals(1, thread.answerEach("and again").size)
            hook.alwaysAsk = false
            assertEquals(0, thread.answerEach("remembered").size)
            assertEquals(5, provider.calledTools.size)
        }
    }

    @Test fun denyAllRefusesWithoutAsking() = runBlocking {
        val provider = provider()
        runtime(provider).use { runtime ->
            val thread = runtime.startThread(ThreadOptions(approvalPolicy = ApprovalPolicy.DENY_ALL))
            assertEquals(0, thread.answerEach(twoCalls()).size)
            assertTrue(provider.calledTools.isEmpty())
            val calls = thread.calls()
            assertEquals(2, calls.size)
            assertTrue(calls.all { it.status == ItemStatus.DECLINED && it.approvedBy == null })
        }
    }

    @Test fun noPolicyAreaOrEarlierYesAnswersACallThatAlwaysAsks() {
        val host = checkNotNull(approvalDomain("web_fetch", """{"url":"https://example.com/a"}"""))
        val samePage = approvalDomain("web_fetch", """{"url":"https://example.com/b"}""")
        val tool = checkNotNull(approvalDomain("mcp__cabin__unlock", "{}"))
        for (policy in listOf(ApprovalPolicy.ASK, ApprovalPolicy.REMEMBER, ApprovalPolicy.APPROVE_ALL)) {
            assertNull(automaticApproval(policy, samePage, { setOf(host) }, setOf(host), alwaysAsk = true))
            assertNull(automaticApproval(policy, tool, { setOf(tool) }, setOf(tool), alwaysAsk = true))
        }
        assertEquals(AutomaticApproval(ApprovalState.DENIED, null),
            automaticApproval(ApprovalPolicy.DENY_ALL, tool, { emptySet() }, emptySet(), alwaysAsk = true))
        // Without the flag the same areas are answered as before.
        assertEquals(AutomaticApproval(ApprovalState.APPROVED, ApprovalSource.USER),
            automaticApproval(ApprovalPolicy.ASK, samePage, { emptySet() }, setOf(host)))
    }

    @Test fun anyHookThatAlwaysAsksMakesTheCallAlwaysAsk() = runBlocking {
        val provider = provider()
        threadBuilder(ThreadScriptModel(scripts(1))).tools(provider).hook(confirmUntilApproved()).hook(AlwaysAskHook()).build().use { runtime ->
            val thread = runtime.startThread(ThreadOptions(approvalPolicy = ApprovalPolicy.APPROVE_ALL))
            assertTrue(thread.answerEach("set it").single().alwaysAsk)
        }
    }

    private fun provider() = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))

    private fun runtime(provider: LoopMcpProvider, hook: RuntimeHook = AlwaysAskHook()): cc.noharry.agent.AgentRuntime =
        threadBuilder(ThreadScriptModel(scripts(2, 1, 1, 1, 1))).tools(provider).hook(hook).build()

    /** Each turn reads the schema once, then calls set_temperature [calls] times in a row, then finishes. */
    private fun scripts(vararg calls: Int): List<List<ModelEvent>> = calls.withIndex().flatMap { (turn, count) ->
        listOf(listOf(schemaRead("set_temperature"), ModelEvent.Completed)) +
            (1..count).map { listOf(ModelEvent.ToolCall("t$turn-c$it", qualifiedMcpTool("set_temperature"), """{"value":$it}"""), ModelEvent.Completed) } +
            listOf(finishEvents("done"))
    }

    private fun twoCalls() = "set it twice"

    /** Runs one turn, approving each approval it asks with [scope]; the approvals, in order. */
    private suspend fun AgentThread.answerEach(
        input: String,
        scope: ApprovalScope = ApprovalScope.ONCE,
        options: TurnOptions = TurnOptions(),
    ): List<Approval> {
        val approvals = mutableListOf<Approval>()
        val events = mutableListOf<ThreadEvent>()
        runStreamed(input, options).collect { event ->
            events += event
            val approval = (event as? ThreadEvent.ItemStarted)?.item as? Approval ?: return@collect
            approvals += approval
            assertEquals(RespondResult.ACCEPTED, approval.approve(scope))
        }
        assertTurnEvents(events)
        assertEquals(TurnStatus.COMPLETED, (events.last() as ThreadEvent.TurnCompleted).result.status)
        return approvals
    }

    private suspend fun AgentThread.calls(): List<ThreadItem.ToolCall> =
        items().filterIsInstance<ThreadItem.ToolCall>().filter { it.tool == qualifiedMcpTool("set_temperature") }
}

/** Asks before set_temperature runs; every time while [alwaysAsk], else in the ordinary way. */
private class AlwaysAskHook(@Volatile var alwaysAsk: Boolean = true) : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
        if (call.source != ToolSource.BACKEND || context.approvedConfirmation) HookDecision.Allow()
        else HookDecision.RequireConfirmation("Changes the cabin", "Set the temperature?", alwaysAsk = alwaysAsk)
}

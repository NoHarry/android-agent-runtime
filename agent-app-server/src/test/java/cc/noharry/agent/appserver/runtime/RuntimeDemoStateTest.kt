package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.*
import cc.noharry.agent.appserver.protocol.RuntimeDemoState
import cc.noharry.agent.appserver.protocol.pendingConfirmationActionIds
import org.junit.Assert.*
import org.junit.Test

class RuntimeDemoStateTest {
    @Test fun completedItemsPopulateStateAndUsage() {
        val events = listOf(
            testTurnStarted("run-1"),
            ThreadEvent.ItemCompleted(ThreadItem.Reasoning("thinking", "run-1", "thinking", ItemStatus.COMPLETED)),
            ThreadEvent.ItemCompleted(ThreadItem.AgentMessage("answer", "run-1", "done", MessagePhase.FINAL, ItemStatus.COMPLETED)),
            ThreadEvent.UsageUpdated("run-1", Usage(120, 12)),
            ThreadEvent.ItemCompleted(ThreadItem.Notice("retry", "run-1", NoticeKind.RETRY, "Retry")),
            testToolStarted("run-1", "web_fetch", "{}", "tool-1"),
            testToolCompleted("run-1", "web_fetch", "{}", "tool-1"),
            ThreadEvent.ItemCompleted(ThreadItem.FileChange("file", "run-1", "/app/index.html", 3, 1, "write_file")),
            ThreadEvent.TurnCompleted(TurnResult("run-1", TurnStatus.COMPLETED, "done", emptyList())),
        )
        val state = events.fold(RuntimeDemoState()) { current, event -> current.reduce(event) }
        assertEquals("done", state.assistantPreview)
        assertEquals("thinking", state.thinkingPreview)
        assertEquals("COMPLETED", state.finalStatus)
        assertEquals(listOf("web_fetch"), state.finishedTools)
        assertTrue(state.runningTools.isEmpty())
        assertEquals("/app/index.html", state.changedFiles.single().path)
        assertEquals(120, state.tokenUsage.inputTokens)
        assertEquals(1, state.retryCount)
        assertTrue(state.visibleSignals().containsAll(listOf("thinking", "assistant", "tools", "diff", "usage", "result")))
    }

    @Test fun approvalCompletionRemovesThePendingAction() {
        val waiting = RuntimeDemoState().reduce(ThreadEvent.ItemStarted(TestApproval()))
        assertEquals(listOf("confirm-1"), waiting.pendingConfirmationActionIds())
        val approved = waiting.reduce(ThreadEvent.ItemCompleted(TestApproval(ApprovalState.APPROVED)))
        assertTrue(approved.pendingConfirmationActionIds().isEmpty())
    }

    @Test fun terminalFailureStopsApprovalActionsAndRetainsError() {
        val state = RuntimeDemoState().reduce(ThreadEvent.ItemStarted(TestApproval()))
            .reduce(ThreadEvent.TurnFailed("run-1", TurnError("FAILED", "failed")))
        assertEquals("FAILED", state.errorCode)
        assertTrue(state.pendingConfirmationActionIds().isEmpty())
    }
}

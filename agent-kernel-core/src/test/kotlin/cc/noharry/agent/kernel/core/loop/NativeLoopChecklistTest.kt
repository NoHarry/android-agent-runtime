package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.context.TodoItem
import cc.noharry.agent.kernel.core.context.TodoState
import cc.noharry.agent.kernel.core.context.TodoStatus
import cc.noharry.agent.kernel.core.context.TodoStorePort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeLoopChecklistTest {
    private val open = TodoState("s", listOf(TodoItem("导航", TodoStatus.completed), TodoItem("放歌", TodoStatus.in_progress), TodoItem("播报", TodoStatus.pending)), 1L)
    private val todoRound = listOf(ModelMessage(role = "tool", toolCallId = "t", toolName = "update_todo", content = "{}"))

    @Test fun successfulFinishClosesTheRunsOwnChecklist() = runBlocking {
        val store = MemoryTodoStore(open)
        store.closeChecklistOnSuccess("s", result(RuntimeCompletionOutcome.SUCCEEDED), todoRound) { 9L }
        assertEquals(List(3) { TodoStatus.completed }, store.state!!.items.map { it.status })
        assertEquals(listOf("导航", "放歌", "播报"), store.state!!.items.map { it.content })
        assertEquals(9L, store.state!!.updatedAtEpochMillis)
    }

    @Test fun partialBlockedOrFailedRunsKeepTheirOpenItems() = runBlocking {
        for (outcome in listOf(RuntimeCompletionOutcome.PARTIAL, RuntimeCompletionOutcome.BLOCKED, RuntimeCompletionOutcome.FAILED, RuntimeCompletionOutcome.UNKNOWN)) {
            val store = MemoryTodoStore(open)
            store.closeChecklistOnSuccess("s", result(outcome), todoRound) { 9L }
            assertEquals(open, store.state)
            assertEquals(0, store.saves)
        }
    }

    @Test fun aChecklistFromAnEarlierTurnIsLeftAloneAndStoreFailuresAreIgnored() = runBlocking {
        val store = MemoryTodoStore(open)
        store.closeChecklistOnSuccess("s", result(RuntimeCompletionOutcome.SUCCEEDED), listOf(ModelMessage(role = "user", content = "hi"))) { 9L }
        assertEquals(open, store.state)
        val failing = MemoryTodoStore(open, failSave = true)
        failing.closeChecklistOnSuccess("s", result(RuntimeCompletionOutcome.SUCCEEDED), todoRound) { 9L }
        assertEquals(open, failing.state)
        val closed = MemoryTodoStore(open.copy(items = open.items.map { it.copy(status = TodoStatus.completed) }))
        closed.closeChecklistOnSuccess("s", result(RuntimeCompletionOutcome.SUCCEEDED), todoRound) { 9L }
        assertEquals(0, closed.saves)
    }

    private fun result(outcome: RuntimeCompletionOutcome) =
        RuntimeResult("r", RuntimeRunStatus.COMPLETED, "done", completion = RuntimeCompletionSummary(outcome = outcome))

    private class MemoryTodoStore(var state: TodoState?, private val failSave: Boolean = false) : TodoStorePort {
        var saves = 0
        override suspend fun load(sessionId: String): TodoState? = state
        override suspend fun save(sessionId: String, state: TodoState): Boolean {
            if (failSave) error("disk full")
            saves++
            this.state = state
            return true
        }
        override suspend fun clear(sessionId: String): Boolean = true.also { state = null }
    }
}

package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.core.context.TodoStatus
import cc.noharry.agent.kernel.core.context.TodoStorePort
import kotlinx.coroutines.CancellationException

/**
 * A finish that explicitly declares outcome "success" and is summarized SUCCEEDED closes this run's own checklist, so the model never spends a model round only to
 * mark todos completed. Partial, blocked or failed runs keep their open items, and a checklist this run never
 * updated belongs to an earlier turn and is left alone. The store is persistence only; failures here never
 * change the run result.
 */
internal suspend fun TodoStorePort?.closeChecklistOnSuccess(
    sessionId: String,
    result: RuntimeResult,
    history: List<ModelMessage>,
    nowEpochMillis: () -> Long,
) {
    val store = this ?: return
    if (result.completion.outcome != RuntimeCompletionOutcome.SUCCEEDED) return
    if (sessionId.isBlank() || history.none { it.role == "tool" && it.toolName == UPDATE_TODO_TOOL_NAME }) return
    try {
        val state = store.load(sessionId) ?: return
        if (state.items.all { it.status == TodoStatus.completed }) return
        store.save(
            sessionId,
            state.copy(items = state.items.map { it.copy(status = TodoStatus.completed) }, updatedAtEpochMillis = nowEpochMillis()),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Checklist persistence is best effort; the completed run result stands.
    }
}

private const val UPDATE_TODO_TOOL_NAME = "update_todo"

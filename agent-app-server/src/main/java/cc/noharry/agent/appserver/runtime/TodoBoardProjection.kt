@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.protocol.TodoEntry
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome

/**
 * Todo board projection rules for the debug console.
 *
 * The board is event-sourced from public `update_todo` results and has no file-backed source:
 * todo JSON written under `memory/todo` by earlier builds must never reach the UI. A session
 * shows tasks only while a run that published them owns it.
 */

/**
 * The board change an event implies: a published `update_todo` board, or, for a fully successful result, the
 * current board with every item completed, so the card ends closed without a model round spent on todos. The
 * Kernel closes the stored checklist only for a finish that explicitly declared outcome "success"; the public
 * result does not carry that flag, so after a legacy finish the card can show closed while the store stays open.
 */
internal fun ThreadEvent.todoBoardUpdate(current: () -> List<TodoEntry>?): List<TodoEntry>? {
    todoBoardOrNull()?.let { return it }
    val result = (this as? ThreadEvent.TurnCompleted)?.result ?: return null
    // A partial or blocked answer completes the turn but keeps its open items, as the kernel's checklist does.
    if (result.hostCompletion() != RuntimeCompletionOutcome.SUCCEEDED) return null
    val board = current()?.takeIf { entries -> entries.any { it.status != TODO_STATUS_COMPLETED } } ?: return null
    return board.map { it.copy(status = TODO_STATUS_COMPLETED) }
}

private const val TODO_STATUS_COMPLETED = "completed"

/** Extracts the todo board carried by a public `update_todo` finished event, if any. */
internal fun ThreadEvent.todoBoardOrNull(): List<TodoEntry>? =
    ((this as? ThreadEvent.ItemCompleted)?.item as? cc.noharry.agent.ThreadItem.ToolCall)
        ?.takeIf { it.tool == DEBUG_UPDATE_TODO_TOOL_NAME }
        ?.let { decodeTodoToolResult(it.output.orEmpty()) }

/**
 * Resolves the board a session should display.
 *
 * Ownership is exclusive and ordered: a live run answers for its own board, otherwise the
 * finished snapshot answers for the last run, otherwise the session has published nothing.
 * Every absent case renders as empty, so a new run never inherits the previous run's board
 * and no on-disk todo file can repopulate the card.
 */
internal fun resolveEventTodoBoard(
    live: ActiveRunRecord?,
    finished: ActiveRunSnapshot?,
): List<TodoEntry> {
    if (live != null) return live.todoBoard.orEmpty()
    return finished?.todoBoard.orEmpty()
}

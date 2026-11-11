package cc.noharry.agent.appserver.session
import cc.noharry.agent.appserver.protocol.isTurnStart
import cc.noharry.agent.appserver.evidence.DiagnosticEntry
import cc.noharry.agent.appserver.evidence.isInternalToolName
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ConfirmationStatus
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.foldedByDefault
import cc.noharry.agent.appserver.runtime.append
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal class TranscriptStore {
    private val items = mutableListOf<ConversationItem>()
    private val confirmationItemsByPendingId = mutableMapOf<String, Long>()
    private val sdkItems = mutableMapOf<String, Long>()
    private val sdkSnapshots = mutableMapOf<String, cc.noharry.agent.ThreadItem>()
    private val foldedItems = mutableMapOf<Long, Boolean>()
    private var nextItemId: Long = 1
    private var currentTurnId: Long = 0
    private var lastAssistantItemId: Long? = null
    private var lastThinkingItemId: Long? = null

    fun snapshot(): List<ConversationItem> = items.toList()

    /** Trades everything held with [other]: a session's transcript moves between the screen's and a background one. */
    internal fun swapContents(other: TranscriptStore) {
        swapList(items, other.items)
        swapMap(confirmationItemsByPendingId, other.confirmationItemsByPendingId)
        swapMap(foldedItems, other.foldedItems)
        swapMap(sdkItems, other.sdkItems)
        swapMap(sdkSnapshots, other.sdkSnapshots)
        nextItemId = other.nextItemId.also { other.nextItemId = nextItemId }
        currentTurnId = other.currentTurnId.also { other.currentTurnId = currentTurnId }
        lastAssistantItemId = other.lastAssistantItemId.also { other.lastAssistantItemId = lastAssistantItemId }
        lastThinkingItemId = other.lastThinkingItemId.also { other.lastThinkingItemId = lastThinkingItemId }
    }

    /** Whether an approval waits here under [pendingId]. */
    internal fun waitsFor(pendingId: String): Boolean =
        items.any { it is ConversationItem.ConfirmationItem && it.pendingId == pendingId && it.status == ConfirmationStatus.PENDING }

    internal fun replaceAll(restored: List<ConversationItem>) {
        items.clear()
        items += restored
        foldedItems.clear()
        sdkItems.clear()
        sdkSnapshots.clear()
        nextItemId = (items.maxOfOrNull { it.id } ?: 0L) + 1L
        currentTurnId = items.lastOrNull { it.isTurnStart }?.id ?: 0L
        lastAssistantItemId = null
        lastThinkingItemId = null
        confirmationItemsByPendingId.clear()
    }

    /** Opens or folds [itemId] as the person chose; false when there is no such item. */
    internal fun setFolded(itemId: Long, folded: Boolean): Boolean {
        if (items.none { it.id == itemId }) return false
        foldedItems[itemId] = folded
        return true
    }

    /** The person's open/closed choices for the items still here. */
    internal fun folded(): Map<Long, Boolean> = foldedItems.filterKeys { id -> items.any { it.id == id } }

    internal fun openTurn(text: String) {
        currentTurnId = nextItemId++
        sdkItems.clear()
        sdkSnapshots.clear()
        lastAssistantItemId = null
        lastThinkingItemId = null
        items += ConversationItem.TurnHeader(
            id = currentTurnId,
            label = turnLabel(text),
            preview = text.take(TURN_PREVIEW_CHARS).replace("\n", " "),
            fullText = text,
        )
        trimItems()
    }

    internal fun appendConfirmationRequired(
        pendingId: String,
        domain: String,
        mode: ConfirmationMode,
    ) {
        val existingId = confirmationItemsByPendingId[pendingId]
        if (existingId != null) {
            updateConfirmation(pendingId, ConfirmationStatus.PENDING)
            return
        }
        val id = nextItemId++
        confirmationItemsByPendingId[pendingId] = id
        items += ConversationItem.ConfirmationItem(
            id = id,
            pendingId = pendingId,
            domain = domain,
            mode = mode,
            createdAt = nowEpochMillis(),
        )
        trimItems()
    }

    internal fun updateConfirmation(pendingId: String, status: ConfirmationStatus): Boolean {
        val id = confirmationItemsByPendingId[pendingId] ?: return false
        val index = items.indexOfFirst { it.id == id && it is ConversationItem.ConfirmationItem }
        if (index < 0) return false
        val item = items[index] as ConversationItem.ConfirmationItem
        items[index] = item.copy(
            status = status,
            resolvedAt = if (status == ConfirmationStatus.PENDING) null else nowEpochMillis(),
        )
        return true
    }

    internal fun appendThinkingDelta(text: String) {
        appendDelta(text, TranscriptRole.THINKING, { lastThinkingItemId }, { lastThinkingItemId = it })
    }

    internal fun appendAssistantDelta(text: String) {
        appendDelta(text, TranscriptRole.ASSISTANT, { lastAssistantItemId }, { lastAssistantItemId = it })
    }

    internal fun appendFinalResponse(text: String) {
        if (text.isBlank()) return
        val normalized = text.trimForDisplay()
        // Streaming already painted the same assistant body this turn — avoid a second copy. An earlier turn's
        // identical reply ("不客气" twice) is not this one.
        val lastAssistant = lastAssistantItemId?.let { id ->
            items.firstOrNull { it.id == id } as? ConversationItem.MessageItem
        }
        if (lastAssistant != null && lastAssistant.content == normalized) {
            items[items.indexOf(lastAssistant)] = lastAssistant.copy(final = true)
            return
        }
        items += ConversationItem.MessageItem(
            id = nextItemId++,
            role = TranscriptRole.ASSISTANT,
            content = normalized,
            final = true,
        )
        trimItems()
    }

    internal fun toolIssuesForCurrentTurn(): ToolIssueSummary = currentToolIssues()

    internal fun markPendingToolCallsUnknown() {
        for (index in items.indices) {
            val item = items[index] as? ConversationItem.ToolCallItem ?: continue
            if (item.id > currentTurnId && item.status == ToolCallStatus.RUNNING) {
                items[index] = item.copy(status = ToolCallStatus.UNKNOWN, finishedAt = nowEpochMillis())
            }
        }
    }

    internal fun appendMessage(role: TranscriptRole, content: String) {
        items += ConversationItem.MessageItem(
            id = nextItemId++,
            role = role,
            content = content.trimForDisplay(),
        )
        trimItems()
    }

    internal fun appendStatus(message: String) {
        // Status is ambient noise in the stream; it shows folded ([foldedByDefault]) so Debug stays scannable.
        appendMessage(TranscriptRole.STATUS, message)
    }

    internal fun appendError(title: String, message: String) {
        appendMessage(TranscriptRole.ERROR, "$title: $message")
    }

    internal fun appendRuntime(title: String, body: String, markdown: Boolean = false) {
        items += ConversationItem.RuntimePhaseItem(
            id = nextItemId++,
            title = title,
            body = body.trimForDisplay(),
            markdown = markdown,
        )
        trimItems()
    }

    internal fun appendRuntimeResult(resultStatus: String, response: String) {
        appendRuntime(
            title = "Runtime result $resultStatus",
            body = decorateRuntimeResultBody(response, currentToolIssues()),
            markdown = true,
        )
    }

    internal fun appendDiagnostics(entry: DiagnosticEntry) {
        appendMessage(
            role = TranscriptRole.RUNTIME,
            content = "${entry.title}\n${entry.body}",
        )
    }

    private fun appendDelta(
        text: String,
        role: TranscriptRole,
        getId: () -> Long?,
        setId: (Long?) -> Unit,
    ) {
        if (text.isBlank()) return
        val id = getId()
        val index = items.indexOfLast { it.id == id && it is ConversationItem.MessageItem }
        if (index >= 0) {
            val item = items[index] as ConversationItem.MessageItem
            items[index] = item.copy(content = (item.content + text).trimForDisplay())
            return
        }
        val item = ConversationItem.MessageItem(
            id = nextItemId++,
            role = role,
            content = text.trimForDisplay(),
        )
        items += item
        setId(item.id)
        trimItems()
    }

    private fun currentToolIssues(): ToolIssueSummary {
        var failed = 0
        var unknown = 0
        items.asSequence()
            .filterIsInstance<ConversationItem.ToolCallItem>()
            .filter { it.id > currentTurnId }
            .filterNot { it.toolName.isInternalToolName() }
            .forEach { item ->
                when (item.status) {
                    ToolCallStatus.FAILED -> failed++
                    ToolCallStatus.UNKNOWN,
                    ToolCallStatus.RUNNING -> unknown++
                    ToolCallStatus.DONE -> Unit
                }
            }
        return ToolIssueSummary(failed = failed, unknown = unknown)
    }

    internal fun recordThreadEvent(event: cc.noharry.agent.ThreadEvent, mode: ConfirmationMode) {
        when (event) {
            is cc.noharry.agent.ThreadEvent.ItemStarted -> upsertSdkItem(event.item, mode)
            is cc.noharry.agent.ThreadEvent.ItemUpdated -> upsertSdkItem(event.item, mode)
            is cc.noharry.agent.ThreadEvent.ItemCompleted -> {
                upsertSdkItem(event.item, mode)
                (event.item as? cc.noharry.agent.ThreadItem.UserMessage)?.requestId?.let(steering::remove)
            }
            is cc.noharry.agent.ThreadEvent.ItemDelta -> {
                val item = sdkSnapshots[event.itemId] ?: return
                val updated = when (item) {
                    is cc.noharry.agent.ThreadItem.AgentMessage -> item.copy(text = item.text + event.text)
                    is cc.noharry.agent.ThreadItem.Reasoning -> item.copy(text = item.text + event.text)
                    is cc.noharry.agent.ThreadItem.ToolCall -> item.copy(output = item.output.orEmpty() + event.text)
                    else -> item
                }
                upsertSdkItem(updated, mode)
            }
            is cc.noharry.agent.ThreadEvent.TurnCompleted -> {
                if (event.result.status == cc.noharry.agent.TurnStatus.WAITING_FOR_ANSWER) {
                    showQuestion((event.result.pending as? cc.noharry.agent.Question)?.text ?: event.result.finalResponse)
                }
                closeTurn(event.result.status.resultName(), event.result.finalResponse.trim())
            }
            is cc.noharry.agent.ThreadEvent.TurnFailed -> {
                closeTurn(cc.noharry.agent.TurnStatus.FAILED.resultName(), event.error.message)
                appendError(event.error.code, event.error.message)
            }
            else -> Unit
        }
    }

    /**
     * A turn that stopped to ask: the model's words already showed the question as it streamed, so that message becomes
     * the turn's reply; only when they did not is the question added.
     */
    private fun showQuestion(text: String) {
        val question = text.trim().takeIf { it.isNotEmpty() } ?: return
        val shown = items.lastOrNull {
            it is ConversationItem.MessageItem && it.role == TranscriptRole.ASSISTANT && it.id > currentTurnId &&
                it.content.trim() == question
        } as? ConversationItem.MessageItem
        if (shown != null) {
            items[items.indexOf(shown)] = shown.copy(final = true)
        } else {
            items += ConversationItem.MessageItem(nextItemId++, TranscriptRole.ASSISTANT, question, final = true)
            trimItems()
        }
    }

    /** A turn whose tools failed ends with the result line clients mark the turn failed by (the phone's footnote). */
    private fun closeTurn(status: String, response: String) {
        markPendingToolCallsUnknown()
        if (currentToolIssues().hasIssues) appendRuntimeResult(status, response)
    }

    /** Before steering goes out: [shown] is what its bubble says, null when it is the app's own input (no bubble). */
    internal fun expectSteering(requestId: String, shown: String?) {
        steering[requestId] = SteeringDisplay(shown)
    }

    internal fun forgetSteering(requestId: String) {
        steering.remove(requestId)
    }

    private fun upsertSdkItem(item: cc.noharry.agent.ThreadItem, mode: ConfirmationMode) {
        sdkSnapshots[item.id] = item
        if (item is cc.noharry.agent.ThreadItem.UserMessage && !item.steer) return
        // A blocking question is the turn's reply; [showQuestion] places it when the turn ends.
        if (item is cc.noharry.agent.Question && !item.async) return
        val display = (item as? cc.noharry.agent.ThreadItem.UserMessage)?.requestId?.let(steering::get)
        // Plan reminders, delegation reports and the like: their runtime line already says what went in.
        if (display != null && display.shown == null) return
        val id = sdkItems.getOrPut(item.id) { nextItemId++ }
        val previous = items.firstOrNull { it.id == id }
        val projected = projectSdkItem(item, id, mode, previous, display?.shown) ?: return
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) items += projected else items[index] = projected
        if (item is cc.noharry.agent.Approval) confirmationItemsByPendingId[item.id] = id
        trimItems()
    }

    private fun trimItems() {
        while (items.size > MAX_TRANSCRIPT_ITEMS) {
            items.removeAt(0)
        }
    }

    private fun nowEpochMillis(): Long = System.currentTimeMillis()
}

private const val MAX_TRANSCRIPT_ITEMS = 300
private const val TURN_PREVIEW_CHARS = 60

private fun <T> swapList(a: MutableList<T>, b: MutableList<T>) {
    val held = a.toList()
    a.clear()
    a += b
    b.clear()
    b += held
}

private fun <K, V> swapMap(a: MutableMap<K, V>, b: MutableMap<K, V>) {
    val held = a.toMap()
    a.clear()
    a += b
    b.clear()
    b += held
}

private data class SteeringDisplay(val shown: String?)

/**
 * Steering this app sent, by request id: the words a bubble shows, or null for input that is not the person's. Shared
 * by every session's transcript, since the item can arrive after the person switched to another session.
 */
private val steering = java.util.concurrent.ConcurrentHashMap<String, SteeringDisplay>()

/** The run status names the result line has always carried. */
private fun cc.noharry.agent.TurnStatus.resultName(): String = when (this) {
    cc.noharry.agent.TurnStatus.COMPLETED -> "COMPLETED"
    cc.noharry.agent.TurnStatus.WAITING_FOR_ANSWER -> "WAITING_USER_INPUT"
    cc.noharry.agent.TurnStatus.INTERRUPTED -> "CANCELLED"
    cc.noharry.agent.TurnStatus.FAILED -> "FAILED"
}

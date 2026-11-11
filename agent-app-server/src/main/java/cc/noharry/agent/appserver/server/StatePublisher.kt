package cc.noharry.agent.appserver.server

import cc.noharry.agent.appserver.protocol.isTurnStart
import cc.noharry.agent.appserver.protocol.ApprovalRequest
import cc.noharry.agent.appserver.protocol.ApprovalResolved
import cc.noharry.agent.appserver.protocol.ConfirmationStatus
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ItemChanged
import cc.noharry.agent.appserver.protocol.ItemDelta
import cc.noharry.agent.appserver.protocol.ItemRemoved
import cc.noharry.agent.appserver.protocol.ServerNotifications
import cc.noharry.agent.appserver.protocol.StateChanged
import cc.noharry.agent.appserver.protocol.StateSection
import cc.noharry.agent.appserver.protocol.ThreadListChanged
import cc.noharry.agent.appserver.protocol.ThreadSnapshot
import cc.noharry.agent.appserver.protocol.TrajectorySection
import cc.noharry.agent.appserver.protocol.TurnCompleted
import cc.noharry.agent.appserver.protocol.TurnStarted
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.protocol.jsonrpc.NotificationMethod
import cc.noharry.agent.appserver.protocol.jsonrpc.ProtocolJson


/**
 * What a state change means on the wire: notifications, and approvals to ask the clients that take them (those about
 * [focusThreadId], the current thread, go to every such client; the others only to the clients following theirs).
 */
internal data class Publication(
    val notifications: List<Outgoing>,
    val asks: List<ApprovalRequest>,
    val focusThreadId: String = "",
)

/**
 * One notification and who gets it: only clients that follow the trajectory get [trajectory] ones (large, changing
 * often), and only clients following [thread] get those about a thread beside the current one.
 */
internal class Outgoing(
    val notification: JsonRpcNotification,
    val trajectory: Boolean = false,
    val thread: String? = null,
)

/**
 * Turns each state the server reaches into what its clients need to follow it: the thread list when it changed, the
 * new thread's items when the thread switched, else each item that started, changed (only its added text when that
 * is all) or went, each section that changed, a turn's start and end, and approvals that came up or were settled; the
 * same item, turn and approval changes for every thread kept live beside the current one ([ServerState.threads]), for
 * the clients following it. [published] is what every subscribed client holds; a client that joins starts from it.
 */
internal class StatePublisher {
    var published: ServerState? = null
        private set

    fun publish(next: ServerState): Publication {
        val before = published
        published = next
        if (before == null) return Publication(emptyList(), pendingApprovals(next, emptyMap()), next.activeSessionId)
        val out = mutableListOf<Outgoing>()
        if (before.sessions != next.sessions || before.activeSessionId != next.activeSessionId) {
            out += Outgoing(notification(ServerNotifications.THREAD_LIST_CHANGED, ThreadListChanged(next.sessions, next.activeSessionId)))
        }
        val switched = before.activeSessionId != next.activeSessionId
        if (switched) {
            out += Outgoing(notification(ServerNotifications.THREAD_SELECTED, ThreadSnapshot(next.activeSessionId, next.items)))
        } else {
            out += itemChanges(next.activeSessionId, before.items, next.items).map(::Outgoing)
        }
        val previous = before.liveItems()
        out += backgroundChanges(previous, next)
        out += sectionChanges(before, next)
        out += turnChanges(before, next).map(::Outgoing)
        out += backgroundTurns(before, next)
        out += settledApprovals(previous, next)
        return Publication(out, pendingApprovals(next, previous), next.activeSessionId)
    }

    /** Item changes in the threads beside the current one, each for the clients following it. */
    private fun backgroundChanges(previous: Map<String, List<ConversationItem>>, next: ServerState): List<Outgoing> =
        next.threads.filterKeys { it != next.activeSessionId }.flatMap { (thread, items) ->
            itemChanges(thread, previous[thread].orEmpty(), items).map { Outgoing(it, thread = thread) }
        }

    /** Runs starting and ending in threads beside the current one (the current thread's come from its run section). */
    private fun backgroundTurns(before: ServerState, next: ServerState): List<Outgoing> {
        val started = next.runningThreads.filterKeys { it !in before.runningThreads && it != next.activeSessionId }
            .map { (thread, requestId) -> Outgoing(notification(ServerNotifications.TURN_STARTED, TurnStarted(thread, requestId)), thread = thread) }
        val ended = before.runningThreads.filterKeys { it !in next.runningThreads && it != next.activeSessionId }
            .map { (thread, requestId) -> Outgoing(notification(ServerNotifications.TURN_COMPLETED, TurnCompleted(thread, requestId)), thread = thread) }
        return started + ended
    }

    private fun itemChanges(threadId: String, before: List<ConversationItem>, after: List<ConversationItem>): List<JsonRpcNotification> {
        val old = before.associateBy { it.id }
        val out = mutableListOf<JsonRpcNotification>()
        val kept = after.mapTo(HashSet()) { it.id }
        before.filter { it.id !in kept }.forEach { out += notification(ServerNotifications.ITEM_REMOVED, ItemRemoved(threadId, it.id)) }
        after.forEach { item ->
            val was = old[item.id]
            when {
                was == null -> out += notification(ServerNotifications.ITEM_STARTED, ItemChanged(threadId, item))
                was == item -> Unit
                else -> out += appended(was, item)?.let { notification(ServerNotifications.ITEM_DELTA, ItemDelta(threadId, item.id, it)) }
                    ?: notification(ServerNotifications.ITEM_UPDATED, ItemChanged(threadId, item))
            }
        }
        return out
    }

    /** The text added to a message's end, when nothing else about it changed. */
    private fun appended(was: ConversationItem, now: ConversationItem): String? {
        if (was !is ConversationItem.MessageItem || now !is ConversationItem.MessageItem) return null
        if (now.copy(content = was.content) != was || !now.content.startsWith(was.content)) return null
        return now.content.substring(was.content.length)
    }

    private fun sectionChanges(before: ServerState, next: ServerState): List<Outgoing> {
        val old: Map<String, StateSection> = before.sections().associateBy { it.key }
        return next.sections().filter { old[it.key] != it }.map(::sectionOutgoing)
    }

    private fun turnChanges(before: ServerState, next: ServerState): List<JsonRpcNotification> {
        val requestId = next.currentRequest?.requestId ?: before.currentRequest?.requestId.orEmpty()
        return when {
            !before.isRunning && next.isRunning ->
                listOf(notification(ServerNotifications.TURN_STARTED, TurnStarted(next.activeSessionId, requestId, next.runStartedAtEpochMillis)))
            before.isRunning && !next.isRunning ->
                listOf(notification(ServerNotifications.TURN_COMPLETED, TurnCompleted(next.activeSessionId, requestId, next.lastRun)))
            else -> emptyList()
        }
    }

    private fun settledApprovals(previous: Map<String, List<ConversationItem>>, next: ServerState): List<Outgoing> =
        next.liveItems().flatMap { (thread, items) ->
            val waiting = previous[thread].orEmpty().pendingConfirmations().mapTo(HashSet()) { it.pendingId }
            items.filterIsInstance<ConversationItem.ConfirmationItem>()
                .filter { it.pendingId in waiting && it.status != ConfirmationStatus.PENDING }
                .map {
                    val resolved = notification(ServerNotifications.APPROVAL_RESOLVED, ApprovalResolved(it.pendingId, it.status))
                    Outgoing(resolved, thread = thread.takeIf { it != next.activeSessionId })
                }
        }

    /** Approvals waiting now that were not before, in any live thread: each is asked once. */
    private fun pendingApprovals(state: ServerState, previous: Map<String, List<ConversationItem>>): List<ApprovalRequest> =
        state.liveItems().flatMap { (thread, items) -> pendingApprovals(thread, items, previous[thread].orEmpty()) }

    private fun pendingApprovals(thread: String, items: List<ConversationItem>, before: List<ConversationItem>): List<ApprovalRequest> {
        val asked = before.pendingConfirmations().mapTo(HashSet()) { it.pendingId }
        return items.pendingConfirmations().filter { it.pendingId !in asked }.map { item ->
            val call = items.toolCallFor(item)
            ApprovalRequest(
                threadId = thread,
                itemId = item.id,
                pendingId = item.pendingId,
                domain = item.domain,
                mode = item.mode,
                toolName = call?.toolName.orEmpty(),
                argumentsJson = call?.argumentsJson,
                alwaysAsk = item.alwaysAsk,
            )
        }
    }

    private fun <P> notification(method: NotificationMethod<P>, params: P) =
        JsonRpcNotification(method.name, ProtocolJson.encodeToJsonElement(method.params, params))

    private fun sectionOutgoing(section: StateSection): Outgoing =
        Outgoing(notification(ServerNotifications.STATE_CHANGED, StateChanged(section)), trajectory = section is TrajectorySection)
}

private fun List<ConversationItem>.pendingConfirmations(): List<ConversationItem.ConfirmationItem> =
    filterIsInstance<ConversationItem.ConfirmationItem>().filter { it.status == ConfirmationStatus.PENDING }

/** The tool call an approval is for: the latest one before it in its turn. */
private fun List<ConversationItem>.toolCallFor(item: ConversationItem.ConfirmationItem): ConversationItem.ToolCallItem? {
    val at = indexOf(item)
    if (at <= 0) return null
    return subList(0, at).asReversed().takeWhile { !it.isTurnStart }.filterIsInstance<ConversationItem.ToolCallItem>().firstOrNull()
}

/** Every live thread's items: the current one's and those kept live beside it. */
internal fun ServerState.liveItems(): Map<String, List<ConversationItem>> = threads + (activeSessionId to items)

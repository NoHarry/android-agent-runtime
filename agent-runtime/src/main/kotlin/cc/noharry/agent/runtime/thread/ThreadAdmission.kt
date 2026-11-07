package cc.noharry.agent.runtime.thread

import cc.noharry.agent.kernel.core.state.SessionRunSchedulerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** One queue accounts for both occupied threads and exhausted runtime capacity. All transitions share the store lock. */
internal class ThreadAdmission(
    private val coordination: ThreadCoordination,
) {
    private val owners = linkedMapOf<DefaultThreadRuntime, SessionRunSchedulerConfig>()
    private val active = linkedMapOf<String, ThreadTicket>()
    private val queued = mutableListOf<ThreadTicket>()
    private val drains = mutableMapOf<String, CompletableDeferred<Unit>>()

    fun register(owner: DefaultThreadRuntime, config: SessionRunSchedulerConfig) = synchronized(coordination.lock) {
        owners[owner] = config
    }

    fun unregister(owner: DefaultThreadRuntime) = synchronized(coordination.lock) {
        owners.remove(owner)
        queued.filter { it.owner === owner }.forEach { rejectQueued(it, "RUNTIME_CLOSED", "Runtime is closed.") }
        drain()
    }

    fun owners(): List<DefaultThreadRuntime> = synchronized(coordination.lock) { owners.keys.toList() }

    suspend fun enter(owner: DefaultThreadRuntime, threadId: String, generation: Long, rejectBusy: Boolean): ThreadAdmissionResult {
        val ticket = ThreadTicket(owner, threadId, generation)
        synchronized(coordination.lock) { enqueue(ticket, rejectBusy) }
        return try { ticket.result.await() } catch (cancelled: CancellationException) {
            release(ticket)
            throw cancelled
        }
    }

    private fun enqueue(ticket: ThreadTicket, rejectBusy: Boolean) {
        val slot = coordination.slot(ticket.threadId)
        val error = when {
            ticket.owner !in owners -> rejected("RUNTIME_CLOSED", "Runtime is closed.")
            slot.deleting || slot.generation != ticket.generation -> rejected("THREAD_DELETED", "Thread was deleted.")
            rejectBusy && ticket.threadId in active -> rejected("THREAD_BUSY", "The thread already has an active turn. Use steer or queue another turn.")
            canStart(ticket) -> null
            queued.count { it.threadId == ticket.threadId } >= perThreadLimit() -> rejected("SESSION_QUEUE_FULL", "Thread turn queue is full.")
            queued.size >= globalQueueLimit() -> rejected("RUNTIME_BUSY", "Runtime turn queue is full.")
            else -> null
        }
        if (error != null) ticket.result.complete(error)
        else if (canStart(ticket)) grant(ticket)
        else queued += ticket
    }

    fun release(ticket: ThreadTicket) = synchronized(coordination.lock) {
        queued.remove(ticket)
        if (active[ticket.threadId] === ticket) {
            active.remove(ticket.threadId)
            drains.remove(ticket.threadId)?.complete(Unit)
        }
        drain()
    }

    fun invalidate(threadId: String): CompletableDeferred<Unit> = synchronized(coordination.lock) {
        queued.filter { it.threadId == threadId }.forEach { rejectQueued(it, "THREAD_DELETED", "Thread was deleted.") }
        if (threadId !in active) CompletableDeferred(Unit)
        else drains.getOrPut(threadId) { CompletableDeferred() }
    }

    fun queuedCount(threadId: String? = null): Int = synchronized(coordination.lock) {
        if (threadId == null) queued.size else queued.count { it.threadId == threadId }
    }

    private fun rejectQueued(ticket: ThreadTicket, code: String, message: String) {
        queued.remove(ticket)
        ticket.result.complete(rejected(code, message))
    }

    private fun drain() {
        for (ticket in queued.toList()) {
            if (!canStart(ticket)) continue
            queued.remove(ticket)
            grant(ticket)
        }
    }

    private fun canStart(ticket: ThreadTicket): Boolean =
        ticket.threadId !in active && active.size < activeLimit() && !coordination.slot(ticket.threadId).deleting

    private fun grant(ticket: ThreadTicket) {
        active[ticket.threadId] = ticket
        ticket.result.complete(ThreadAdmissionResult.Granted(ticket))
    }

    private fun activeLimit(): Int = owners.values.minOfOrNull { it.maxActiveSessions } ?: 0
    private fun perThreadLimit(): Int = owners.values.minOf { it.maxQueuedRunsPerSession }
    private fun globalQueueLimit(): Int = owners.values.minOf { it.maxQueuedRunsGlobal }
    private fun rejected(code: String, message: String) = ThreadAdmissionResult.Rejected(code, message)
}

internal class ThreadTicket(val owner: DefaultThreadRuntime, val threadId: String, val generation: Long) {
    val result = CompletableDeferred<ThreadAdmissionResult>()
}

internal sealed interface ThreadAdmissionResult {
    data class Granted(val ticket: ThreadTicket) : ThreadAdmissionResult
    data class Rejected(val code: String, val message: String) : ThreadAdmissionResult
}

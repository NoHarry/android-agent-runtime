@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionEvent
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Coalesced wakeups plus per-stream cursors: a slow subscriber never fills the writer queue. */
internal class AsyncSessionEventDelivery(
    private val positions: () -> Map<EventStreamKey, Long>,
    private val initialPosition: (EventStreamKey) -> Long,
    private val page: (EventStreamKey, Long) -> List<SessionEvent>,
    initial: Map<EventStreamKey, Long>,
    private val listener: (SessionEvent) -> Unit,
) : AutoCloseable {
    private val cursors = initial.toMutableMap()
    private val dirty = AtomicBoolean(false)
    private val scheduled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val readGate = Any()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SessionEventLog-subscriber").apply { isDaemon = true }
    }

    fun signal() {
        dirty.set(true)
        if (closed.get() || !scheduled.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    while (!closed.get() && dirty.getAndSet(false)) deliver()
                } finally {
                    scheduled.set(false)
                    if (dirty.get() && !closed.get()) signal()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            scheduled.set(false)
        }
    }

    private fun deliver() {
        for ((key, target) in positions()) {
            var cursor = cursors[key] ?: initialPosition(key)
            while (!closed.get() && cursor < target) {
                val events = synchronized(readGate) {
                    if (closed.get()) emptyList() else runCatching { page(key, cursor) }.getOrDefault(emptyList())
                }
                if (events.isEmpty()) break
                for (event in events) {
                    if (closed.get()) return
                    if (event.sequence != cursor + 1) return
                    runCatching { listener(event) }
                    cursor = event.sequence
                    cursors[key] = cursor
                }
            }
        }
    }

    override fun close() {
        closed.set(true)
        // Wait for our disk reads, not arbitrary user code; no new read can start afterward.
        synchronized(readGate) { executor.shutdownNow() }
    }
}

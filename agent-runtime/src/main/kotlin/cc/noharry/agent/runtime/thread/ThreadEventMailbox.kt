package cc.noharry.agent.runtime.thread

import cc.noharry.agent.ThreadEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Acknowledged delivery bounds ordinary production by the collector's buffer. Cleanup may enqueue the remaining
 * item completions without awaiting that collector: it may itself be waiting for interrupt/delete to finish.
 */
internal class ThreadEventMailbox(private val output: ProducerScope<ThreadEvent>) {
    private val events = Channel<Delivery>(Channel.UNLIMITED)

    init {
        output.launch {
            for (delivery in events) {
                try { output.send(delivery.event) } finally { delivery.accepted.complete(Unit) }
            }
        }
    }

    suspend fun emit(event: ThreadEvent, awaitDelivery: Boolean = true) {
        if (!output.isActive) return
        val delivery = Delivery(event)
        if (events.trySend(delivery).isSuccess && awaitDelivery) delivery.accepted.await()
    }

    fun close() { events.close() }

    private class Delivery(val event: ThreadEvent) {
        val accepted = CompletableDeferred<Unit>()
    }
}

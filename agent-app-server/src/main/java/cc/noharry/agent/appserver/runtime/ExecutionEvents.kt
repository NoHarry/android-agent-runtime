package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.ThreadEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Per-run asynchronous projections, as a plugin follows another agent's turn
 * ([cc.noharry.agent.appserver.server.OutsideTurns.follow]). The Kernel SessionEventLog remains the canonical content ledger.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
sealed interface ExecutionMessage {
    data class Runtime(val event: ThreadEvent) : ExecutionMessage
    data class ToolStatus(val name: String, val callId: String, val phase: String) : ExecutionMessage
    data class Speech(val text: String, val final: Boolean) : ExecutionMessage
    data class Failure(val message: String) : ExecutionMessage
    data class ObserverIssue(val observer: String) : ExecutionMessage
    data object Stopped : ExecutionMessage
    data object Ended : ExecutionMessage
}

@cc.noharry.agent.appserver.AppServerPluginApi
class ExecutionSubscription(
    private val channel: Channel<ExecutionMessage>,
    private val unsubscribe: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val detached = AtomicBoolean()
    val events: ReceiveChannel<ExecutionMessage> get() = channel
    fun finish() {
        detach()
        channel.close()
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            detach()
            channel.cancel()
        }
    }

    private fun detach() {
        if (detached.compareAndSet(false, true)) unsubscribe()
    }
}

/** Shared process-wide fan-out; callbacks never execute on the model/tool producer thread. */
internal object ExecutionEvents {
    private data class Subscriber(val channel: Channel<ExecutionMessage>, val required: Boolean)
    private val lock = Any()
    private val topics = mutableMapOf<String, MutableList<Subscriber>>()

    fun subscribe(runId: String, required: Boolean = false, capacity: Int = DEFAULT_CAPACITY): ExecutionSubscription {
        val subscriber = Subscriber(Channel(capacity), required)
        synchronized(lock) { topics.getOrPut(runId) { mutableListOf() }.add(subscriber) }
        return ExecutionSubscription(subscriber.channel) {
            synchronized(lock) {
                topics[runId]?.let { list -> if (list.remove(subscriber) && list.isEmpty()) topics.remove(runId) }
            }
        }
    }

    fun publish(runId: String, message: ExecutionMessage) {
        synchronized(lock) {
            var requiredFailure: IllegalStateException? = null
            val subscribers = topics[runId] ?: return
            val iterator = subscribers.iterator()
            while (iterator.hasNext()) {
                val subscriber = iterator.next()
                if (subscriber.channel.trySend(message).isSuccess) continue
                val failure = IllegalStateException("Execution subscriber overflow or closed: $runId")
                subscriber.channel.close(failure)
                iterator.remove()
                if (subscriber.required) requiredFailure = failure
            }
            if (subscribers.isEmpty()) topics.remove(runId)
            requiredFailure?.let { throw it }
        }
    }

    internal fun closeRun(runId: String) {
        synchronized(lock) { topics.remove(runId)?.forEach { it.channel.close() } }
    }

    private const val DEFAULT_CAPACITY = 512
}

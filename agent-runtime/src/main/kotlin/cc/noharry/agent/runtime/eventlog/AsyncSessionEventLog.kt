@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Single canonical acceptance queue; disk and subscriber work never run on its producer. */
internal class AsyncSessionEventLog(
    private val durable: FileBackedSessionEventLog,
    private val now: () -> Long = System::currentTimeMillis,
    private val persistBatch: (List<SessionEvent>) -> Unit = durable::appendAllocatedBatch,
) : SessionEventLog, AutoCloseable {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val streams = linkedMapOf<EventStreamKey, AsyncStreamState>()
    private val pending = linkedMapOf<String, QueuedSessionEvent>()
    private val queue = ArrayDeque<QueuedSessionEvent>()
    private val deliveries = mutableSetOf<AsyncSessionEventDelivery>()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SessionEventLog-writer").apply { isDaemon = true }
    }
    private var writer: Future<*>? = null
    private var pendingBytes = 0L
    private var closing = false
    private var failure: String? = null
    private val openedStreams = mutableSetOf<EventStreamKey>()

    override fun append(event: SessionEventAppend): SessionEvent {
        validateAppendIdentity(event)
        validatePayload(event.payload)
        val bytes = encodePayload(event.payload).toString().length.toLong() * CHAR_BYTES + ENVELOPE_BYTES
        val stored = lock.withLock {
            awaitCapacity(bytes)
            val key = EventStreamKey(event.sessionId, event.runId)
            val state = streams.getOrPut(key) {
                val last = durable.replay(key.sessionId, key.runId).lastOrNull()?.sequence ?: 0L
                AsyncStreamState(last, last)
            }
            val sequence = state.accepted + 1
            require(event.sourceEventSequences.all { it in FIRST_EVENT_SEQUENCE until sequence }) {
                "Causal source must precede the accepted event"
            }
            val accepted = event.toStoredEvent(UUID.randomUUID().toString(), sequence, now())
            val queued = QueuedSessionEvent(accepted, bytes)
            pending[accepted.eventId] = queued
            queue.addLast(queued)
            pendingBytes += bytes
            state.accepted = sequence
            if (writer == null) writer = worker.submit(::writeLoop)
            changed.signalAll()
            accepted
        }
        signalDeliveries()
        return stored
    }

    private fun awaitCapacity(bytes: Long) {
        var remaining = TimeUnit.SECONDS.toNanos(FLUSH_TIMEOUT_SECONDS)
        while (!closing && failure == null && pending.isNotEmpty() &&
            (pending.size >= MAX_PENDING_EVENTS || pendingBytes + bytes > MAX_PENDING_BYTES)) {
            if (remaining <= 0) {
                failure = "ACCEPTANCE_QUEUE_FULL"
                changed.signalAll()
                error("EventLog acceptance queue is full")
            }
            remaining = changed.awaitNanos(remaining)
        }
        check(!closing) { "EventLog writer is closed" }
        check(failure == null) { "EventLog persistence failed: $failure" }
    }

    private fun writeLoop() {
        while (true) {
            val ready = lock.withLock {
                while (queue.isEmpty() && !closing) changed.await()
                queue.isNotEmpty()
            }
            if (!ready) {
                openedStreams.forEach(durable::endAsyncStream)
                return
            }
            Thread.sleep(BATCH_DELAY_MS)
            val batch = lock.withLock { takeBatch() }
            try {
                batch.map { it.event.key() }.distinct().forEach { key ->
                    if (openedStreams.add(key)) durable.beginAsyncStream(key)
                }
                persistBatch(batch.map { it.event })
                batch.filter { it.event.type == SessionEventType.RUN_FINISHED }.forEach {
                    durable.endAsyncStream(it.event.key())
                    openedStreams.remove(it.event.key())
                }
                lock.withLock {
                    batch.forEach { item ->
                        pending.remove(item.event.eventId)
                        pendingBytes -= item.bytes
                        streams.getValue(item.event.key()).persisted = item.event.sequence
                    }
                    changed.signalAll()
                }
            } catch (error: Exception) {
                lock.withLock {
                    failure = safeFailureCode(error)
                    changed.signalAll()
                }
                signalDeliveries()
                return
            }
            signalDeliveries()
        }
    }

    private fun takeBatch(): List<QueuedSessionEvent> {
        val batch = mutableListOf<QueuedSessionEvent>()
        var bytes = 0L
        while (queue.isNotEmpty() && batch.size < MAX_BATCH_EVENTS) {
            val next = queue.first()
            if (batch.isNotEmpty() && bytes + next.bytes > MAX_BATCH_BYTES) break
            batch += queue.removeFirst()
            bytes += next.bytes
        }
        return batch
    }

    /** Waits only for storage, never for UI callbacks. Failure stays visible in health. */
    fun flush(): Boolean = lock.withLock {
        var remaining = TimeUnit.SECONDS.toNanos(FLUSH_TIMEOUT_SECONDS)
        while (pending.isNotEmpty() && failure == null) {
            check(remaining > 0) { "EventLog writer did not finish flushing" }
            remaining = changed.awaitNanos(remaining)
        }
        failure == null
    }

    override fun replay(sessionId: String, runId: String): List<SessionEvent> {
        flush()
        return durable.replay(sessionId, runId)
    }

    override fun query(query: SessionEventQuery): List<SessionEvent> {
        flush()
        return durable.query(query)
    }

    override fun durability() = SessionEventLogDurability.DURABLE

    override fun health(sessionId: String, runId: String): SessionEventLogHealth {
        val live = lock.withLock {
            streams[EventStreamKey(sessionId, runId)]?.let { state ->
                if (state.accepted == state.persisted && failure == null) return@let null
                SessionEventLogHealth(
                    status = SessionEventLogHealthStatus.DEGRADED,
                    attemptedCount = state.accepted.toInt(), appendedCount = state.persisted.toInt(),
                    failedCount = if (failure == null) 0 else (state.accepted - state.persisted).toInt(),
                    firstFailureCode = failure, lastAcceptedSequence = state.accepted,
                    lastPersistedSequence = state.persisted.takeIf { it > 0 },
                    backendDurability = durability(),
                )
            }
        }
        if (live != null) return live
        val offline = durable.health(sessionId, runId)
        val ownFlushed = lock.withLock {
            streams[EventStreamKey(sessionId, runId)]?.let { it.accepted == it.persisted && failure == null } == true
        }
        return if (ownFlushed && offline.firstFailureCode == ASYNC_WRITER_OPEN) {
            offline.copy(status = SessionEventLogHealthStatus.COMPLETE, firstFailureCode = null)
        } else offline
    }

    override fun subscribe(listener: (SessionEvent) -> Unit): AutoCloseable {
        val delivery = lock.withLock {
            check(!closing) { "EventLog writer is closed" }
            AsyncSessionEventDelivery(::positions, ::initialPosition, ::deliveryPage, positions(), listener)
                .also { deliveries += it }
        }
        return AutoCloseable {
            lock.withLock { deliveries -= delivery }
            delivery.close()
        }
    }

    override fun putAttachment(sessionId: String, ref: SessionAttachmentRef, bytes: ByteArray): Boolean =
        durable.putAttachment(sessionId, ref, bytes)

    override fun readAttachment(sessionId: String, sha256: String): ByteArray? = durable.readAttachment(sessionId, sha256)

    private fun positions(): Map<EventStreamKey, Long> = lock.withLock { streams.mapValues { it.value.accepted } }

    private fun initialPosition(key: EventStreamKey): Long = lock.withLock { streams[key]?.initial ?: 0L }

    private fun deliveryPage(key: EventStreamKey, after: Long): List<SessionEvent> {
        val tail = lock.withLock {
            pending.values.asSequence().map { it.event }.filter { it.key() == key && it.sequence > after }
                .take(DELIVERY_PAGE_SIZE).toList()
        }
        if (tail.firstOrNull()?.sequence == after + 1) return tail
        // Disk read releases its lock before user callbacks. Slow UI catches up from the log.
        return durable.query(SessionEventQuery(
            sessionId = key.sessionId, runId = key.runId, afterSequenceExclusive = after, limit = DELIVERY_PAGE_SIZE,
        ))
    }

    private fun signalDeliveries() = lock.withLock { deliveries.toList() }.forEach { it.signal() }

    override fun close() {
        val subscribers = lock.withLock {
            closing = true
            changed.signalAll()
            deliveries.toList().also { deliveries.clear() }
        }
        subscribers.forEach { it.close() }
        // Do not return while a disk worker can still touch a root the host might delete.
        // The host's bounded cancel/join barrier refuses deletion if this writer is stuck.
        writer?.get()
        worker.shutdown()
    }
}

private data class AsyncStreamState(var accepted: Long, var persisted: Long, val initial: Long = accepted)
private data class QueuedSessionEvent(val event: SessionEvent, val bytes: Long)
private fun SessionEvent.key() = EventStreamKey(sessionId, runId)
private const val MAX_PENDING_EVENTS = 128
private const val MAX_PENDING_BYTES = 8L * 1024 * 1024
private const val MAX_BATCH_EVENTS = 32
private const val MAX_BATCH_BYTES = 128L * 1024
private const val BATCH_DELAY_MS = 20L
private const val FLUSH_TIMEOUT_SECONDS = 30L
private const val DELIVERY_PAGE_SIZE = 64
private const val CHAR_BYTES = 2L
private const val ENVELOPE_BYTES = 512L

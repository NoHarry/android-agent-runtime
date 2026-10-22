package cc.noharry.agent.runtime.probe

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded queue → probes.jsonl writer (SPEC-RPB-002).
 * Distinguishes enqueued vs written; never throws to bus callers.
 */
internal interface ProbeEventSink {
    fun tryEnqueue(event: ProbeEvent): Boolean
    suspend fun drain(timeoutMillis: Long): DrainResult
    fun stats(): EventSinkStats
}

internal class FileProbeEventSink(
    private val eventFile: File,
    private val capacity: Int = DEFAULT_PROBE_QUEUE_CAPACITY,
    private val forceIoFailure: AtomicBoolean = AtomicBoolean(false),
) : ProbeEventSink {
    private val queue = ArrayDeque<ProbeEvent>(capacity.coerceAtLeast(1))
    private val mutex = Mutex()
    private val enqueued = AtomicLong(0L)
    private val written = AtomicLong(0L)
    private val dropped = AtomicLong(0L)
    private val ioFailed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override fun tryEnqueue(event: ProbeEvent): Boolean {
        if (closed.get()) {
            dropped.incrementAndGet()
            return false
        }
        synchronized(queue) {
            if (queue.size >= capacity) {
                dropped.incrementAndGet()
                return false
            }
            queue.addLast(event)
            enqueued.incrementAndGet()
            return true
        }
    }

    override suspend fun drain(timeoutMillis: Long): DrainResult {
        val startedWritten = written.get()
        val timedOut = withTimeoutOrNull(timeoutMillis.coerceAtLeast(0L)) {
            mutex.withLock {
                flushQueueUnlocked()
            }
            true
        } == null

        if (!timedOut) {
            // already flushed inside timeout
        } else {
            // Best-effort partial flush after timeout window without unbounded wait.
            mutex.withLock {
                flushQueueUnlocked(maxEvents = 0)
            }
        }

        val queueEmpty = synchronized(queue) { queue.isEmpty() }
        // If timed out, force remaining accounting: timedOut true even if we drained partially.
        val stillPending = !queueEmpty
        return DrainResult(
            queueEmpty = queueEmpty,
            timedOut = timedOut || stillPending && timeoutMillis == 0L,
            ioFailed = ioFailed.get(),
            writtenDelta = written.get() - startedWritten,
        )
    }

    /**
     * Drain that honors a simulated slow writer for tests (blocks until timeout).
     */
    suspend fun drainWithSlowWrite(
        timeoutMillis: Long,
        delayPerEventMillis: Long,
    ): DrainResult {
        val startedWritten = written.get()
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0L) * 1_000_000L
        var timedOut = false
        mutex.withLock {
            while (true) {
                val next = synchronized(queue) {
                    if (queue.isEmpty()) null else queue.peekFirst()
                } ?: break
                if (System.nanoTime() >= deadline) {
                    timedOut = true
                    break
                }
                if (delayPerEventMillis > 0L) {
                    kotlinx.coroutines.delay(delayPerEventMillis)
                }
                if (System.nanoTime() >= deadline) {
                    timedOut = true
                    break
                }
                val writtenOk = writeOne(next)
                if (writtenOk) {
                    synchronized(queue) {
                        if (queue.peekFirst() === next) queue.removeFirst()
                    }
                } else {
                    break
                }
            }
        }
        val queueEmpty = synchronized(queue) { queue.isEmpty() }
        return DrainResult(
            queueEmpty = queueEmpty,
            timedOut = timedOut || !queueEmpty,
            ioFailed = ioFailed.get(),
            writtenDelta = written.get() - startedWritten,
        )
    }

    override fun stats(): EventSinkStats =
        EventSinkStats(
            enqueued = enqueued.get(),
            written = written.get(),
            dropped = dropped.get(),
            ioFailed = ioFailed.get(),
        )

    fun closeForEnqueue() {
        closed.set(true)
    }

    private fun flushQueueUnlocked(maxEvents: Int = Int.MAX_VALUE) {
        var count = 0
        while (count < maxEvents) {
            val next = synchronized(queue) {
                if (queue.isEmpty()) null else queue.peekFirst()
            } ?: break
            if (!writeOne(next)) break
            synchronized(queue) {
                if (queue.peekFirst() === next) queue.removeFirst()
            }
            count++
        }
    }

    private fun writeOne(event: ProbeEvent): Boolean {
        if (forceIoFailure.get()) {
            ioFailed.set(true)
            return false
        }
        return try {
            eventFile.parentFile?.mkdirs()
            eventFile.appendText(encodeProbeEventJsonLine(event) + "\n")
            written.incrementAndGet()
            true
        } catch (_: Exception) {
            ioFailed.set(true)
            false
        }
    }
}

/** In-memory sink for pure unit tests without disk. */
internal class InMemoryProbeEventSink(
    private val capacity: Int = DEFAULT_PROBE_QUEUE_CAPACITY,
    private val failWriteAfter: Int = Int.MAX_VALUE,
) : ProbeEventSink {
    private val queue = ArrayDeque<ProbeEvent>(capacity.coerceAtLeast(1))
    private val writtenEvents = mutableListOf<ProbeEvent>()
    private val enqueued = AtomicLong(0L)
    private val written = AtomicLong(0L)
    private val dropped = AtomicLong(0L)
    private val ioFailed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var writeAttempts = 0

    val lines: List<ProbeEvent> get() = synchronized(writtenEvents) { writtenEvents.toList() }

    override fun tryEnqueue(event: ProbeEvent): Boolean {
        if (closed.get()) {
            dropped.incrementAndGet()
            return false
        }
        synchronized(queue) {
            if (queue.size >= capacity) {
                dropped.incrementAndGet()
                return false
            }
            queue.addLast(event)
            enqueued.incrementAndGet()
            return true
        }
    }

    override suspend fun drain(timeoutMillis: Long): DrainResult {
        val startedWritten = written.get()
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0L) * 1_000_000L
        var timedOut = false
        while (true) {
            if (timeoutMillis == 0L && synchronized(queue) { queue.isNotEmpty() }) {
                // Zero timeout: do not process pending work.
                timedOut = synchronized(queue) { queue.isNotEmpty() }
                break
            }
            val next = synchronized(queue) {
                if (queue.isEmpty()) null else queue.peekFirst()
            } ?: break
            if (timeoutMillis > 0L && System.nanoTime() >= deadline) {
                timedOut = true
                break
            }
            writeAttempts++
            if (writeAttempts > failWriteAfter) {
                ioFailed.set(true)
                break
            }
            synchronized(writtenEvents) { writtenEvents.add(next) }
            written.incrementAndGet()
            synchronized(queue) {
                if (queue.peekFirst() === next) queue.removeFirst()
            }
        }
        val queueEmpty = synchronized(queue) { queue.isEmpty() }
        return DrainResult(
            queueEmpty = queueEmpty,
            timedOut = timedOut || !queueEmpty,
            ioFailed = ioFailed.get(),
            writtenDelta = written.get() - startedWritten,
        )
    }

    /**
     * Drain that only writes [maxWrites] events then reports timeout with remainder pending.
     * Used to prove written < enqueued under incomplete drain.
     */
    suspend fun drainPartial(maxWrites: Int): DrainResult {
        val startedWritten = written.get()
        var count = 0
        while (count < maxWrites) {
            val next = synchronized(queue) {
                if (queue.isEmpty()) null else queue.removeFirst()
            } ?: break
            writeAttempts++
            if (writeAttempts > failWriteAfter) {
                ioFailed.set(true)
                break
            }
            synchronized(writtenEvents) { writtenEvents.add(next) }
            written.incrementAndGet()
            count++
        }
        val queueEmpty = synchronized(queue) { queue.isEmpty() }
        return DrainResult(
            queueEmpty = queueEmpty,
            timedOut = !queueEmpty,
            ioFailed = ioFailed.get(),
            writtenDelta = written.get() - startedWritten,
        )
    }

    override fun stats(): EventSinkStats =
        EventSinkStats(
            enqueued = enqueued.get(),
            written = written.get(),
            dropped = dropped.get(),
            ioFailed = ioFailed.get(),
        )

    fun closeForEnqueue() {
        closed.set(true)
    }
}

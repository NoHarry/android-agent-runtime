package cc.noharry.agent.runtime.probe

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Independent durable completeness side-car (SPEC-RPB-002 / B2).
 * Must survive event sink IO failure; writes via temp + rename.
 */
internal interface CompletenessStore {
    fun recordEnqueueOutcome(enqueued: Boolean)
    fun mergeDrain(result: DrainResult)
    fun mergeFactory(truncated: Long, error: Long, sequenceMax: Long)
    fun markDropped(count: Long = 1L)
    fun markError(count: Long = 1L)
    suspend fun writeFinal(runId: String, probesDisabled: Boolean = false): ProbeCompleteness
    fun snapshot(runId: String, probesDisabled: Boolean = false): ProbeCompleteness
}

internal class InMemoryCompletenessStore : CompletenessStore {
    private val enqueued = AtomicLong(0L)
    private val written = AtomicLong(0L)
    private val dropped = AtomicLong(0L)
    private val truncated = AtomicLong(0L)
    private val error = AtomicLong(0L)
    private val sequenceMax = AtomicLong(-1L)
    private val drainTimedOut = AtomicBoolean(false)
    private val sinkIoFailed = AtomicBoolean(false)
    private val completenessIoFailed = AtomicBoolean(false)

    override fun recordEnqueueOutcome(enqueued: Boolean) {
        if (enqueued) {
            this.enqueued.incrementAndGet()
        } else {
            dropped.incrementAndGet()
        }
    }

    override fun mergeDrain(result: DrainResult) {
        written.addAndGet(result.writtenDelta)
        if (result.timedOut) drainTimedOut.set(true)
        if (result.ioFailed) sinkIoFailed.set(true)
    }

    override fun mergeFactory(truncated: Long, error: Long, sequenceMax: Long) {
        // Factory counters are absolute run totals; take max so bus-side markError
        // increments are not wiped when factory error is still zero (or vice versa).
        this.truncated.updateAndGet { current -> maxOf(current, truncated) }
        this.error.updateAndGet { current -> maxOf(current, error) }
        this.sequenceMax.updateAndGet { current -> maxOf(current, sequenceMax) }
    }

    override fun markDropped(count: Long) {
        dropped.addAndGet(count)
    }

    override fun markError(count: Long) {
        error.addAndGet(count)
    }

    override suspend fun writeFinal(runId: String, probesDisabled: Boolean): ProbeCompleteness =
        snapshot(runId, probesDisabled)

    override fun snapshot(runId: String, probesDisabled: Boolean): ProbeCompleteness {
        val complete = if (probesDisabled) {
            true
        } else {
            isProbeComplete(
                dropped = dropped.get(),
                error = error.get(),
                truncated = truncated.get(),
                drainTimedOut = drainTimedOut.get(),
                sinkIoFailed = sinkIoFailed.get(),
                completenessIoFailed = completenessIoFailed.get(),
                written = written.get(),
                enqueued = enqueued.get(),
                sequenceMax = sequenceMax.get(),
            )
        }
        return ProbeCompleteness(
            runId = runId,
            sequenceMax = sequenceMax.get(),
            enqueued = enqueued.get(),
            written = written.get(),
            dropped = dropped.get(),
            truncated = truncated.get(),
            error = error.get(),
            drainTimedOut = drainTimedOut.get(),
            sinkIoFailed = sinkIoFailed.get(),
            completenessIoFailed = completenessIoFailed.get(),
            complete = complete,
            probesDisabled = probesDisabled,
        )
    }

    fun forceCompletenessIoFailed() {
        completenessIoFailed.set(true)
    }
}

internal class FileCompletenessStore(
    private val completenessFile: File,
    private val forceIoFailure: AtomicBoolean = AtomicBoolean(false),
) : CompletenessStore {
    private val memory = InMemoryCompletenessStore()

    override fun recordEnqueueOutcome(enqueued: Boolean) = memory.recordEnqueueOutcome(enqueued)
    override fun mergeDrain(result: DrainResult) = memory.mergeDrain(result)
    override fun mergeFactory(truncated: Long, error: Long, sequenceMax: Long) =
        memory.mergeFactory(truncated, error, sequenceMax)

    override fun markDropped(count: Long) = memory.markDropped(count)
    override fun markError(count: Long) = memory.markError(count)

    override fun snapshot(runId: String, probesDisabled: Boolean): ProbeCompleteness =
        memory.snapshot(runId, probesDisabled)

    override suspend fun writeFinal(runId: String, probesDisabled: Boolean): ProbeCompleteness {
        val base = memory.snapshot(runId, probesDisabled)
        if (forceIoFailure.get()) {
            memory.forceCompletenessIoFailed()
            return memory.snapshot(runId, probesDisabled).copy(
                completenessIoFailed = true,
                complete = false,
            )
        }
        return try {
            val json = encodeProbeCompletenessJson(base)
            completenessFile.parentFile?.mkdirs()
            val temp = File(
                completenessFile.parentFile,
                "${completenessFile.name}.tmp-${System.nanoTime()}",
            )
            temp.writeText(json)
            Files.move(
                temp.toPath(),
                completenessFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            base
        } catch (_: Exception) {
            // ATOMIC_MOVE may be unsupported on some filesystems; retry non-atomic.
            try {
                val json = encodeProbeCompletenessJson(base)
                completenessFile.parentFile?.mkdirs()
                val temp = File(
                    completenessFile.parentFile,
                    "${completenessFile.name}.tmp-${System.nanoTime()}",
                )
                temp.writeText(json)
                Files.move(
                    temp.toPath(),
                    completenessFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                base
            } catch (_: Exception) {
                memory.forceCompletenessIoFailed()
                memory.snapshot(runId, probesDisabled).copy(
                    completenessIoFailed = true,
                    complete = false,
                )
            }
        }
    }
}

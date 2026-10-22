package cc.noharry.agent.runtime.probe

/**
 * Internal observe-only probe envelope (SPEC-RPB-001).
 * Not part of public API; Phase 0 does not freeze this surface.
 */
internal const val PROBE_ENVELOPE_SCHEMA_VERSION: Int = 1
internal const val PROBE_ATTRS_MAX_BYTES: Int = 8 * 1024
internal const val DEFAULT_PROBE_QUEUE_CAPACITY: Int = 2048
internal const val DEFAULT_PROBE_DRAIN_TIMEOUT_MILLIS: Long = 300L
internal const val PROBE_COMPLETENESS_SCHEMA_VERSION: Int = 1

internal data class ProbeEvent(
    val schemaVersion: Int = PROBE_ENVELOPE_SCHEMA_VERSION,
    val runId: String,
    val sessionId: String? = null,
    val requestId: String? = null,
    val sequence: Long,
    val stageId: String,
    val probeId: String,
    val epochMillis: Long? = null,
    val elapsedNanos: Long,
    val attrs: Map<String, ProbeAttrValue> = emptyMap(),
    val truncated: Boolean = false,
)

internal sealed class ProbeAttrValue {
    data class Str(val value: String) : ProbeAttrValue()
    data class Num(val value: Double) : ProbeAttrValue()
    data class Bool(val value: Boolean) : ProbeAttrValue()
    data class Obj(val value: Map<String, ProbeAttrValue>) : ProbeAttrValue()
    data class Arr(val value: List<ProbeAttrValue>) : ProbeAttrValue()
}

internal interface ProbeClock {
    fun elapsedNanos(): Long
    fun epochMillis(): Long
}

internal class SystemProbeClock(
    private val anchorNanos: Long = System.nanoTime(),
) : ProbeClock {
    override fun elapsedNanos(): Long = (System.nanoTime() - anchorNanos).coerceAtLeast(0L)
    override fun epochMillis(): Long = System.currentTimeMillis()
}

internal data class ProbePack(
    val packId: String,
    val producers: List<String>,
)

internal data class ProbeCompleteness(
    val schemaVersion: Int = PROBE_COMPLETENESS_SCHEMA_VERSION,
    val runId: String,
    val sequenceMax: Long,
    val enqueued: Long,
    val written: Long,
    val dropped: Long,
    val truncated: Long,
    val error: Long,
    val drainTimedOut: Boolean,
    val sinkIoFailed: Boolean,
    val completenessIoFailed: Boolean,
    val complete: Boolean,
    /** When probes are intentionally off: idle path is complete without hiding losses. */
    val probesDisabled: Boolean = false,
)

internal data class DrainResult(
    val queueEmpty: Boolean,
    val timedOut: Boolean,
    val ioFailed: Boolean,
    val writtenDelta: Long,
)

internal data class EventSinkStats(
    val enqueued: Long,
    val written: Long,
    val dropped: Long,
    val ioFailed: Boolean,
)

internal interface ProbeFactoryCounters {
    fun sequenceMax(): Long
    fun truncatedCount(): Long
    fun errorCount(): Long
}

/**
 * Frozen completeness predicate (detail-design §2).
 * Empty idle/disabled runs may still report complete=true with probesDisabled.
 */
internal fun isProbeComplete(
    dropped: Long,
    error: Long,
    truncated: Long,
    drainTimedOut: Boolean,
    sinkIoFailed: Boolean,
    completenessIoFailed: Boolean,
    written: Long,
    enqueued: Long,
    sequenceMax: Long,
): Boolean =
    dropped == 0L &&
        error == 0L &&
        truncated == 0L &&
        !drainTimedOut &&
        !sinkIoFailed &&
        !completenessIoFailed &&
        written == enqueued &&
        (enqueued == 0L || sequenceMax >= 0L)

internal fun uniqueProducers(packs: List<ProbePack>): Set<String> =
    packs.flatMap { it.producers }.toSet()

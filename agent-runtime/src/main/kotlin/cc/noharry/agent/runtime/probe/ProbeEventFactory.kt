package cc.noharry.agent.runtime.probe

import java.util.concurrent.atomic.AtomicLong

/**
 * Run-scoped atomic sequence + attrs budget/redaction (SPEC-RPB-001).
 * Concurrent next() must not produce duplicate sequences.
 */
internal class ProbeEventFactory(
    private val runId: String,
    private val sessionId: String?,
    private val requestId: String?,
    private val clock: ProbeClock,
    private val catalog: ProbeStageCatalog,
    private val attrsMaxBytes: Int = PROBE_ATTRS_MAX_BYTES,
) : ProbeFactoryCounters {
    private val sequenceCounter = AtomicLong(0L)
    private val truncatedCounter = AtomicLong(0L)
    private val errorCounter = AtomicLong(0L)
    /** Highest sequence allocated this run; -1 when none. */
    private val sequenceMaxHolder = AtomicLong(-1L)

    override fun sequenceMax(): Long = sequenceMaxHolder.get()
    override fun truncatedCount(): Long = truncatedCounter.get()
    override fun errorCount(): Long = errorCounter.get()

    fun isKnownStage(stageId: String): Boolean = catalog.isKnown(stageId)

    fun next(stageId: String, probeId: String, attrs: Map<String, Any?> = emptyMap()): ProbeEvent {
        require(runId.isNotBlank()) { "runId must be non-blank" }
        val sequence = sequenceCounter.getAndIncrement()
        sequenceMaxHolder.updateAndGet { current -> maxOf(current, sequence) }

        val known = catalog.isKnown(stageId)
        val coerced = coerceAndFilterAttrs(stageId, attrs)
        val (budgeted, truncated) = applyAttrsBudget(stageId, coerced)
        if (truncated) {
            truncatedCounter.incrementAndGet()
        }
        if (!known) {
            errorCounter.incrementAndGet()
        }

        return ProbeEvent(
            runId = runId,
            sessionId = sessionId,
            requestId = requestId,
            sequence = sequence,
            stageId = stageId,
            probeId = probeId,
            epochMillis = clock.epochMillis(),
            elapsedNanos = clock.elapsedNanos(),
            attrs = budgeted,
            truncated = truncated || !known,
        )
    }

    /**
     * Records an exception on the factory path without throwing to callers.
     */
    fun recordError(): Long = errorCounter.incrementAndGet()

    private fun coerceAndFilterAttrs(
        stageId: String,
        attrs: Map<String, Any?>,
    ): Map<String, ProbeAttrValue> {
        val spec = catalog.spec(stageId)
        val out = linkedMapOf<String, ProbeAttrValue>()
        attrs.forEach { (key, raw) ->
            if (spec != null && spec.allowedAttrKeys.isNotEmpty() && key !in spec.allowedAttrKeys) {
                return@forEach
            }
            coerceAttrValue(raw)?.let { out[key] = it }
        }
        return out
    }

    private fun applyAttrsBudget(
        stageId: String,
        attrs: Map<String, ProbeAttrValue>,
    ): Pair<Map<String, ProbeAttrValue>, Boolean> {
        if (attrs.isEmpty()) return attrs to false
        if (estimateAttrsUtf8Bytes(attrs) <= attrsMaxBytes) return attrs to false

        val priorities = catalog.spec(stageId)?.attrPriority.orEmpty()
        val orderedKeys = attrs.keys.sortedBy { priorities[it] ?: 0 }
        val mutable = attrs.toMutableMap()
        var truncated = false
        for (key in orderedKeys) {
            if (estimateAttrsUtf8Bytes(mutable) <= attrsMaxBytes) break
            mutable.remove(key)
            truncated = true
        }
        if (estimateAttrsUtf8Bytes(mutable) > attrsMaxBytes) {
            mutable.clear()
            truncated = true
        }
        return mutable to truncated
    }
}

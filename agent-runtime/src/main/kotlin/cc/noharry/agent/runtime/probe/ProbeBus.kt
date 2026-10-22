package cc.noharry.agent.runtime.probe

/**
 * Probe bus: producer allowlist + enqueue only (SPEC-RPB-002 / B1).
 * No fan-out transform; accept never throws to control path.
 */
internal interface ProbeBus {
    /** Returns false if producer disabled or enqueue dropped. Never throws to callers. */
    fun accept(event: ProbeEvent): Boolean

    /**
     * Primary drain → optional post-drain hook (e.g. emit probe.completeness) →
     * secondary drain for those events → independent completeness side-car writeFinal.
     * Side-car must not depend on event sink health (B2).
     */
    suspend fun onRunFinished(
        drainTimeoutMillis: Long = DEFAULT_PROBE_DRAIN_TIMEOUT_MILLIS,
        afterPrimaryDrain: ((ProbeCompleteness) -> Unit)? = null,
    ): ProbeCompleteness
}

/** Intentional idle path when no packs are enabled. */
internal class NoopProbeBus(
    private val runId: String,
    private val completenessStore: CompletenessStore = InMemoryCompletenessStore(),
) : ProbeBus {
    override fun accept(event: ProbeEvent): Boolean = false

    override suspend fun onRunFinished(
        drainTimeoutMillis: Long,
        afterPrimaryDrain: ((ProbeCompleteness) -> Unit)?,
    ): ProbeCompleteness =
        completenessStore.writeFinal(runId = runId, probesDisabled = true)
}

internal class DefaultProbeBus(
    private val runId: String,
    private val enabledProducers: Set<String>,
    private val eventSink: ProbeEventSink,
    private val completenessStore: CompletenessStore,
    private val factoryCounters: ProbeFactoryCounters,
) : ProbeBus {
    override fun accept(event: ProbeEvent): Boolean {
        if (event.probeId !in enabledProducers) {
            return false
        }
        return try {
            val enqueued = eventSink.tryEnqueue(event)
            completenessStore.recordEnqueueOutcome(enqueued)
            enqueued
        } catch (_: Exception) {
            completenessStore.markError()
            false
        }
    }

    override suspend fun onRunFinished(
        drainTimeoutMillis: Long,
        afterPrimaryDrain: ((ProbeCompleteness) -> Unit)?,
    ): ProbeCompleteness {
        mergePrimaryDrain(drainTimeoutMillis)
        // Snapshot after primary drain is the truth for probe.completeness attrs
        // (enqueued/written before the redundancy event itself).
        val primarySnap = completenessStore.snapshot(runId = runId, probesDisabled = false)
        if (afterPrimaryDrain != null) {
            try {
                afterPrimaryDrain(primarySnap)
            } catch (_: Exception) {
                completenessStore.markError()
            }
            // Flush probe.error / probe.completeness events enqueued by the hook.
            mergeSecondaryDrain(drainTimeoutMillis)
        }
        return completenessStore.writeFinal(runId = runId, probesDisabled = false)
    }

    private suspend fun mergePrimaryDrain(drainTimeoutMillis: Long) {
        val drain = safeDrain(drainTimeoutMillis)
        completenessStore.mergeDrain(drain)
        completenessStore.mergeFactory(
            truncated = factoryCounters.truncatedCount(),
            error = factoryCounters.errorCount(),
            sequenceMax = factoryCounters.sequenceMax(),
        )
    }

    private suspend fun mergeSecondaryDrain(drainTimeoutMillis: Long) {
        val drain = safeDrain(drainTimeoutMillis)
        completenessStore.mergeDrain(drain)
        completenessStore.mergeFactory(
            truncated = factoryCounters.truncatedCount(),
            error = factoryCounters.errorCount(),
            sequenceMax = factoryCounters.sequenceMax(),
        )
    }

    private suspend fun safeDrain(drainTimeoutMillis: Long): DrainResult =
        try {
            eventSink.drain(drainTimeoutMillis)
        } catch (_: Exception) {
            completenessStore.markError()
            DrainResult(queueEmpty = false, timedOut = true, ioFailed = true, writtenDelta = 0L)
        }

    companion object {
        fun fromPacks(
            runId: String,
            packs: List<ProbePack>,
            eventSink: ProbeEventSink,
            completenessStore: CompletenessStore,
            factoryCounters: ProbeFactoryCounters,
        ): ProbeBus {
            if (packs.isEmpty()) {
                return NoopProbeBus(runId = runId, completenessStore = completenessStore)
            }
            return DefaultProbeBus(
                runId = runId,
                enabledProducers = uniqueProducers(packs),
                eventSink = eventSink,
                completenessStore = completenessStore,
                factoryCounters = factoryCounters,
            )
        }
    }
}

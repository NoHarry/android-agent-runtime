package cc.noharry.agent.kernel.core

data class RuntimeStateRetentionPolicy(
    val maxTerminalRuns: Int = DEFAULT_MAX_TERMINAL_RUNS,
    val maxTraceEntriesPerRun: Int = DEFAULT_MAX_TRACE_ENTRIES_PER_RUN,
    val maxEvidenceEntriesPerRun: Int = DEFAULT_MAX_EVIDENCE_ENTRIES_PER_RUN,
) {
    init {
        require(maxTerminalRuns > 0) { "maxTerminalRuns must be positive." }
        require(maxTraceEntriesPerRun > 0) { "maxTraceEntriesPerRun must be positive." }
        require(maxEvidenceEntriesPerRun > 0) { "maxEvidenceEntriesPerRun must be positive." }
    }
}

internal const val DEFAULT_MAX_TERMINAL_RUNS = 64
internal const val DEFAULT_MAX_TRACE_ENTRIES_PER_RUN = 256
internal const val DEFAULT_MAX_EVIDENCE_ENTRIES_PER_RUN = 128

class BoundedStringSet(
    private val maxSize: Int,
) {
    private val values = linkedSetOf<String>()

    fun add(runId: String) {
        synchronized(values) {
            values.remove(runId)
            values.add(runId)
            while (values.size > maxSize) {
                values.remove(values.first())
            }
        }
    }

    fun remove(runId: String) {
        synchronized(values) { values.remove(runId) }
    }

    operator fun contains(runId: String): Boolean =
        synchronized(values) { runId in values }
}

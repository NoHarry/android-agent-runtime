package cc.noharry.agent.runtime.probe

/** Stable producer ids bound by ProbePacks (SPEC-RPB-001/002). */
internal object ProbeProducerIds {
    const val LIFECYCLE_RUN = "lifecycle.run"
    const val REQUEST_CONFIG = "request.config"
    const val MODEL_ROUND = "model.round"
    const val TOOL_EXEC = "tool.exec"
    const val SEARCH_NETWORK = "search.network"
    const val TERMINAL = "terminal.empty"
    const val CASE_COMPLETENESS = "case.completeness"
}

/** Default bench_core pack enables all P0 mandatory producers once. */
internal fun benchCorePack(): ProbePack =
    ProbePack(
        packId = "bench_core",
        producers = listOf(
            ProbeProducerIds.LIFECYCLE_RUN,
            ProbeProducerIds.REQUEST_CONFIG,
            ProbeProducerIds.MODEL_ROUND,
            ProbeProducerIds.TOOL_EXEC,
            ProbeProducerIds.SEARCH_NETWORK,
            ProbeProducerIds.TERMINAL,
            ProbeProducerIds.CASE_COMPLETENESS,
        ),
    )

/** Seven mandatory stage families (goal-contract). */
internal enum class ProbeStageFamily {
    RUN,
    REQUEST_SESSION_CONFIG,
    MODEL,
    TOOL_BATCH,
    SEARCH_NETWORK,
    TERMINAL_EMPTY,
    CASE_PROBE_COMPLETENESS,
}

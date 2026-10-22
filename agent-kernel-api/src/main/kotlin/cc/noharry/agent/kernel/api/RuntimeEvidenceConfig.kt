package cc.noharry.agent.kernel.api

import java.io.File

/**
 * Aggregated experimental host configuration for local/debug probe evidence.
 *
 * **Not** an API freeze candidate. Debug/local only; expanding to general production
 * evidence requires a separate owner review.
 *
 * Does not expose internal probe kernel types on the public surface.
 * Unknown pack ids and illegal case/hash combinations fail at builder validation.
 */
@ExperimentalAgentRuntimeApi
data class RuntimeEvidenceConfig(
    val storageRoot: File,
    val enabledProbePackIds: Set<String> = emptySet(),
    val casePackId: String = "none",
    val promptSha256: String? = null,
)

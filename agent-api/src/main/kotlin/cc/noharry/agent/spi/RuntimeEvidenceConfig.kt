package cc.noharry.agent.spi

import java.io.File

/**
 * Aggregated experimental host configuration for local/debug probe evidence.
 *
 * Debug/local only; not meant for production evidence.
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

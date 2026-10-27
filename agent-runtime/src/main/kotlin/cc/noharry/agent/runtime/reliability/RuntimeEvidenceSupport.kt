@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.reliability
import cc.noharry.agent.kernel.api.RuntimeEvidenceConfig
import cc.noharry.agent.runtime.probe.ProbePack
import cc.noharry.agent.runtime.probe.benchCorePack
import java.io.File

/** Allowlisted probe pack ids resolvable without host-supplied pack objects. */
internal object RuntimeEvidenceAllowlist {
    const val BENCH_CORE = "bench_core"

    fun resolvePack(id: String): ProbePack =
        when (id) {
            BENCH_CORE -> benchCorePack()
            else -> throw IllegalArgumentException(
                "Unknown probe pack id '$id'. Allowed: $BENCH_CORE",
            )
        }
}

/**
 * Validated, immutable internal form of [RuntimeEvidenceConfig].
 * Empty [enabledProbePackIds] means probes stay fully disabled.
 */
internal data class InternalRuntimeEvidenceConfig(
    val storageRoot: File,
    val enabledProbePackIds: List<String>,
    val casePackId: String,
    val promptSha256: String?,
) {
    val probesEnabled: Boolean get() = enabledProbePackIds.isNotEmpty()

    fun resolvePacks(): List<ProbePack> =
        enabledProbePackIds.map(RuntimeEvidenceAllowlist::resolvePack)
}

private val PROMPT_SHA256_HEX = Regex("^[0-9a-f]{64}$")

/**
 * Validate and canonicalize public [RuntimeEvidenceConfig].
 * Fail-fast at builder time (not mid-run).
 */
internal fun validateRuntimeEvidenceConfig(config: RuntimeEvidenceConfig): InternalRuntimeEvidenceConfig {
    val root = config.storageRoot
    require(root.path.isNotBlank()) { "RuntimeEvidenceConfig.storageRoot path must not be blank" }

    val packIds = config.enabledProbePackIds
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()
    for (id in packIds) {
        RuntimeEvidenceAllowlist.resolvePack(id) // fail-fast unknown
    }

    val caseId = config.casePackId.trim()
    require(caseId.isNotEmpty()) { "RuntimeEvidenceConfig.casePackId must not be blank" }

    val hashRaw = config.promptSha256?.trim()
    if (caseId == "none") {
        require(hashRaw.isNullOrEmpty()) {
            "RuntimeEvidenceConfig.promptSha256 must be null when casePackId is none"
        }
        return InternalRuntimeEvidenceConfig(
            storageRoot = root,
            enabledProbePackIds = packIds,
            casePackId = "none",
            promptSha256 = null,
        )
    }

    require(!hashRaw.isNullOrEmpty()) {
        "RuntimeEvidenceConfig.promptSha256 is required (64 lowercase hex) when casePackId != none"
    }
    // Reject uppercase: hosts must supply already-normalized lowercase hex.
    require(PROMPT_SHA256_HEX.matches(hashRaw)) {
        "RuntimeEvidenceConfig.promptSha256 must be 64 lowercase hex characters"
    }
    return InternalRuntimeEvidenceConfig(
        storageRoot = root,
        enabledProbePackIds = packIds,
        casePackId = caseId,
        promptSha256 = hashRaw,
    )
}

package cc.noharry.agent.runtime

import java.io.File
import cc.noharry.agent.kernel.core.RELIABILITY_CLEANUP_MARKER_FILE
import cc.noharry.agent.kernel.core.ReliabilityRetentionPolicy
import cc.noharry.agent.kernel.core.writeReliabilityText

internal class ReliabilityCleanupScheduler(
    private val root: File,
    private val policy: ReliabilityRetentionPolicy,
    private val cleanup: () -> ReliabilityCleanupResult,
) {
    private var mutationsSinceCleanup: Int = 0
    private var cleanupSequence: Int = 0

    fun onStorageMutation() {
        mutationsSinceCleanup += 1
        if (mutationsSinceCleanup < policy.cleanupEveryMutations) {
            return
        }

        mutationsSinceCleanup = 0
        cleanupSequence += 1
        writeMarker(cleanup())
    }

    private fun writeMarker(result: ReliabilityCleanupResult) {
        // Fail-closed observability: delete failures are never reported as full success.
        val success = result.deleteFailures.isEmpty()
        val reasons = result.deleteFailures.joinToString(";") { "${it.runId}:${it.reason}" }
        File(root, RELIABILITY_CLEANUP_MARKER_FILE).writeReliabilityText(
            listOf(
                "cleanupMode=ON_STORAGE_MUTATION",
                "cleanupEveryMutations=${policy.cleanupEveryMutations}",
                "cleanupSequence=$cleanupSequence",
                "lastDeletedRunDirectories=${result.deletedRunDirectories}",
                "lastDeleteFailures=${result.deleteFailures.size}",
                "lastDeleteFailedRunIds=${result.deleteFailures.joinToString(",") { it.runId }}",
                "lastDeleteFailureReasons=$reasons",
                "lastCleanupSuccess=$success",
                "storageBytesAfter=${result.storageBytesAfter}",
            ).joinToString(separator = "\n", postfix = "\n"),
        )
    }
}

/**
 * Result of production retention cleanup.
 *
 * [deleteFailures] is the fail-closed signal: non-empty means cleanup did **not** fully succeed
 * and must not be treated as a clean PASS by hosts/gates.
 */
internal data class ReliabilityCleanupResult(
    val deletedRunDirectories: Int,
    val storageBytesAfter: Long,
    val deleteFailures: List<ReliabilityDeleteFailure> = emptyList(),
) {
    val cleanupSucceeded: Boolean get() = deleteFailures.isEmpty()
}

internal data class ReliabilityDeleteFailure(
    val runId: String,
    val reason: String,
)

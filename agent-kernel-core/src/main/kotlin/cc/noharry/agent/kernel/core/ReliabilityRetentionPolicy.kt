package cc.noharry.agent.kernel.core

data class ReliabilityRetentionPolicy(
    val maxRunDirectories: Int = DEFAULT_MAX_RUN_DIRECTORIES,
    val maxStartupScanRunDirectories: Int = DEFAULT_MAX_STARTUP_SCAN_RUN_DIRECTORIES,
    val maxStorageBytes: Long? = DEFAULT_MAX_STORAGE_BYTES,
    val cleanupEveryMutations: Int = DEFAULT_CLEANUP_EVERY_MUTATIONS,
) {
    init {
        require(maxRunDirectories > 0) { "maxRunDirectories must be positive." }
        require(maxStartupScanRunDirectories > 0) { "maxStartupScanRunDirectories must be positive." }
        require(maxStorageBytes == null || maxStorageBytes > 0) { "maxStorageBytes must be positive." }
        require(cleanupEveryMutations > 0) { "cleanupEveryMutations must be positive." }
    }
}

internal const val DEFAULT_MAX_RUN_DIRECTORIES = 256
internal const val DEFAULT_MAX_STARTUP_SCAN_RUN_DIRECTORIES = 128
internal const val DEFAULT_MAX_STORAGE_BYTES = 50L * 1024L * 1024L
internal const val DEFAULT_CLEANUP_EVERY_MUTATIONS = 1

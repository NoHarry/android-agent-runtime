package cc.noharry.agent.kernel.api

/** Inclusive candidate Kernel API compatibility window declared by a plugin. */
@ExperimentalAgentRuntimeApi
class KernelApiRange(
    val min: KernelApiVersion,
    val maxInclusive: KernelApiVersion,
) {
    init {
        require(min <= maxInclusive) {
            PluginContractMessages.INVALID_KERNEL_RANGE
        }
    }

    /** Returns whether [version] is inside this inclusive window. */
    fun contains(version: KernelApiVersion): Boolean =
        version >= min && version <= maxInclusive
}

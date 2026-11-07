package cc.noharry.agent.spi

/** Inclusive Kernel API compatibility window declared by a plugin. */
@ExperimentalAgentRuntimeApi
internal class KernelApiRange(
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

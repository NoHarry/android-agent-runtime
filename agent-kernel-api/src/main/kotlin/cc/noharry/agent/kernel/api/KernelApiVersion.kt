package cc.noharry.agent.kernel.api

/** Candidate Kernel API version used for plugin range checks. */
@ExperimentalAgentRuntimeApi
class KernelApiVersion(
    val major: Int,
    val minor: Int,
) : Comparable<KernelApiVersion> {
    init {
        require(major >= 0 && minor >= 0) {
            PluginContractMessages.INVALID_KERNEL_VERSION
        }
    }

    override fun compareTo(other: KernelApiVersion): Int {
        val majorCmp = major.compareTo(other.major)
        return if (majorCmp != 0) majorCmp else minor.compareTo(other.minor)
    }

    override fun equals(other: Any?): Boolean =
        other is KernelApiVersion && major == other.major && minor == other.minor

    override fun hashCode(): Int = 31 * major + minor

    override fun toString(): String = "$major.$minor"
}

package cc.noharry.agent.kernel.api

/** Candidate semantic version for a packaged [AgentPlugin]. */
@ExperimentalAgentRuntimeApi
class PluginVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) {
            PluginContractMessages.INVALID_PLUGIN_VERSION
        }
    }

    override fun equals(other: Any?): Boolean =
        other is PluginVersion &&
            major == other.major &&
            minor == other.minor &&
            patch == other.patch

    override fun hashCode(): Int = 31 * (31 * major + minor) + patch

    override fun toString(): String = "$major.$minor.$patch"
}

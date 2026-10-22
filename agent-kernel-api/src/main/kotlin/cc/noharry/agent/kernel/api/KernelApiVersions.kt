package cc.noharry.agent.kernel.api

/** Published candidate Kernel API versions understood by this runtime. */
@ExperimentalAgentRuntimeApi
object KernelApiVersions {
    /** Kernel API version compiled into this artifact. */
    val CURRENT: KernelApiVersion = KernelApiVersion(1, 0)
}

package cc.noharry.agent.spi

/** Kernel API versions understood by this runtime. */
@ExperimentalAgentRuntimeApi
internal object KernelApiVersions {
    /** Kernel API version compiled into this artifact. */
    val CURRENT: KernelApiVersion = KernelApiVersion(1, 0)
}

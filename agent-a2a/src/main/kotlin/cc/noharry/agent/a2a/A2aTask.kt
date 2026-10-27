package cc.noharry.agent.a2a

/** Snapshot of a remote A2A task. */
@ExperimentalA2aClientApi
public data class A2aTask(
    public val id: String,
    public val contextId: String? = null,
    public val status: Status,
    public val statusMessage: A2aMessage? = null,
    /** Artifacts carried by the snapshot, in remote order. */
    public val artifacts: List<A2aArtifact> = emptyList(),
) {
    /** Stable task lifecycle states understood by the candidate facade. */
    public enum class Status {
        /** Work has not started. */
        SUBMITTED,
        /** Work is running. */
        WORKING,
        /** Remote input is required. */
        INPUT_REQUIRED,
        /** Work completed successfully. */
        COMPLETED,
        /** Work was cancelled. */
        CANCELLED,
        /** Work failed. */
        FAILED,
        /** Remote state was not recognized. */
        UNKNOWN,
    }
}

package cc.noharry.agent.a2a

/** Ordered event emitted by an A2A message or task stream. */
@ExperimentalA2aClientApi
public sealed interface A2aStreamEvent {
    /** A Message payload was received. */
    public data class Message(public val value: A2aMessage) : A2aStreamEvent
    /** A Task snapshot or status update was received; [metadata] is the update event's own metadata. */
    public data class Task(
        public val value: A2aTask,
        public val metadata: A2aValue.ObjectValue? = null,
    ) : A2aStreamEvent
    /** An artifact update for [taskId] was received; it does not end the stream by itself. */
    public data class Artifact(
        public val taskId: String,
        public val contextId: String?,
        public val artifact: A2aArtifact,
        public val append: Boolean = false,
        public val lastChunk: Boolean = true,
    ) : A2aStreamEvent
    /** The remote stream reached an explicit successful terminal payload. */
    public data object Completed : A2aStreamEvent
    /** The stream ended with a stable client error. */
    public data class Failed(public val error: A2aClientError) : A2aStreamEvent
    /** The connection ended without proof that the remote operation completed. */
    public data class Incomplete(public val error: A2aClientError.Incomplete) : A2aStreamEvent
}

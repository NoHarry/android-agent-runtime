package cc.noharry.agent.a2a

/** Successful result of `message/send`. */
@ExperimentalA2aClientApi
public sealed interface A2aSendMessageResult {
    /** The remote returned a Message directly. */
    public data class Message(public val value: A2aMessage) : A2aSendMessageResult
    /** The remote created or updated a Task. */
    public data class Task(public val value: A2aTask) : A2aSendMessageResult
}

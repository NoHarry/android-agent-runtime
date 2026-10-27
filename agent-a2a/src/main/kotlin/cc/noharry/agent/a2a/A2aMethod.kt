package cc.noharry.agent.a2a

/** Operations for which an authentication provider may issue credentials. */
@ExperimentalA2aClientApi
public enum class A2aMethod {
    /** Agent Card discovery. */
    GET_AGENT_CARD,
    /** Synchronous message exchange. */
    SEND_MESSAGE,
    /** Streaming message exchange. */
    STREAM_MESSAGE,
    /** Task lookup. */
    GET_TASK,
    /** Remote task cancellation. */
    CANCEL_TASK,
    /** Task stream resubscription. */
    RESUBSCRIBE_TASK,
}

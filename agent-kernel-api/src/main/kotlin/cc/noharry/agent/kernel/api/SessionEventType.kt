package cc.noharry.agent.kernel.api

/**
 * Candidate lossless event kinds for [SessionEventLog] v2.
 *
 * This scaffold is experimental and does not freeze the event taxonomy.
 */
@ExperimentalAgentRuntimeApi
enum class SessionEventType {
    RUN_STARTED,
    USER_MESSAGE,
    USER_INTERACTION,
    MODEL_REQUEST,
    ASSISTANT_CHUNK,
    ASSISTANT_MESSAGE,
    MODEL_TOOL_CALL,
    TOOL_STARTED,
    TOOL_RESULT,
    USAGE,
    MODEL_RESPONSE_METADATA,
    MODEL_CALL_FINISHED,
    RUN_STATUS,
    RUN_FINISHED,
}

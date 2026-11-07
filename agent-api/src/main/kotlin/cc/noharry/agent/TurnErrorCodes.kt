package cc.noharry.agent

/**
 * Conversation-level codes returned in [TurnError] and [ThreadItem.Error]. Provider and tool failures may also use
 * the codes in [cc.noharry.agent.spi.reliability.RuntimeErrorCode]. Hosts should retain a fallback for unknown codes.
 */
object TurnErrorCodes {
    /** The selected model or runtime configuration does not accept image input. */
    const val IMAGE_INPUT_UNSUPPORTED = "IMAGE_INPUT_UNSUPPORTED"
    /** The caller interrupted an admitted turn. */
    const val INTERRUPTED = "INTERRUPTED"
    /** An unexpected execution failure prevented the turn from completing. */
    const val TURN_FAILED = "TURN_FAILED"
    /** Execution ended without producing a terminal result. */
    const val MISSING_TURN_RESULT = "MISSING_TURN_RESULT"
    /** A non-queuing request encountered another active turn in the same thread. */
    const val THREAD_BUSY = "THREAD_BUSY"
    /** The conversation was deleted while this turn was queued or executing. */
    const val THREAD_DELETED = "THREAD_DELETED"
    /** The selected question is not an unanswered question in the current conversation or turn. */
    const val QUESTION_NOT_FOUND = "QUESTION_NOT_FOUND"
    /** Conversation data could not be loaded or saved; a result may be unavailable after restarting. */
    const val STORAGE_FAILED = "STORAGE_FAILED"
    /** The referenced attachment is absent or failed integrity verification. */
    const val ATTACHMENT_NOT_FOUND = "ATTACHMENT_NOT_FOUND"
    /** The referenced attachment has an unsupported media type. */
    const val ATTACHMENT_MEDIA_UNSUPPORTED = "ATTACHMENT_MEDIA_UNSUPPORTED"
    /** A text attachment is not valid UTF-8. */
    const val ATTACHMENT_INVALID_TEXT = "ATTACHMENT_INVALID_TEXT"
    /** The per-thread queue has reached its configured limit. */
    const val SESSION_QUEUE_FULL = "SESSION_QUEUE_FULL"
    /** The shared queue has reached its configured limit. */
    const val RUNTIME_BUSY = "RUNTIME_BUSY"
    /** The runtime was closed before the queued turn could execute. */
    const val RUNTIME_CLOSED = "RUNTIME_CLOSED"
    /** A required approval was not received before its deadline. */
    const val CONFIRMATION_EXPIRED = "CONFIRMATION_EXPIRED"
    /** A tool's approval was denied; a tool result can carry this code while the turn continues. */
    const val CONFIRMATION_DENIED = "CONFIRMATION_DENIED"
    /** A pending tool approval was canceled without executing the tool. */
    const val CONFIRMATION_CANCELLED = "CONFIRMATION_CANCELLED"
}

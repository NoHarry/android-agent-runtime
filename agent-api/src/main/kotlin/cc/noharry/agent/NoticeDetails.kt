package cc.noharry.agent

/**
 * Structured notice values for host presentation. Hosts can localize [ThreadItem.Notice] without parsing its
 * English fallback message. Fields unrelated to the notice's [NoticeKind] are null.
 */
data class NoticeDetails(
    /** Retry ordinal, starting at one, for [NoticeKind.RETRY]. */
    val attempt: Int? = null,
    /** Backoff in milliseconds for [NoticeKind.RETRY]. */
    val delayMillis: Long? = null,
    /** Remaining model rounds for [NoticeKind.BUDGET_LOW]. */
    val roundsRemaining: Int? = null,
    /** Remaining tool calls for [NoticeKind.BUDGET_LOW]. */
    val toolCallsRemaining: Int? = null,
    /** Provider-history message count before [NoticeKind.COMPACTION]. */
    val beforeMessageCount: Int? = null,
    /** Provider-history message count after [NoticeKind.COMPACTION]. */
    val afterMessageCount: Int? = null,
    /** Selected workspace identifier for [NoticeKind.WORKSPACE_CHANGED]. */
    val workspaceId: String? = null,
)

package cc.noharry.agent

/**
 * A turn starts with [TurnStarted] and ends with exactly one [TurnCompleted] or [TurnFailed].
 * Each item starts once, has zero or more deltas/updates, and completes once with its complete contents.
 * All started items complete before the terminal event, including on failure.
 * Cancellation of the collecting coroutine interrupts the turn; a cancelled collector cannot receive events.
 * Persisted items remain available through AgentThread.items after cancellation.
 */
sealed interface ThreadEvent {
    data class TurnStarted(val threadId: String, val turnId: String) : ThreadEvent
    data class ItemStarted(val item: ThreadItem) : ThreadEvent
    data class ItemDelta(val itemId: String, val text: String) : ThreadEvent
    data class ItemUpdated(val item: ThreadItem) : ThreadEvent
    data class ItemCompleted(val item: ThreadItem) : ThreadEvent
    data class UsageUpdated(val turnId: String, val usage: Usage) : ThreadEvent
    data class TurnCompleted(val result: TurnResult) : ThreadEvent
    data class TurnFailed(val turnId: String, val error: TurnError) : ThreadEvent
}

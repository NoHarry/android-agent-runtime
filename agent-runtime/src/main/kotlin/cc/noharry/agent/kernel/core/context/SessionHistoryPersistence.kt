package cc.noharry.agent.kernel.core.context

import cc.noharry.agent.spi.model.ModelMessage

/** Runtime-owned storage for the exact provider history, separate from redacted visible items. */
internal interface SessionHistoryPersistence {
    val identity: Any get() = this
    fun load(sessionId: String): StoredSessionHistory?
    fun save(sessionId: String, history: StoredSessionHistory)
    fun delete(sessionId: String)
    fun clear()
}

internal data class StoredSessionHistory(
    val runs: List<List<ModelMessage>>,
    val lastInputTokens: Long? = null,
)

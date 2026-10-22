@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.core.SessionHistoryStore

/**
 * A conversation's earlier runs as the model saw them, kept outside any one runtime. A host that builds a new runtime
 * for every run passes the same instance to each [AgentRuntimeBuilder.sessionHistory]; each run's requests then start
 * with the session's earlier runs (their inputs, tool calls, results and answers) and the run is appended when it ends,
 * so the conversation grows like one long chat and the provider's prompt cache covers everything before the new input.
 * A host that does this no longer needs to restate the conversation in [AgentRuntimeBuilder.runContext].
 * Memory only. Candidate API; not frozen.
 *
 * Length is kept down by conversation compaction (with [AgentRuntimeBuilder.contextWindowTokens]), which summarizes the
 * oldest runs instead of dropping them.
 *
 * @param maxBytesPerSession a memory guard, not the working limit: past it the oldest runs are dropped, down to half of
 * it, and the model no longer sees them. Keep it well above what the context window holds before compaction.
 */
@ExperimentalAgentRuntimeApi
class SessionHistory(maxBytesPerSession: Int = DEFAULT_MAX_BYTES_PER_SESSION) {
    init {
        require(maxBytesPerSession > 0) { "maxBytesPerSession must be positive" }
    }

    internal val store = SessionHistoryStore(maxBytesPerSession)

    /** The messages [sessionId]'s next run will start with; empty for a new session or after a restart. */
    fun messages(sessionId: String): List<ModelMessage> = store.messages(sessionId)

    /** Forgets [sessionId]'s runs, e.g. when the host deletes or resets that conversation. */
    fun clearSession(sessionId: String) {
        store.clearSession(sessionId)
    }

    /** Forgets every session, e.g. when the host clears its runtime data. */
    fun clearAll() {
        store.clearAll()
    }
}

private const val DEFAULT_MAX_BYTES_PER_SESSION = 4 * 1024 * 1024

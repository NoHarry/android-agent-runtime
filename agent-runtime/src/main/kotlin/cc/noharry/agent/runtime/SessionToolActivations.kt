@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.core.tools.ToolActivationStore

/**
 * The backend tools a conversation's runs have found, kept outside any one runtime. A host that builds a new runtime
 * for every run passes the same instance to each [AgentRuntimeBuilder.sessionToolActivations]; a run then starts with
 * the tools its session's earlier runs found, in the order they found them, so the model can call one again without
 * searching and the tool list (and the provider's prompt cache over it) carries over. Tools found by memory hints
 * are not carried. Candidate API; not frozen.
 *
 * @param maxToolsPerSession most tools a session carries; past it the oldest go, down to half of it.
 */
@ExperimentalAgentRuntimeApi
class SessionToolActivations(
    maxToolsPerSession: Int = DEFAULT_MAX_TOOLS_PER_SESSION,
    nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(maxToolsPerSession > 0) { "maxToolsPerSession must be positive" }
    }

    internal val store = ToolActivationStore(maxToolsPerSession, nowEpochMillis)

    /** Forgets what [sessionId]'s runs found, e.g. when the host deletes or resets that conversation. */
    fun clearSession(sessionId: String) {
        store.clearSession(sessionId)
    }

    /** Forgets every session's tools, e.g. when the host clears its runtime data. */
    fun clearAll() {
        store.clearAllSessions()
    }
}

private const val DEFAULT_MAX_TOOLS_PER_SESSION = 64

package cc.noharry.agent.appserver.session
import cc.noharry.agent.appserver.protocol.TokenUsageState
import cc.noharry.agent.appserver.runtime.ActiveRunRecord
import cc.noharry.agent.appserver.skills.AppSubagents
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The Board's usage: the whole session, its stored turns plus the run on screen, each run once.
 * The stored part is read from the session's turns the first time (off the main thread) and kept up to date as turns
 * are appended; a session stored before turns kept their usage shows only what ran since.
 */
internal class SessionUsage(
    private val coordinator: SessionCoordinator,
    private val scope: CoroutineScope,
) {
    private val loading = ConcurrentHashMap.newKeySet<String>()

    fun of(sessionId: String, live: ActiveRunRecord?, onLoaded: () -> Unit): TokenUsageState {
        val stored = coordinator.storedTurnUsage(sessionId)
        // One read per session; once loaded, appended turns keep it current and no further read is started.
        if (stored == null && loading.add(sessionId)) {
            scope.launch(Dispatchers.IO) {
                try {
                    coordinator.loadTurnUsage(sessionId)
                } finally {
                    loading.remove(sessionId)
                }
                onLoaded()
            }
        }
        // Background subagents' calls count toward the conversation that started them (this process only).
        return stored.orEmpty().filterKeys { it != live?.requestId }.values
            .fold((live?.tokenUsage ?: TokenUsageState()) + AppSubagents.usage(sessionId)) { total, usage -> total + usage }
    }
}

package cc.noharry.agent.kernel.core

/** Core-owned persistence seam for session Todo state. */
interface TodoStorePort {
    suspend fun load(sessionId: String): TodoState?

    suspend fun save(sessionId: String, state: TodoState): Boolean

    suspend fun clear(sessionId: String): Boolean
}

/**
 * Process-local fallback used when the host configures no persistent [TodoStorePort].
 *
 * Keeps `update_todo` and in-loop todo reminders working without writing any
 * host workspace or business VFS state; continuity across process restarts is
 * intentionally not provided (prompts require rewriting todos per turn).
 */
internal class InMemoryTodoStore : TodoStorePort {
    private val statesBySession = java.util.concurrent.ConcurrentHashMap<String, TodoState>()

    override suspend fun load(sessionId: String): TodoState? = statesBySession[sessionId]

    override suspend fun save(sessionId: String, state: TodoState): Boolean {
        statesBySession[sessionId] = state
        return true
    }

    override suspend fun clear(sessionId: String): Boolean {
        statesBySession.remove(sessionId)
        return true
    }
}

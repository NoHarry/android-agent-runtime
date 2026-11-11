package cc.noharry.agent.appserver.runtime

import android.content.Context
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.session.SessionCoordinator
import kotlinx.coroutines.Job

/**
 * The sessions beside the selected one, as clients follow them (`thread/subscribe`): a followed session's transcript
 * stays loaded and live, as does one with a run going on, and its items go out with the state ([items]). Main thread.
 */
internal class SessionThreads(
    private val sessions: SessionCoordinator,
    private val runner: RuntimeSessionRunner,
    private val changed: () -> Unit,
) {
    /** Whether some client follows a session; set by the server. */
    @Volatile var followed: (String) -> Boolean = { false }

    init {
        sessions.transcripts.keepLoaded = { sessionId -> runner.isRunningIn(sessionId) || followed(sessionId) }
    }

    /** [sessionId]'s items now, loaded beside the selected session when it is another. */
    fun load(context: Context, sessionId: String): List<ConversationItem> {
        sessions.ensure(context.applicationContext)
        return sessions.loadTranscript(sessionId).snapshot().also { changed() }
    }

    /** [sessionId]'s items now: live when it is loaded, else as stored. */
    fun read(context: Context, sessionId: String): List<ConversationItem> {
        sessions.ensure(context.applicationContext)
        return sessions.transcriptOf(sessionId)?.snapshot() ?: sessions.storedItems(sessionId)
    }

    /** No client follows [sessionId] any more: it is let go unless it is selected or a run goes on in it. */
    fun release(sessionId: String) {
        if (sessionId == sessions.activeSessionId) return
        sessions.transcripts.release(sessionId)
        changed()
    }

    fun stop(sessionId: String): Job = runner.stopRun(sessionId)

    /** Every loaded session's items but the selected one's. */
    fun items(): Map<String, List<ConversationItem>> = sessions.transcripts.snapshots()
}

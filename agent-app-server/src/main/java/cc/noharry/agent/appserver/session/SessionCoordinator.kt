package cc.noharry.agent.appserver.session
import android.content.Context
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.SessionBoardState
import cc.noharry.agent.appserver.protocol.SessionSummary
import cc.noharry.agent.appserver.protocol.SessionTurn
import cc.noharry.agent.appserver.protocol.TokenUsageState

internal class SessionCoordinator(transcript: TranscriptStore) {
    private val fields = SessionFields(transcript)
    private val admin = SessionAdmin(fields)

    var activeSessionId: String
        get() = fields.activeSessionId
        private set(value) {
            fields.activeSessionId = value
        }

    var sessions: List<SessionSummary>
        get() = fields.sessions
        private set(value) {
            fields.sessions = value
        }

    fun ensure(context: Context): SessionStore = sessionEnsure(fields, context)
    fun newSession(context: Context) = sessionNew(fields, context)
    internal fun selectSession(context: Context, sessionId: String) = sessionSelect(fields, context, sessionId)
    internal fun deleteSession(context: Context, sessionId: String) = sessionDelete(fields, context, sessionId)
    fun clearAllRuntimeData(context: Context) = sessionClearAll(fields, context)
    fun adoptActiveSessionId(sessionId: String) = admin.adoptActive(sessionId)
    internal fun reviveDeletedSession(sessionId: String) = admin.forgetDeleted(sessionId)
    internal fun attachStoreForTest(store: SessionStore) = admin.attachStore(store)
    internal fun markSessionDeleted(sessionId: String) = admin.markDeleted(sessionId)
    internal fun isSessionDeleted(sessionId: String): Boolean = admin.isDeleted(sessionId)
    internal fun forgetDeletedSession(sessionId: String) = admin.forgetDeleted(sessionId)
    internal fun deletePersistedSession(sessionId: String) = admin.deletePersisted(sessionId)
    fun appendTurn(turn: SessionTurn) = sessionAppendTurn(fields, turn)
    /** [sessionId]'s stored turns' usage by request id, or null until [loadTurnUsage] has read it. */
    internal fun storedTurnUsage(sessionId: String): Map<String, TokenUsageState>? = fields.turnUsage[sessionId]
    internal fun loadTurnUsage(sessionId: String) = sessionLoadTurnUsage(fields, sessionId)
    fun board(): SessionBoardState = sessionBoard(fields)

    /** The selected session's transcript and the other loaded sessions'. */
    internal val transcripts: LoadedTranscripts get() = fields.transcripts

    /** [sessionId]'s live transcript: the selected one's, a loaded one's, or null. */
    internal fun transcriptOf(sessionId: String): TranscriptStore? = fields.transcripts.of(sessionId, fields.activeSessionId)

    /** Loads [sessionId] beside the selected one (from its stored turns) and gives its live transcript. */
    internal fun loadTranscript(sessionId: String): TranscriptStore = transcriptOf(sessionId)
        ?: fields.transcripts.load(sessionId) { storedItems(sessionId) }

    /** [sessionId]'s items as its stored turns give them. */
    internal fun storedItems(sessionId: String): List<ConversationItem> = fields.store?.transcriptItems(sessionId).orEmpty()
}

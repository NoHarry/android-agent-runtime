package cc.noharry.agent.appserver.session
import android.content.Context
import android.content.SharedPreferences
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.SessionPolicy
import cc.noharry.agent.appserver.protocol.SessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** When a wake starts a new conversation ([SessionPolicy]). */
@cc.noharry.agent.appserver.AppServerPluginApi
object SessionSettings {
    private val mutablePolicy = MutableStateFlow(SessionPolicy())
    val policy: StateFlow<SessionPolicy> = mutablePolicy

    fun init(context: Context) {
        val prefs = prefs(context)
        mutablePolicy.value = SessionPolicy(
            everyWake = prefs.getBoolean(KEY_EVERY_WAKE, false),
            idleMinutes = prefs.getInt(KEY_IDLE_MINUTES, SessionPolicy.DEFAULT_IDLE_MINUTES),
            maxSessions = prefs.getInt(KEY_MAX_SESSIONS, SessionPolicy.DEFAULT_MAX_SESSIONS),
        ).bounded()
    }

    fun save(context: Context, policy: SessionPolicy) {
        val bounded = policy.bounded()
        prefs(context).edit()
            .putBoolean(KEY_EVERY_WAKE, bounded.everyWake)
            .putInt(KEY_IDLE_MINUTES, bounded.idleMinutes)
            .putInt(KEY_MAX_SESSIONS, bounded.maxSessions)
            .apply()
        mutablePolicy.value = bounded
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "app_server_sessions"
    private const val KEY_EVERY_WAKE = "new_session_every_wake"
    private const val KEY_IDLE_MINUTES = "new_session_idle_minutes"
    private const val KEY_MAX_SESSIONS = "max_sessions"
}

/**
 * The approval mode ([ConfirmationMode]) the person chose from a client (`run/set`), kept across restarts in the
 * preferences file `app_server_approvals`; until one is chosen, and after [forget], the host's
 * ([cc.noharry.agent.appserver.server.AppServerConfig.approvals]), where a new installation starts.
 */
internal object ApprovalSettings {
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var hosts: ConfirmationMode = ConfirmationMode.ASK_EVERY_TIME

    /** The mode turns run in now. */
    @Volatile
    var mode: ConfirmationMode = ConfirmationMode.ASK_EVERY_TIME
        private set

    /** At each start: the person's choice, else [host]'s. */
    fun init(context: Context, host: ConfirmationMode) {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
        hosts = host
        mode = stored.getString(KEY_MODE, null)?.let { name -> ConfirmationMode.entries.firstOrNull { it.name == name } } ?: host
    }

    /** The person chose [mode]: kept for the next starts too. */
    fun choose(mode: ConfirmationMode) {
        prefs?.edit()?.putString(KEY_MODE, mode.name)?.apply()
        this.mode = mode
    }

    /** The host's mode again, now and at the next starts, until the person chooses another. */
    fun forget() {
        prefs?.edit()?.remove(KEY_MODE)?.apply()
        mode = hosts
    }

    private const val PREFS = "app_server_approvals"
    private const val KEY_MODE = "mode"
}

/**
 * The sessions to delete to keep [keep]: the ones past the newest [keep] ([sessions] is newest first), except those in
 * [protected] (the selected one, one running, one with background work), which stay even over the limit.
 */
internal fun sessionsOverLimit(sessions: List<SessionSummary>, keep: Int, protected: Set<String>): List<String> =
    sessions.drop(keep.coerceAtLeast(1)).map { it.sessionId }.filterNot { it in protected }

package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.util.Locale

/** A new thread's title until its first turn names it, and one the runtime could not name. */
const val NEW_SESSION_TITLE = "New session"
const val UNTITLED_SESSION_TITLE = "Debug session"

private const val MILLIS_PER_MINUTE = 60_000L
private const val PERCENT = 100

/**
 * When a wake starts a new conversation (after some minutes without a turn, or at every wake), and how many
 * conversations are kept (20 by default; the oldest go beyond it).
 */
@Serializable
data class SessionPolicy(
    /** Every wake starts a new conversation, however recent the last turn. */
    val everyWake: Boolean = false,
    /** Otherwise, how long without a turn before a wake does. */
    val idleMinutes: Int = DEFAULT_IDLE_MINUTES,
    /** Conversations kept; past it the ones touched longest ago are deleted, with all they hold. */
    val maxSessions: Int = DEFAULT_MAX_SESSIONS,
) {
    val idleMillis: Long get() = idleMinutes * MILLIS_PER_MINUTE

    fun bounded(): SessionPolicy = copy(
        idleMinutes = idleMinutes.coerceIn(MIN_IDLE_MINUTES, MAX_IDLE_MINUTES),
        maxSessions = maxSessions.coerceIn(MIN_SESSIONS, MAX_SESSIONS),
    )

    companion object {
        const val DEFAULT_IDLE_MINUTES = 5
        const val DEFAULT_MAX_SESSIONS = 20
        const val MIN_IDLE_MINUTES = 1
        const val MAX_IDLE_MINUTES = 240
        const val MIN_SESSIONS = 1
        const val MAX_SESSIONS = 500
    }
}

/** Latest completed business request's inclusive input count, not cumulative run billing. */
@Serializable
data class ContextUsage(
    val inputTokens: Long? = null,
    val windowTokens: Long? = null,
    val awaitingAfterCompaction: Boolean = false,
) {
    val fraction: Float? get() = inputTokens?.let { used ->
        windowTokens?.takeIf { it > 0 }?.let { (used.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    }
    val remainingTokens: Long? get() = inputTokens?.let { used -> windowTokens?.let { (it - used).coerceAtLeast(0) } }
    val percentText: String? get() = inputTokens?.let { used ->
        windowTokens?.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.1f%%", used.toDouble() / it * PERCENT) }
    }
}

/** What the person set that a front end shows and changes; the server saves each change and sends it back. */
@Serializable
data class Preferences(
    val sessionPolicy: SessionPolicy = SessionPolicy(),
    val basicTools: List<BasicToolView> = emptyList(),
    val androidUse: AndroidUseView = AndroidUseView(),
    val runtimeSkills: List<RuntimeSkillView> = emptyList(),
    val subagents: SubagentSettingsView = SubagentSettingsView(),
    val subagentKinds: List<SubagentKindView> = emptyList(),
    /** Some profile has a service's search and fetch APIs, which some subagent types need. */
    val webLookup: Boolean = false,
)

@Serializable
@SerialName("preferences")
data class PreferencesSection(val preferences: Preferences = Preferences()) : StateSection

/** Requests that change [Preferences]. */
object PreferenceRequests {
    val SET_SESSION_POLICY = RequestMethod("preferences/sessionPolicy", SessionPolicy.serializer(), Empty.serializer())
}

/** A token count as the screens show it: 128,000. */
fun formatContextTokens(tokens: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(tokens)

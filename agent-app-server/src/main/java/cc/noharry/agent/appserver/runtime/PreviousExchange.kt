package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.session.SessionStore

/** Latest stored turn used to bind options and host confirmation checks. */
@cc.noharry.agent.appserver.AppServerPluginApi
data class PreviousExchange(
    val sessionId: String,
    val turnId: String,
    val userText: String,
    val assistantText: String,
    val status: String,
    val completion: String,
    val scene: String = "",
)

internal fun SessionStore.previousExchange(sessionId: String): PreviousExchange? =
    // Never filter back to an old successful turn: a failed/cancelled latest turn is still a boundary.
    readTurns(sessionId).lastOrNull()?.let {
        PreviousExchange(it.sessionId, it.turnId, it.userText, it.finalResponse, it.finalStatus, it.completionOutcome, it.scene)
    }

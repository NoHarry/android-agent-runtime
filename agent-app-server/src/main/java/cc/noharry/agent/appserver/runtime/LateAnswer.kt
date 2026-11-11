@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.protocol.UserQuestion
import cc.noharry.agent.SteerResult

/** A late reply must carry the question, since the new run's model request starts from this text. */
internal fun lateAnswerText(question: UserQuestion, text: String, words: ModelWords = AppHost.words): String =
    text

/** Why a reply to an ended run could not become a new turn; the console shows it in the screen's language. */
internal const val SESSION_BUSY_REASON = "SESSION_BUSY"

/** Where a question reply goes after the exact-run post: consumed by that run, a new turn, an error, or nothing to show. */
internal sealed interface AnswerRoute {
    data object Delivered : AnswerRoute
    data object Late : AnswerRoute
    data object Silent : AnswerRoute
    data class Failed(val reason: String) : AnswerRoute
}

/**
 * Only the selected, idle session may turn a reply to an ended run into a new turn; a busy selected session reports
 * instead, and a run the UI no longer owns stays silent because its errors belong to another view.
 */
internal fun answerRoute(result: SteerResult, selected: Boolean, running: Boolean, ownsRun: Boolean): AnswerRoute =
    when (result) {
        SteerResult.ACCEPTED -> AnswerRoute.Delivered
        SteerResult.NOT_RUNNING -> when {
            selected && !running -> AnswerRoute.Late
            selected -> AnswerRoute.Failed(SESSION_BUSY_REASON)
            else -> AnswerRoute.Silent
        }
        SteerResult.QUESTION_NOT_FOUND -> if (ownsRun) AnswerRoute.Failed("QUESTION_NOT_FOUND") else AnswerRoute.Silent
        SteerResult.EMPTY_INPUT, SteerResult.QUEUE_FULL, SteerResult.REQUEST_ID_CONFLICT -> if (ownsRun) AnswerRoute.Failed(result.name) else AnswerRoute.Silent
    }

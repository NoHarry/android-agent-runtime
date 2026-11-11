@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.console

import cc.noharry.agent.SteerResult
import cc.noharry.agent.appserver.protocol.QuestionAnswerMode
import cc.noharry.agent.appserver.protocol.TrajectoryRunGroup
import cc.noharry.agent.appserver.protocol.TrajectorySnapshot
import cc.noharry.agent.appserver.protocol.UserQuestion
import cc.noharry.agent.appserver.protocol.view.questionCards
import cc.noharry.agent.appserver.protocol.view.userQuestions
import cc.noharry.agent.appserver.runtime.AnswerRoute
import cc.noharry.agent.appserver.runtime.ModelWords
import cc.noharry.agent.appserver.runtime.answerRoute
import cc.noharry.agent.appserver.runtime.lateAnswerText
import cc.noharry.agent.appserver.trajectory.sessionEvent
import cc.noharry.agent.appserver.trajectory.toTrajectoryRecord
import cc.noharry.agent.spi.SessionEventPayload.UserInteraction
import cc.noharry.agent.spi.SessionEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The person's questions: recovered from the canonical log, shown while answerable, and answered late. */
class UserQuestionsTest {
    @Test fun coldCanonicalRecordsRecoverLatestQuestionStateAndKeepSessionsBound() {
        val pending = UserInteraction("QUESTION", "PENDING", questionId = "run/q/0", text = "去哪？", options = listOf("苏州"))
        val answered = pending.copy(state = "ANSWERED", inputRequestId = "answer")
        val records = listOf(pending, answered).mapIndexed { index, payload ->
            sessionEvent(eventId = "q-$index", sequence = index + 1L, type = SessionEventType.USER_INTERACTION)
                .copy(payload = payload).toTrajectoryRecord()
        }
        val snapshot = TrajectorySnapshot("s1", runGroups = listOf(
            TrajectoryRunGroup(sessionId = "s1", runId = "run-1", requestId = "req-1", records = records),
        ))
        val question = snapshot.userQuestions().single()
        assertEquals("ANSWERED", question.state)
        assertEquals("s1", question.sessionId)
        assertEquals(listOf("苏州"), question.options)
        assertEquals("run/q/0", question.id)
    }

    @Test fun cardsShowOnlyTheActiveRunOrTheLatestRunAndKeepEndedRunsAnswerableAsLateReplies() {
        fun group(runId: String, state: String?, startedAt: Long) = TrajectoryRunGroup(sessionId = "s1", runId = runId, startedAtEpochMillis = startedAt, records = listOfNotNull(
            state?.let {
                sessionEvent(eventId = "$runId-q", sequence = 1L, type = SessionEventType.USER_INTERACTION)
                    .copy(payload = UserInteraction("QUESTION", it, questionId = "$runId/q/0", text = "去哪？")).toTrajectoryRecord()
            },
        ))
        val snapshot = TrajectorySnapshot("s1", runGroups = listOf(group("run-2", "PENDING", 2L), group("run-1", "EXPIRED", 1L)))
        assertEquals(listOf("run-2/q/0" to QuestionAnswerMode.ACTIVE), snapshot.questionCards("run-2").map { it.first.id to it.second })
        assertEquals(listOf("run-2/q/0" to QuestionAnswerMode.LATE), snapshot.questionCards(null).map { it.first.id to it.second })
        assertTrue(snapshot.questionCards("run-3").isEmpty())
        val answered = TrajectorySnapshot("s1", runGroups = listOf(group("run-2", "ANSWERED", 2L), group("run-1", "EXPIRED", 1L)))
        assertEquals(listOf(QuestionAnswerMode.NONE), answered.questionCards(null).map { it.second })
        // A later turn without questions (for example the late reply itself) retires the older run's cards.
        val replied = TrajectorySnapshot("s1", runGroups = listOf(group("run-3", null, 3L), group("run-2", "EXPIRED", 2L)))
        assertTrue(replied.questionCards(null).isEmpty())
        val late = lateAnswerText(UserQuestion("run-2/q/0", "run-2", "s1", "去哪？", emptyList(), "EXPIRED"), "苏州", ModelWords.CHINESE)
        assertEquals("苏州", late)
        assertEquals(AnswerRoute.Late, answerRoute(SteerResult.NOT_RUNNING, selected = true, running = false, ownsRun = false))
        assertEquals(AnswerRoute.Failed("SESSION_BUSY"), answerRoute(SteerResult.NOT_RUNNING, selected = true, running = true, ownsRun = false))
        assertEquals(AnswerRoute.Silent, answerRoute(SteerResult.NOT_RUNNING, selected = false, running = false, ownsRun = false))
        assertEquals(AnswerRoute.Delivered, answerRoute(SteerResult.ACCEPTED, selected = true, running = true, ownsRun = true))
        assertEquals(AnswerRoute.Failed("QUEUE_FULL"), answerRoute(SteerResult.QUEUE_FULL, selected = true, running = true, ownsRun = true))
    }
}

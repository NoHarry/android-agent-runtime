package cc.noharry.agent.appserver.protocol.view

import cc.noharry.agent.appserver.protocol.QuestionAnswerMode
import cc.noharry.agent.appserver.protocol.TrajectorySnapshot
import cc.noharry.agent.appserver.protocol.UserQuestion

// The user questions a trajectory holds, and which of them the question cards show; pure, so the server's tests
// check them too.

fun TrajectorySnapshot.userQuestions(): List<UserQuestion> = runGroups.flatMap { group ->
    val questions = linkedMapOf<String, UserQuestion>()
    group.records.sortedBy { it.sequence }.forEach { record ->
        val fact = record.rich?.question ?: return@forEach
        if (fact.kind == "QUESTION") questions[fact.questionId] = UserQuestion(
            fact.questionId, group.runId, group.sessionId, fact.text, fact.options, fact.state,
        )
    }
    questions.values.toList()
}

/**
 * Only the active run's questions, or when idle the latest run's questions, are shown. A question whose run
 * ended without an answer stays answerable as a late reply that starts a new turn quoting the question.
 */
fun TrajectorySnapshot.questionCards(activeRunId: String?): List<Pair<UserQuestion, QuestionAnswerMode>> {
    val all = userQuestions()
    val active = all.filter { it.runId == activeRunId }
    if (active.isNotEmpty()) return active.map { it to if (it.state == "PENDING") QuestionAnswerMode.ACTIVE else QuestionAnswerMode.NONE }
    if (activeRunId != null) return emptyList()
    // Only the session's most recent run may still be answered late; once a later turn exists, older cards go away.
    val latestRun = (runGroups.firstOrNull { it.isActive } ?: runGroups.maxByOrNull { it.startedAtEpochMillis ?: Long.MIN_VALUE })?.runId
        ?: return emptyList()
    return all.filter { it.runId == latestRun }.map {
        it to if (it.state == "PENDING" || it.state == "EXPIRED") QuestionAnswerMode.LATE else QuestionAnswerMode.NONE
    }
}

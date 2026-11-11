package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.SteerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Routes control input to the exact active turn without acquiring another execution owner. */
internal suspend fun postRunInput(
    registry: ActiveRunRegistry,
    sessionId: String,
    runId: String,
    requestId: String,
    text: String,
    questionId: String?,
): SteerResult = withContext(Dispatchers.IO) {
    val record = registry.getExact(sessionId, runId) ?: return@withContext SteerResult.NOT_RUNNING
    if (!record.isJobActive) return@withContext SteerResult.NOT_RUNNING
    if (questionId == null) return@withContext record.thread.steer(text, cc.noharry.agent.SteerOptions(requestId = requestId))
    val question = record.thread.pendingRequests().filterIsInstance<cc.noharry.agent.Question>().firstOrNull {
        it.turnId == runId && it.async && it.status == cc.noharry.agent.ItemStatus.IN_PROGRESS &&
            (it.id == questionId || it.id == "$runId:question:$questionId")
    } ?: return@withContext SteerResult.QUESTION_NOT_FOUND
    record.thread.steer(text, cc.noharry.agent.SteerOptions(requestId = requestId, answering = question.id))
}

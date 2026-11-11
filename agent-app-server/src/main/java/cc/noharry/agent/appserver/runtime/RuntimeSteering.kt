package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.AgentThread
import cc.noharry.agent.SteerOptions
import cc.noharry.agent.SteerResult
import cc.noharry.agent.appserver.session.TranscriptStore

internal data class SteeringPostUiResult(val status: String, val transcript: SteeringTranscript, val runEnded: Boolean = false)

internal sealed interface SteeringTranscript {
    data class Runtime(val title: String, val body: String) : SteeringTranscript
    data class Status(val message: String) : SteeringTranscript
    data class Error(val title: String, val message: String) : SteeringTranscript
}

internal suspend fun postRuntimeSteering(
    thread: AgentThread,
    text: String,
    title: String = STEERING_TITLE,
    shown: String = text,
    requestId: String? = null,
): SteeringPostUiResult = try {
    when (thread.steer(text, SteerOptions(requestId = requestId))) {
        SteerResult.ACCEPTED -> SteeringPostUiResult("Steering sent", SteeringTranscript.Runtime(title, shown.take(MAX_STEERING_PREVIEW_CHARS)))
        SteerResult.NOT_RUNNING -> SteeringPostUiResult("Steering inactive", SteeringTranscript.Status("The turn is no longer active."), runEnded = true)
        SteerResult.QUESTION_NOT_FOUND -> SteeringPostUiResult("Input rejected", SteeringTranscript.Error("Input", "QUESTION_NOT_FOUND"))
        SteerResult.EMPTY_INPUT -> SteeringPostUiResult("Input rejected", SteeringTranscript.Error("Input", "EMPTY_INPUT"))
        SteerResult.QUEUE_FULL -> SteeringPostUiResult("Input rejected", SteeringTranscript.Error("Input", "QUEUE_FULL"))
        SteerResult.REQUEST_ID_CONFLICT -> SteeringPostUiResult("Input rejected", SteeringTranscript.Error("Input", "REQUEST_ID_CONFLICT"))
    }
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (error: Exception) {
    SteeringPostUiResult("Steering failed", SteeringTranscript.Error("Steering", error.message ?: "Unable to post steering input."))
}

private const val MAX_STEERING_PREVIEW_CHARS = 240
private const val STEERING_TITLE = "Runtime steering input"
internal const val DELEGATION_REPORT_WAIT_MS = 120_000L
internal const val DELEGATION_REPORT_POLL_MS = 1_000L
internal const val REPORT_START_WAIT_MS = 3_000L
internal const val RESULT_SETTLE_MS = 1_500L

/** Records a control outcome in the transcript. */
internal fun TranscriptStore.append(entry: SteeringTranscript) {
    when (entry) {
        is SteeringTranscript.Runtime -> appendRuntime(entry.title, entry.body)
        is SteeringTranscript.Status -> appendStatus(entry.message)
        is SteeringTranscript.Error -> appendError(entry.title, entry.message)
    }
}

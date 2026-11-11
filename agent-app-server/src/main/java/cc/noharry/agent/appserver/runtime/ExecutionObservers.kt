package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.ThreadEvent
import kotlinx.coroutines.*

/** Independent subscriber jobs survive producer cancellation long enough to drain required logs. */
internal class ExecutionObservers(
    scope: CoroutineScope,
    private val runId: String,
    onEvent: (ThreadEvent) -> Unit,
    record: (ExecutionMessage) -> Unit,
) {
    private val workers = CoroutineScope(scope.coroutineContext + SupervisorJob())
    private val logs = ExecutionEvents.subscribe(runId, required = true)
    private val ui = ExecutionEvents.subscribe(runId)
    @Volatile private var failure: Throwable? = null
    private val logJob = workers.launch(Dispatchers.IO) {
        try { for (message in logs.events) record(message) } catch (error: Exception) { failure = error }
    }
    private val uiJob = workers.launch {
        try {
            for (message in ui.events) when (message) {
                is ExecutionMessage.Runtime -> onEvent(message.event)
                else -> Unit
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ExecutionEvents.publish(runId, ExecutionMessage.ObserverIssue("ui"))
        }
    }

    suspend fun closeAndJoin(drainUi: Boolean): Throwable? {
        logs.finish()
        if (drainUi) ui.finish() else ui.close()
        try {
            logJob.join()
            uiJob.join()
            return failure
        } finally {
            logs.close()
            ui.close()
            workers.cancel()
        }
    }
}

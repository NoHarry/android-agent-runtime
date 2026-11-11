package cc.noharry.agent.appserver.runtime
/**
 * Input adapters supply identity and presentation policy; the shared runner owns execution. A null [sessionId] runs
 * in the selected session (a spoken turn names only its request, see [SpokenRuns]).
 */
internal data class RunIntake(val requestId: String, val sessionId: String? = null, val external: Boolean = false)

/**
 * Runs asked for by voice (the overlay's wake, the console's mic, the ASR port). A spoken request gets a spoken
 * answer: their final reply is always played, partial and failed ones included, where a typed request's partial
 * reply is only spoken when it asks something. Bounded to the latest few.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
object SpokenRuns {
    private val ids = ArrayDeque<String>()

    /** A request id for a new spoken run, already recorded. */
    fun next(): String = RequestIds.next("debug").also { mark(it) }

    @Synchronized
    fun mark(runId: String) {
        ids.addLast(runId)
        while (ids.size > MAX_REMEMBERED) ids.removeFirst()
    }

    @Synchronized
    fun contains(runId: String): Boolean = runId in ids

    private const val MAX_REMEMBERED = 32
}

internal fun failExternalIntake(runId: String, message: String) {
    ExecutionEvents.publish(runId, ExecutionMessage.Failure(message))
    ExecutionEvents.publish(runId, ExecutionMessage.Ended)
    ExecutionEvents.closeRun(runId)
}

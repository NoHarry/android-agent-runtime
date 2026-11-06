package cc.noharry.agent.a2a.server

/**
 * Candidate bounded loopback server configuration. Use a trusted tunnel for remote access. [description] and
 * [skillDescription] go on the Agent Card; [messages] are the status texts callers see.
 */
data class A2aServerConfig(
    val port: Int = 8766,
    val name: String = "Android Runtime Agent",
    val maxActiveTasks: Int = 4,
    val maxRetainedTasks: Int = 64,
    val taskTimeoutMillis: Long = 600_000,
    val description: String = "Text requests, answered with brief tool progress and text.",
    val skillDescription: String = "Run a text request with the app's tools and answer in text.",
    val messages: A2aServerMessages = A2aServerMessages(),
) {
    init {
        require(port in 0..65535)
        require(name.isNotBlank())
        require(maxActiveTasks in 1..MAX_ACTIVE_TASKS)
        require(maxRetainedTasks in maxActiveTasks..MAX_RETAINED_TASKS)
        require(taskTimeoutMillis > 0)
    }

    private companion object {
        const val MAX_ACTIVE_TASKS = 16
        const val MAX_RETAINED_TASKS = 256
    }
}

/** What the server tells a caller about its task: English unless the host words it in its own language. */
data class A2aServerMessages(
    val accepted: String = "Text request received",
    val started: String = "Started",
    val timedOut: String = "The task timed out, or its output was read too slowly",
    val canceled: String = "The task was canceled",
    val interrupted: String = "Interrupted by a new task",
    val failed: String = "The task failed; the device's log has why",
    val noResult: String = "The run ended without a final result",
    val deliveryFailed: String = "The output connection closed, or the output grew too large",
)

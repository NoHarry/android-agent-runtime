package cc.noharry.agent.a2a.server

/** Candidate bounded loopback server configuration. Use a trusted tunnel for remote access. */
data class A2aServerConfig(
    val port: Int = 8766,
    val name: String = "Android Runtime Agent",
    val maxActiveTasks: Int = 4,
    val maxRetainedTasks: Int = 64,
    val taskTimeoutMillis: Long = 600_000,
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

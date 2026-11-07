@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent

/**
 * An embeddable agent contract available from the `agent-api` artifact.
 * The `agent-runtime` artifact supplies the `builder` companion extension for Kotlin hosts (import
 * `cc.noharry.agent.builder`); Java hosts use `AgentRuntimeBuilder.create()` from that artifact.
 */
interface AgentRuntime : AutoCloseable {
    fun startThread(options: ThreadOptions = ThreadOptions()): AgentThread
    /**
     * Reopens saved model history and visible items when storage is configured. Without storage, history lives only
     * in this runtime. Non-default options update the saved defaults used by future turns.
     */
    fun resumeThread(threadId: String, options: ThreadOptions = ThreadOptions()): AgentThread
    suspend fun listThreads(): List<ThreadInfo>
    /**
     * Deletes saved history and invalidates all existing handles to this conversation, even if its id is reused.
     * @throws IllegalStateException if called from that thread's active turn, including its completion hook.
     */
    suspend fun deleteThread(threadId: String)
    val diagnostics: RuntimeDiagnostics
    override fun close()

    companion object
}

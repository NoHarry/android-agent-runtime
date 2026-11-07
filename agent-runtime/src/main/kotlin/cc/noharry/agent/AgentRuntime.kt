@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent

import cc.noharry.agent.runtime.AgentRuntimeBuilder

/** An embeddable agent. Configure a model, then start or resume a conversation. */
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

    companion object {
        @JvmStatic fun builder(): AgentRuntimeBuilder = AgentRuntimeBuilder.create()
    }
}

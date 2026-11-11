@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.server

import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.AgentThread
import cc.noharry.agent.PendingRequest
import cc.noharry.agent.RuntimeDiagnostics
import cc.noharry.agent.SteerOptions
import cc.noharry.agent.SteerResult
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.ThreadInfo
import cc.noharry.agent.ThreadItem
import cc.noharry.agent.ThreadOptions
import cc.noharry.agent.ThreadStatus
import cc.noharry.agent.TurnInput
import cc.noharry.agent.TurnOptions
import cc.noharry.agent.TurnResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * A runtime that builds a new one ([build]) for each turn, as the app's own turns are built: a turn sees the plugins,
 * tool servers and settings of the moment it starts (a plugin switched off is gone from the next turn, one switched on
 * is there) and keeps its runtime until it ends. Threads are started, listed, read and deleted on one runtime built
 * when first needed, over the same storage, which never runs a turn. While a turn runs, its thread's interrupt, steering,
 * pending requests and a policy given to [resumeThread] go to that turn's runtime.
 */
internal class RuntimePerTurn(private val build: () -> AgentRuntime) : AgentRuntime {
    private val managing = lazy(build)
    private val manager: AgentRuntime by managing
    private val turns = ConcurrentHashMap<String, Turn>()

    private class Turn(val runtime: AgentRuntime, val thread: AgentThread)

    override fun startThread(options: ThreadOptions): AgentThread = ThreadPerTurn(manager.startThread(options).id)

    override fun resumeThread(threadId: String, options: ThreadOptions): AgentThread {
        turns[threadId]?.runtime?.resumeThread(threadId, options) ?: manager.resumeThread(threadId, options)
        return ThreadPerTurn(threadId)
    }

    override suspend fun listThreads(): List<ThreadInfo> = manager.listThreads()

    override suspend fun deleteThread(threadId: String) = manager.deleteThread(threadId)

    override val diagnostics: RuntimeDiagnostics get() = manager.diagnostics

    override fun close() {
        turns.values.forEach { runCatching { it.runtime.close() } }
        if (managing.isInitialized()) manager.close()
    }

    private inner class ThreadPerTurn(override val id: String) : AgentThread {
        private val running: AgentThread? get() = turns[id]?.thread

        override fun runStreamed(input: TurnInput, options: TurnOptions): Flow<ThreadEvent> = flow {
            build().use { runtime ->
                val turn = Turn(runtime, runtime.resumeThread(id))
                turns[id] = turn
                try {
                    emitAll(turn.thread.runStreamed(input, options))
                } finally {
                    turns.remove(id, turn)
                }
            }
        }.flowOn(Dispatchers.IO)

        override suspend fun run(input: TurnInput, options: TurnOptions): TurnResult = withContext(Dispatchers.IO) {
            build().use { runtime ->
                val turn = Turn(runtime, runtime.resumeThread(id))
                turns[id] = turn
                try {
                    turn.thread.run(input, options)
                } finally {
                    turns.remove(id, turn)
                }
            }
        }

        override suspend fun steer(text: String, options: SteerOptions): SteerResult = running?.steer(text, options) ?: SteerResult.NOT_RUNNING

        override suspend fun interrupt() {
            running?.interrupt()
        }

        override suspend fun status(): ThreadStatus = (running ?: manager.resumeThread(id)).status()

        override suspend fun pendingRequests(): List<PendingRequest> = (running ?: manager.resumeThread(id)).pendingRequests()

        override suspend fun items(afterTurnId: String?): List<ThreadItem> = (running ?: manager.resumeThread(id)).items(afterTurnId)
    }
}

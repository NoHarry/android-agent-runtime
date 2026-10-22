package cc.noharry.agent.kernel.core

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ToolExecutionLimiter(
    maxConcurrentCalls: Int = DEFAULT_MAX_CONCURRENT_TOOL_CALLS,
) {
    private val semaphore: Semaphore

    init {
        require(maxConcurrentCalls > 0) { "maxConcurrentCalls must be positive." }
        semaphore = Semaphore(maxConcurrentCalls)
    }

    suspend fun <T> withPermit(action: suspend () -> T): T =
        semaphore.withPermit { action() }
}

const val DEFAULT_MAX_CONCURRENT_TOOL_CALLS = 4

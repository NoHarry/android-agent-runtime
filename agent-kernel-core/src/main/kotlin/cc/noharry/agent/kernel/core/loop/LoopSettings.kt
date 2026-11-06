package cc.noharry.agent.kernel.core.loop

import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.NarrationCheck
import cc.noharry.agent.kernel.api.ServiceAnswerCheck
import cc.noharry.agent.kernel.core.context.RuntimeContextHooks
import cc.noharry.agent.kernel.core.tools.RuntimeServiceAnswerCheck
import cc.noharry.agent.kernel.core.memory.RuntimeMemoryRetriever

/**
 * What a host sets of the loop: what it judges about content, worded for the host's users' language and its domain
 * (which final answers only say what the agent will do, [narration]; which lessons from earlier runs fit a request,
 * [memory]; which failed tool results are a service's answer, [serviceAnswer]), and how long the context providers
 * have, together, to add to a run's context before the first model call ([contextTimeoutMillis]; one that is late is
 * left out).
 */
@ExperimentalAgentRuntimeApi
class LoopSettings @JvmOverloads constructor(
    val narration: NarrationCheck = RuntimeNarrationCheck,
    val memory: RuntimeMemoryRetriever = RuntimeMemoryRetriever(),
    val serviceAnswer: ServiceAnswerCheck = RuntimeServiceAnswerCheck,
    val contextTimeoutMillis: Long = RuntimeContextHooks.CONTEXT_AGGREGATE_TIMEOUT_MS,
) {
    init {
        require(contextTimeoutMillis in 1..MAX_CONTEXT_TIMEOUT_MILLIS) { "contextTimeoutMillis must be 1 to $MAX_CONTEXT_TIMEOUT_MILLIS." }
    }
}

/** The longest the context providers may take together: the first model call waits for them. */
const val MAX_CONTEXT_TIMEOUT_MILLIS = 60_000L

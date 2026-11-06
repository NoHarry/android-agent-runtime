package cc.noharry.agent.kernel.core.loop

import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.NarrationCheck
import cc.noharry.agent.kernel.core.memory.RuntimeMemoryRetriever

/**
 * What the loop judges about content, which a host words for its users' language and its domain: which final answers
 * only say what the agent will do ([narration]), and which lessons from earlier runs fit a request ([memory]).
 */
@ExperimentalAgentRuntimeApi
class LoopJudgements(
    val narration: NarrationCheck = RuntimeNarrationCheck,
    val memory: RuntimeMemoryRetriever = RuntimeMemoryRetriever(),
)

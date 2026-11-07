package cc.noharry.agent.minimal

import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.runtime.AgentRuntimeBuilder

/** A minimal host: supply a model, start a thread, and run a turn. */
object MinimalAgent {
    fun builder(model: ModelClient): AgentRuntimeBuilder = AgentRuntime.builder().model(model)
    fun build(model: ModelClient): AgentRuntime = builder(model).build()
}

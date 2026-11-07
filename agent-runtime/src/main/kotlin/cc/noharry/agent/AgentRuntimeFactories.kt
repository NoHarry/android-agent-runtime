@file:JvmName("AgentRuntimeFactories")

package cc.noharry.agent

import cc.noharry.agent.runtime.AgentRuntimeBuilder

/** Creates the runtime implementation builder. Java hosts use [AgentRuntimeBuilder.create]. */
fun AgentRuntime.Companion.builder(): AgentRuntimeBuilder = AgentRuntimeBuilder.create()

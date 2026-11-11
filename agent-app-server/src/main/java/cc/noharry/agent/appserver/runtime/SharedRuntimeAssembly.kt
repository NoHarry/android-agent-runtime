@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import cc.noharry.agent.vfs.VfsFileSystem
import java.io.File

/** The runtime composition the sample builds every session on. */
internal fun runtimeAssembly(
    model: ModelClient,
    prompt: String,
    workspace: VfsFileSystem,
    eventLogRoot: File,
): AgentRuntimeBuilder = AgentRuntimeBuilder.create()
    .model(model)
    .instructions(prompt)
    .virtualWorkspace(workspace)
    .storage(eventLogRoot)
    .sessionEventLogRoot(eventLogRoot)
    .traceRedactor(appTraceRedactor)
    .sessionActivationInheritance()

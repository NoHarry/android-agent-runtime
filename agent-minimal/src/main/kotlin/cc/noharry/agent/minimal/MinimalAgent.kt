@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.minimal

import cc.noharry.agent.kernel.api.AgentPlugin
import cc.noharry.agent.kernel.api.AgentProfile
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.WorkspacePort
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.core.KernelRuntimeBuilder

/**
 * Official smallest runnable Agent profile backed by the same Kernel as every host application.
 *
 * The profile deliberately enables no MCP provider, exec engine, network tool, evidence pack, or
 * host hook. Applications extend it only by installing trusted plugins on the returned builder.
 */
@ExperimentalAgentRuntimeApi
object MinimalAgent {
    const val PROFILE_ID: String = "minimal"

    /** Explicit profile identity compiled through the single KernelRuntimeBuilder/ProfileCompiler path. */
    val PROFILE: AgentProfile = AgentProfile(PROFILE_ID)

    /** Creates a minimal Kernel builder with one required model and one [WorkspacePort]. */
    fun builder(
        modelClient: ModelClient,
        workspace: WorkspacePort,
        workspaceId: String = KernelRuntimeBuilder.DEFAULT_WORKSPACE_ID,
    ): KernelRuntimeBuilder =
        KernelRuntimeBuilder()
            .profile(PROFILE)
            .model(modelClient)
            .workspace(workspace)
            .workspaceId(workspaceId)

    /** Convenience build for hosts that already have their packaged plugin list. */
    fun build(
        modelClient: ModelClient,
        workspace: WorkspacePort,
        plugins: List<AgentPlugin> = emptyList(),
        workspaceId: String = KernelRuntimeBuilder.DEFAULT_WORKSPACE_ID,
    ): AgentRuntime {
        val builder = builder(modelClient, workspace, workspaceId)
        plugins.forEach(builder::plugin)
        return builder.build()
    }
}

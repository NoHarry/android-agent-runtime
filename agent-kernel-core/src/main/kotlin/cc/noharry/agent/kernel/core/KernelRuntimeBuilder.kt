@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.AgentCapabilities
import cc.noharry.agent.kernel.api.AgentPlugin
import cc.noharry.agent.kernel.api.AgentProfile
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.WorkspacePort
import cc.noharry.agent.kernel.api.model.ModelClient

/**
 * Single core builder that compiles profile, model, workspace, and plugins through
 * [ProfileCompiler]. Not a second protocol loop or Android facade.
 *
 * External [ModelClient] and [WorkspacePort] stay host-owned and are not closed by the
 * produced runtime. One builder instance produces at most one runtime: [build] is
 * one-shot and closes this builder for further use.
 */
@ExperimentalAgentRuntimeApi
class KernelRuntimeBuilder {
    private var profile: AgentProfile? = null
    private var modelClient: ModelClient? = null
    private var workspace: WorkspacePort? = null
    private var configuredWorkspaceId: String = DEFAULT_WORKSPACE_ID
    private val plugins = mutableListOf<AgentPlugin>()
    private val consumed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun profile(profile: AgentProfile): KernelRuntimeBuilder = apply {
        this.profile = profile
    }

    fun model(client: ModelClient): KernelRuntimeBuilder = apply {
        modelClient = client
    }

    fun workspace(workspace: WorkspacePort): KernelRuntimeBuilder = apply {
        this.workspace = workspace
    }

    fun workspaceId(workspaceId: String): KernelRuntimeBuilder = apply {
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank." }
        configuredWorkspaceId = workspaceId
    }

    fun plugin(plugin: AgentPlugin): KernelRuntimeBuilder = apply {
        plugins += plugin
    }

    fun build(): AgentRuntime {
        check(consumed.compareAndSet(false, true)) {
            RuntimeCompositionMessages.BUILDER_ALREADY_USED
        }
        val model = requireNotNull(modelClient) { RuntimeCompositionMessages.MISSING_MODEL }
        val selectedWorkspace = requireNotNull(workspace) { RuntimeCompositionMessages.MISSING_WORKSPACE }
        val compiledProfile = profile ?: AgentProfile(RuntimeCompositionMessages.MINIMAL_PROFILE_ID)
        val declarations = RuntimeCapabilityDeclarations.Collector().apply {
            modelClient(model)
            workspace(selectedWorkspace)
        }.snapshot()
        val compiled = ProfileCompiler().compile(
            compiledProfile,
            listOfNotNull(DirectCapabilityPlugin.from(declarations, ownResources = false)) +
                plugins.toList(),
        )
        return try {
            CanonicalCapabilityKeys.validate(compiled)
            rejectUnsupportedInstalledCapabilities(compiled)
            val resolvedModel = compiled.resolveShared(AgentCapabilities.MODEL_CLIENT)
                ?: error(RuntimeCompositionMessages.MISSING_MODEL)
            val resolvedWorkspace = compiled.resolveShared(AgentCapabilities.WORKSPACE)
                ?: error(RuntimeCompositionMessages.MISSING_WORKSPACE)
            CoreAgentRuntime(
                blueprint = compiled,
                modelClient = resolvedModel,
                workspace = resolvedWorkspace,
                configuredWorkspaceId = configuredWorkspaceId,
            )
        } catch (error: Throwable) {
            compiled.close()
            throw error
        }
    }

    companion object {
        const val DEFAULT_WORKSPACE_ID: String = "default"
    }
}

private fun rejectUnsupportedInstalledCapabilities(blueprint: RuntimeBlueprint) {
    val unsupported = listOf(
        AgentCapabilities.RUNTIME_HOOK,
        AgentCapabilities.SESSION_EVENT_LOG,
    )
    blueprint.providedKeys().forEach { key ->
        if (unsupported.any { it === key }) {
            error("${RuntimeCompositionMessages.UNSUPPORTED_INSTALLED_CAPABILITY}: ${key.name}")
        }
    }
}

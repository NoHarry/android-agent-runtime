@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.skills
import cc.noharry.agent.appserver.runtime.outputContractSummary
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolPrefetchProvider
import cc.noharry.agent.spi.ToolPrefetchRequest
import cc.noharry.agent.spi.ToolPrefetchTrace
import cc.noharry.agent.spi.ToolProvider

/** Catalogue-only enrichment; tool execution, identity and permissions remain on the delegate. */
internal class SkillToolProvider(
    private val delegate: ToolProvider,
    private val workflows: List<WorkflowSkill>,
) : ToolProvider by delegate, ToolPrefetchProvider {
    override val providerConcurrency: ProviderConcurrencyDeclaration = delegate.providerConcurrency
    private val remote: ToolPrefetchProvider? get() = delegate as? ToolPrefetchProvider
    override val remotePrefetchEnabled: Boolean get() = remote?.remotePrefetchEnabled == true
    override val prefetchOnSearch: Boolean get() = remote?.prefetchOnSearch == true
    override val prefetchTimeoutMillis: Long get() = remote?.prefetchTimeoutMillis ?: 1L

    override suspend fun listTools(): List<ToolDescriptor> {
        val tools = delegate.listTools()
        val selected = compatibleWorkflowTools(workflows, tools).map { it.name }.toSet()
        return tools.map { tool ->
            if (tool.name !in selected) tool else tool.copy(
                description = tool.description + outputContractSummary(tool.outputSchemaJson),
            )
        }
    }

    override fun localToolNames(request: ToolPrefetchRequest): List<String> =
        (compatibleWorkflowTools(workflows, request.tools).map { it.name } + remote?.localToolNames(request).orEmpty())
            .distinct().take(request.maxTools)

    override suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace): List<String> =
        remote?.prefetchTools(request, trace).orEmpty()
}

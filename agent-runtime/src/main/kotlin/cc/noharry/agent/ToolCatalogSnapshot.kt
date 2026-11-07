@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent

import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.kernel.core.tools.ToolDomainIndexer
import cc.noharry.agent.kernel.core.tools.ToolProviderRegistry

/** A read-only view of the tools and areas offered by providers. */
@ExperimentalAgentRuntimeApi
data class ToolCatalogSnapshot(val tools: List<ToolCatalogEntry>, val domains: List<ToolCatalogDomain>)

@ExperimentalAgentRuntimeApi
data class ToolCatalogEntry(
    val provider: ToolProvider,
    val serverId: String,
    val name: String,
    val qualifiedName: String,
    val descriptor: ToolDescriptor,
)

@ExperimentalAgentRuntimeApi
data class ToolCatalogDomain(val id: String, val toolCount: Int, val summary: String)

internal suspend fun inspectToolCatalog(providers: List<ToolProvider>): ToolCatalogSnapshot {
    val refs = ToolProviderRegistry(providers).allTools(MaterializerContext())
    return ToolCatalogSnapshot(
        refs.map { ToolCatalogEntry(it.provider, it.serverId, it.originalToolName, it.qualifiedName, it.descriptor) },
        ToolDomainIndexer.index(refs).domains.map { ToolCatalogDomain(it.id, it.toolCount, it.summary) },
    )
}

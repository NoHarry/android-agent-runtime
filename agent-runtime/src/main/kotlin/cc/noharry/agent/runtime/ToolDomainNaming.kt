package cc.noharry.agent.runtime

import cc.noharry.agent.spi.ToolDescriptor

/**
 * Which area a tool belongs to, for the list of areas the prompt shows the model ([ToolDomainNaming]). The id goes into
 * the prompt as written; a blank one is `other`. It is called each time the prompt is assembled, possibly from several
 * runs at once: keep it quick and safe to call concurrently.
 */
fun interface ToolDomainNaming {
    fun domainOf(tool: ToolDomainTool): String
}

/** A tool catalog entry used only to assign a host-defined area. */
data class ToolDomainTool(
    val serverId: String,
    val originalToolName: String,
    val qualifiedName: String,
    val descriptor: ToolDescriptor,
)

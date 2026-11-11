package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.Serializable

/** What a configured MCP server stores: whether a token or a key is set and how many headers, never what they are. */
@Serializable
data class McpSecretsView(val token: Boolean = false, val headers: Int = 0, val query: Boolean = false) {
    val isEmpty: Boolean get() = !token && headers == 0 && !query
}

/** MCP status beyond the rows the state holds: provider names, what each server stores and media access. */
@Serializable
data class McpStatusView(
    /** The fixed providers' names in the app's language, by provider id. */
    val titles: Map<String, String> = emptyMap(),
    /** What each configured server stores, by server id. */
    val secrets: Map<String, McpSecretsView> = emptyMap(),
    /** The provider that sees what plays once notification access is granted, and whether it is. */
    val mediaProviderId: String = "",
    val mediaAccess: Boolean = false,
)

/** One tool an MCP provider exposes, for the preview list. */
@Serializable
data class McpToolPreview(
    val name: String,
    val title: String,
    val description: String,
)

/** A provider's tools, or why they could not be listed. */
@Serializable
data class McpToolsView(val tools: List<McpToolPreview>? = null, val unavailable: Finding? = null)

object McpRequests {
    /** Provider names, what each configured server stores and whether the media provider has notification access. */
    val STATUS = RequestMethod("mcp/status", Empty.serializer(), McpStatusView.serializer())

    /** Lists a provider's tools (a configured server is asked live). */
    val TOOLS = RequestMethod("mcp/tools", IdParams.serializer(), McpToolsView.serializer())
}

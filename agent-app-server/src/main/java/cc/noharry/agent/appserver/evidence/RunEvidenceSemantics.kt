package cc.noharry.agent.appserver.evidence

import cc.noharry.agent.appserver.runtime.AppHost
/**
 * Tools that count toward replay "business action" for a request the host says must act ([requiresBusinessAction]): any
 * service's tool, plus hosted web_search, so a question the web answers passes once searched (SearchActivity-backed
 * runs).
 */
internal fun RunToolEvidence.isMeaningfulBusinessTool(): Boolean =
    toolName.isNotBlank() && !toolName.isInternalToolName()

internal fun String.isInternalToolName(): Boolean =
    this in INTERNAL_DEBUG_TOOL_NAMES

/** Hosted / provider web_search — not MCP, but still a real user-visible capability. */
internal fun String.isHostedSearchToolName(): Boolean =
    equals(WEB_SEARCH_TOOL_NAME, ignoreCase = true)

/**
 * Whether [this] request asks for something a service must do, as the host judges it ([cc.noharry.agent.appserver.runtime.ReplayCheck.requiresAction]): a
 * delegation report is the device passing on another agent's result, never a request to act on.
 */
internal fun String.requiresBusinessAction(): Boolean = AppHost.additions.turnLabel(this) == null && AppHost.replayCheck.requiresAction(this)

private const val WEB_SEARCH_TOOL_NAME = "web_search"

/** Scaffold / harness tools: alone they do not satisfy a request that must act. */
private val INTERNAL_DEBUG_TOOL_NAMES = setOf(
    "finish",
    "list_dir",
    "read_file",
    "grep",
    "write_file",
    "edit_file",
    "update_todo",
    "search_tools",
    "exec_js",
    "unknown",
)

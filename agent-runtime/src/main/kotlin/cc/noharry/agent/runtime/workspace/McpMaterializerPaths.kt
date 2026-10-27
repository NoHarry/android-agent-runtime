package cc.noharry.agent.runtime.workspace
internal const val MCP_ROOT = "/mcp"
internal const val MCP_SERVERS = "$MCP_ROOT/servers"
internal const val MCP_SERVERS_INDEX = "$MCP_ROOT/servers.json"
internal const val MCP_ALL_TOOLS = "$MCP_ROOT/tools.json"
internal const val MCP_TOOLS_INDEX = "$MCP_ROOT/tools.index.json"
internal const val MCP_TOOLS = "$MCP_ROOT/tools"
internal const val MCP_RESOURCES = "$MCP_ROOT/resources"
internal const val MCP_RESOURCES_INDEX = "$MCP_RESOURCES/index.json"
internal const val MCP_PROMPTS = "$MCP_ROOT/prompts"
internal const val MCP_PROMPTS_INDEX = "$MCP_PROMPTS/index.json"

internal val SERVER_PATH = Regex("""^/mcp/servers/[^/]+$""")
internal val SERVER_TOOLS_PATH = Regex("""^/mcp/servers/[^/]+/tools\.json$""")
internal val TOOL_PATH = Regex("""^/mcp/tools/[^/]+$""")
internal val TOOL_CARD_PATH = Regex("""^/mcp/tools/[^/]+/card\.json$""")
internal val TOOL_SCHEMA_PATH = Regex("""^/mcp/tools/[^/]+/schema\.json$""")

// Context-driven runtime virtual FS paths (SPEC-CDR-002)
internal const val AGENTS_INDEX = "/AGENTS.md"
internal const val CONTEXT_ROOT = "/context"
internal const val CONTEXT_MEMORY = "$CONTEXT_ROOT/memory"
internal const val CONTEXT_SESSION = "$CONTEXT_ROOT/session"
internal const val CONTEXT_RUNS = "$CONTEXT_ROOT/runs"
internal const val CONTEXT_TODO = "$CONTEXT_ROOT/todo.json"
internal const val AGENTS_ROOT = "/.agents"
internal const val AGENTS_SKILLS_ROOT = "$AGENTS_ROOT/skills"

internal val MEMORY_INDEX_PATH = Regex("""^/context/memory/index\.json$""")
internal val MEMORY_SCENE_PATH = Regex("""^/context/memory/[^/]+/index\.json$""")
internal val SESSION_CURRENT_PATH = Regex("""^/context/session/current\.json$""")
internal val SESSION_HISTORY_PATH = Regex("""^/context/session/history/turn-\d+\.json$""")
internal val RUNS_INDEX_PATH = Regex("""^/context/runs/index\.json$""")
internal val RUN_SUMMARY_PATH = Regex("""^/context/runs/[^/]+/summary\.json$""")
internal val SKILL_INDEX_PATH = Regex("""^/\.agents/skills/index\.json$""")
internal val SKILL_MD_PATH = Regex("""^/\.agents/skills/[^/]+/SKILL\.md$""")
internal val SKILL_DIR_PATH = Regex("""^/\.agents/skills/[^/]+$""")

internal fun isMcpPath(path: String): Boolean =
    path == MCP_ROOT || path.startsWith("$MCP_ROOT/")

internal fun isContextPath(path: String): Boolean =
    path == CONTEXT_ROOT || path.startsWith("$CONTEXT_ROOT/")

internal fun isSkillPath(path: String): Boolean =
    path == AGENTS_ROOT ||
        path.startsWith("$AGENTS_ROOT/")

internal fun isContextMaterializedPath(path: String): Boolean =
    path == AGENTS_INDEX || isContextPath(path) || isSkillPath(path)

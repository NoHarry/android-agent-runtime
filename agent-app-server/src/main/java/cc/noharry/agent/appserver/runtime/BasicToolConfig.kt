package cc.noharry.agent.appserver.runtime
import android.content.Context
import androidx.annotation.StringRes
import cc.noharry.agent.appserver.R
import cc.noharry.agent.appserver.skills.DEBUG_SUBAGENT_TOOL_NAMES

internal data class BasicTool(
    val name: String,
    /** What the settings page says the tool does; the model reads the tool's own description, not this. */
    @StringRes val description: Int,
    val enabled: Boolean = true,
    val required: Boolean = false,
)

/**
 * The native tools the host offers ([BuiltInFeatures]), each as the person switched it. A tool of a feature the host
 * left off is not among them: it has no row and cannot be switched on.
 */
internal fun loadBasicTools(context: Context): List<BasicTool> {
    val preferences = context.getSharedPreferences(BASIC_TOOL_PREFS, Context.MODE_PRIVATE)
    return BASIC_TOOLS.filter { AppHost.builtIns.offersTool(it.name) }
        .map { it.copy(enabled = it.required || preferences.getBoolean(it.name, it.enabled)) }
}

internal fun saveBasicTool(context: Context, name: String, enabled: Boolean) {
    val tool = BASIC_TOOLS.singleOrNull { it.name == name && AppHost.builtIns.offersTool(name) } ?: error("Unknown basic tool.")
    require(!tool.required || enabled) { "finish is required by the runtime protocol." }
    context.getSharedPreferences(BASIC_TOOL_PREFS, Context.MODE_PRIVATE).edit().putBoolean(name, enabled).apply()
}

/** Those of [tools] the person switched off. */
internal fun disabledBasicTools(tools: List<BasicTool>): Set<String> =
    tools.filter { !it.enabled && !it.required }.mapTo(linkedSetOf()) { it.name }

/** The native tools a run leaves out: those of [tools] switched off, and every one the host does not offer. */
internal fun runtimeDisabledTools(tools: List<BasicTool>): Set<String> =
    disabledBasicTools(tools) + BASIC_TOOLS.map { it.name }.filterNot { name -> tools.any { it.name == name } }

internal fun basicToolPrompt(tools: List<BasicTool>): String {
    // exec_js is off by default and absent from the tool list; naming it would only invite script plans and
    // spend prompt budget on every request. A tool the host does not offer is not in [tools], so never named.
    val disabled = disabledBasicTools(tools) - EXEC_TOOL_NAME
    if (disabled.isEmpty()) return ""
    return "Host tool configuration for this request: disabled native tools: ${disabled.joinToString()}. " +
        "Use only advertised tools. Skill recipes apply only to available tools. " +
        "If update_todo is unavailable, track progress in reasoning without calling it. " +
        "If discovery or another capability is unavailable, use available alternatives or report the limitation."
}

/** Whether the host offers the native tool [name]: those of a feature only while it is on, the rest always. */
internal fun BuiltInFeatures.offersTool(name: String): Boolean = when (name) {
    "write_file", "edit_file" -> fileWriting
    "web_fetch", "web_search" -> web
    EXEC_TOOL_NAME -> scripts
    in DEBUG_SUBAGENT_TOOL_NAMES -> subagents
    else -> true
}

private const val EXEC_TOOL_NAME = "exec_js"

private const val BASIC_TOOL_PREFS = "app_server_basic_tools"
private val BASIC_TOOLS = listOf(
    BasicTool("exec_js", R.string.runtime_tool_exec_js, enabled = false),
    BasicTool("search_tools", R.string.runtime_tool_search_tools),
    BasicTool("list_dir", R.string.runtime_tool_list_dir),
    BasicTool("read_file", R.string.runtime_tool_read_file),
    BasicTool("grep", R.string.runtime_tool_grep),
    BasicTool("write_file", R.string.runtime_tool_write_file),
    BasicTool("edit_file", R.string.runtime_tool_edit_file),
    BasicTool("update_todo", R.string.runtime_tool_update_todo),
    BasicTool("ask_async", R.string.runtime_tool_ask_async),
    BasicTool("ask", R.string.runtime_tool_ask),
    BasicTool("web_fetch", R.string.runtime_tool_web_fetch),
    BasicTool("web_search", R.string.runtime_tool_web_search),
    BasicTool("spawn_agent", R.string.runtime_tool_spawn_agent),
    BasicTool("wait_agent", R.string.runtime_tool_wait_agent),
    BasicTool("cancel_agent", R.string.runtime_tool_cancel_agent),
    BasicTool("finish", R.string.runtime_tool_finish, required = true),
)

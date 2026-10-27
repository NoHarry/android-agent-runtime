package cc.noharry.agent.kernel.core.tools
import cc.noharry.agent.kernel.api.model.ModelTool
import cc.noharry.agent.kernel.core.loop.INTERACTION_TOOLS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

val NATIVE_READ_TOOLS = listOf(
    ModelTool(
        name = "list_dir",
        schemaJson = """
            {
              "type": "object",
              "description": "List virtual workspace entries under a readable context path.",
              "additionalProperties": false,
              "properties": {
                "path": {"type": "string", "description": "Virtual directory path to list."},
                "page_size": {"type": "integer", "minimum": 1, "description": "Maximum number of entries to return."}
              }
            }
        """.trimIndent(),
    ),
    ModelTool(
        name = "read_file",
        schemaJson = """
            {
              "type": "object",
              "description": "Read text content from an allowed virtual workspace path.",
              "additionalProperties": false,
              "required": ["path"],
              "properties": {
                "path": {"type": "string", "description": "Virtual file path to read."},
                "offset": {"type": "integer", "minimum": 0, "description": "Character offset to start reading from."},
                "max_chars": {"type": "integer", "minimum": 1, "description": "Maximum characters to return."}
              }
            }
        """.trimIndent(),
    ),
    ModelTool(
        name = "grep",
        schemaJson = """
            {
              "type": "object",
              "description": "Search allowed virtual workspace files for a text pattern.",
              "additionalProperties": false,
              "required": ["pattern"],
              "properties": {
                "pattern": {"type": "string", "description": "Plain text or supported pattern to search for."},
                "path": {"type": "string", "description": "Virtual path to limit the search scope."},
                "max_matches": {"type": "integer", "minimum": 1, "description": "Maximum number of matches to return."}
              }
            }
        """.trimIndent(),
    ),
    ModelTool(
        name = "search_tools",
        schemaJson = """
            {
              "type": "object",
              "description": "Search available backend (MCP) tools by keyword. Returns matching tool names, descriptions, and schema paths. Call this before reading catalog files or guessing tool names.",
              "additionalProperties": false,
              "required": ["query"],
              "properties": {
                "query": {"type": "string", "description": "Keywords describing the capability you need, e.g. 'navigate around search parking' or 'play music singer'."},
                "max_results": {"type": "integer", "minimum": 1, "default": 10, "description": "Maximum number of matching tools to return."}
              }
            }
        """.trimIndent(),
    ),
)

val NATIVE_WRITE_TOOLS = listOf(
    ModelTool(
        name = "write_file",
        schemaJson = """
            {
              "type": "object",
              "description": "Write text content to an allowed virtual workspace path.",
              "additionalProperties": false,
              "required": ["path", "content"],
              "properties": {
                "path": {"type": "string", "description": "Virtual file path to write."},
                "content": {"type": "string", "description": "Text content to write."},
                "mode": {"type": "string", "enum": ["create", "create_new", "overwrite", "append", "atomic_replace"], "description": "Write behavior for existing files."}
              }
            }
        """.trimIndent(),
    ),
    ModelTool(
        name = "edit_file",
        schemaJson = """
            {
              "type": "object",
              "description": "Replace text within an allowed virtual workspace file.",
              "additionalProperties": false,
              "required": ["path", "old_string", "new_string"],
              "properties": {
                "path": {"type": "string", "description": "Virtual file path to edit."},
                "old_string": {"type": "string", "description": "Existing text that must be found."},
                "new_string": {"type": "string", "description": "Replacement text."},
                "replace_all": {"type": "boolean", "description": "Whether to replace all matches instead of one."}
              }
            }
        """.trimIndent(),
    ),
)

val TERMINAL_TOOLS = listOf(
    ModelTool(
        name = "ask",
        schemaJson = """
            {
              "type": "object",
              "description": "Ask the user for mandatory missing information, or for any answer you need before anything else useful can happen. Pauses this run until the host supplies the answer; do not guess missing action parameters. Only for optional clarification while other work can continue, prefer ask_async and keep working.",
              "additionalProperties": false,
              "required": ["question"],
              "properties": {
                "question": {"type": "string", "minLength": 1, "maxLength": 4096, "description": "The specific question shown to the user."}
              }
            }
        """.trimIndent(),
    ),
    ModelTool(
        name = "finish",
        schemaJson = """
            {
              "type": "object",
              "description": "Finish the runtime loop exactly once with the final response to the user; response 必须是直接展示给用户的完整最终答复，不是过程旁白；finish 调用后不再有后续输出机会。",
              "additionalProperties": false,
              "required": ["response"],
              "properties": {
                "response": {"type": "string", "description": "response 必须是直接展示给用户的完整最终答复，不是过程旁白；finish 调用后不再有后续输出机会。"},
                "outcome": {"type": "string", "enum": ["success", "partial", "blocked"], "description": "Optional completion request. Omitted outcome is a legacy success request."}
              }
            }
        """.trimIndent(),
    ),
)

val NATIVE_TODO_TOOLS = listOf(
    ModelTool(
        name = "update_todo",
        schemaJson = """
            {
              "type": "object",
              "description": "Update the task checklist for the current session alongside related backend or workspace tool calls in the same round. The tool result echoes the full normalized todo list for later rounds.",
              "additionalProperties": false,
              "required": ["todos"],
              "properties": {
                "todos": {
                  "type": "array",
                  "description": "Full replacement of the current todo list.",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["content", "status"],
                    "properties": {
                      "content": {"type": "string", "description": "Task description."},
                      "status": {"type": "string", "enum": ["pending", "in_progress", "completed"],
                                 "description": "pending=not started; in_progress=working now; completed=done."}
                    }
                  }
                }
              }
            }
        """.trimIndent(),
    ),
)

val NATIVE_NETWORK_TOOLS = listOf(
    ModelTool(
        name = "web_fetch",
        schemaJson = """
            {
              "type": "object",
              "description": "Fetch the content of a specific known public HTTP(S) URL through the runtime-visible native network tool. Private and loopback addresses are rejected. Do NOT use this tool to perform web searches; use web_search, or the provider's web search capability, if available.",
              "additionalProperties": false,
              "required": ["url"],
              "properties": {
                "url": {"type": "string", "description": "Absolute http or https URL to fetch."},
                "max_bytes": {"type": "integer", "minimum": 1, "description": "Maximum response bytes to return before truncating."}
              }
            }
        """.trimIndent(),
    ),
)

/**
 * A web search the host provides (e.g. a provider's search API) whose results the model and the event log both see,
 * unlike a provider's server-side search. Offered only when the host configures one.
 */
val NATIVE_SEARCH_TOOLS = listOf(
    ModelTool(
        name = "web_search",
        schemaJson = """
            {
              "type": "object",
              "description": "Search the web. Returns results with title, url, site, date and a summary of each page. Search with short, specific queries in the user's language; search again with other words when the results miss. Use web_fetch on a result's url for the whole page or its picture. Cite what you use.",
              "additionalProperties": false,
              "required": ["query"],
              "properties": {
                "query": {"type": "string", "description": "What to search for, 1 to 128 characters. Operators such as site:example.com, \"exact words\" and after:2026-10-01 work."},
                "count": {"type": "integer", "minimum": 1, "maximum": 10, "description": "How many results, default 5."}
              }
            }
        """.trimIndent(),
    ),
)

val NATIVE_EXEC_TOOLS = listOf(
    ModelTool(
        name = "exec_js",
        schemaJson = """
            {
              "type": "object",
              "description": "Run a bounded JavaScript code-mode script through the host-provided exec engine. Nested tools.* calls are routed back through runtime hook, policy, confirmation, and loop budget.",
              "additionalProperties": false,
              "required": ["code"],
              "properties": {
                "code": {"type": "string", "description": "JavaScript source to execute in the sandboxed optional exec engine."},
                "timeout_ms": {"type": "integer", "minimum": 1, "description": "Optional timeout requested for this exec."},
                "output_token_budget": {"type": "integer", "minimum": 1, "description": "Optional output token budget for script output."}
              }
            }
        """.trimIndent(),
    ),
)

/**
 * Background subagents (after dsh's subagent tool and Codex's spawn_agent/wait_agent). Offered only when the host
 * configures subagents; they run as read-only native tools, so several spawns in one round start together.
 */
val NATIVE_SUBAGENT_TOOLS = listOf(
    ModelTool(
        name = "spawn_agent",
        schemaJson = """
            {
              "type": "object",
              "description": "Start a subagent in the background for independent, time-consuming work that can run in parallel, such as several lookups or web searches that each take many steps. It returns an agent id at once and works on its own. You decide whether to delegate: handle simple requests and device control yourself. Background or parallel work goes here rather than to an external agent, unless the user names that agent. Start several in one turn to run them in parallel. The subagent does not see this conversation, so give it a complete, self-contained task. It cannot talk to the user and, unless the host allows it, cannot control the device. Collect its result with wait_agent; a result that arrives after your turn has ended comes back to you as a later message, so you may answer the user first.",
              "additionalProperties": false,
              "required": ["task"],
              "properties": {
                "task": {"type": "string", "description": "The complete task: every detail the subagent needs and what it should return."},
                "label": {"type": "string", "description": "A few words naming the task, shown to the user."}
              }
            }
        """.trimIndent(),
    ),
    ModelTool(
        name = "wait_agent",
        schemaJson = """
            {
              "type": "object",
              "description": "Wait for your subagents. Returns as soon as one finishes or stops making progress, or when the timeout passes, with each agent's status: running (with its latest activity), stalled, completed (with its result), failed, cancelled or timed_out. Each return is a status update; call again to keep waiting, or cancel_agent a stalled one. If the user should not wait, answer instead: later results come back as a message.",
              "additionalProperties": false,
              "properties": {
                "ids": {"type": "array", "items": {"type": "string"}, "description": "Agent ids to wait for; omit for every unfinished agent of this conversation."},
                "timeout_seconds": {"type": "integer", "minimum": 0, "maximum": 120, "description": "How long to wait, default 30; 0 only reports status."}
              }
            }
        """.trimIndent(),
    ),
    ModelTool(
        name = "cancel_agent",
        schemaJson = """
            {
              "type": "object",
              "description": "Stop a subagent that is no longer needed or is stuck.",
              "additionalProperties": false,
              "required": ["id"],
              "properties": {"id": {"type": "string", "description": "The agent id spawn_agent returned."}}
            }
        """.trimIndent(),
    ),
)

/** A kind of subagent the host defines, offered as spawn_agent's `agent`: its own instructions go with the task. */
data class NativeSubagentType(val name: String, val description: String)

/**
 * The subagent tools, with spawn_agent's `agent` naming [types] when there are any: each with what it is for, so the
 * model picks one for that kind of work and writes only the specifics into the task.
 */
fun nativeSubagentTools(types: List<NativeSubagentType>): List<ModelTool> {
    if (types.isEmpty()) return NATIVE_SUBAGENT_TOOLS
    return NATIVE_SUBAGENT_TOOLS.map { tool ->
        if (tool.name != SPAWN_AGENT_TOOL_NAME) return@map tool
        val schema = Json.parseToJsonElement(tool.schemaJson).jsonObject
        val listed = types.joinToString("; ") { "${it.name}: ${it.description}" }
        val description = schema["description"]?.jsonPrimitive?.content.orEmpty() +
            " Agent types, each with its own instructions for that kind of work (set agent and give only the specifics in the task): $listed."
        val properties = schema["properties"]?.jsonObject.orEmpty() + ("agent" to buildJsonObject {
            put("type", "string")
            putJsonArray("enum") { types.forEach { add(it.name) } }
            put("description", "The agent type for this work; omit for a general subagent.")
        })
        ModelTool(tool.name, JsonObject(schema + mapOf("description" to JsonPrimitive(description), "properties" to JsonObject(properties))).toString())
    }
}

private const val SPAWN_AGENT_TOOL_NAME = "spawn_agent"

val NATIVE_TOOLS = NATIVE_READ_TOOLS + NATIVE_WRITE_TOOLS + NATIVE_NETWORK_TOOLS + NATIVE_SEARCH_TOOLS + NATIVE_EXEC_TOOLS +
    NATIVE_SUBAGENT_TOOLS + NATIVE_TODO_TOOLS + TERMINAL_TOOLS + INTERACTION_TOOLS

val WORKSPACE_TOOL_NAMES = (NATIVE_READ_TOOLS + NATIVE_WRITE_TOOLS).map { it.name }.toSet()

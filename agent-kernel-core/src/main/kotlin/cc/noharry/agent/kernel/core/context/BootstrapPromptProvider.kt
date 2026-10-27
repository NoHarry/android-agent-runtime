package cc.noharry.agent.kernel.core.context

import cc.noharry.agent.kernel.core.tools.ToolDomainIndex
data class BootstrapPrompt(
    val version: String,
    val text: String,
)

/**
 * Resolves the system prompt for each loop round.
 *
 * Static prompts (no scene/skills) use [SimpleBootstrapPromptResolver].
 * Context-driven prompts use [ContextBootstrapPromptResolver] which injects
 * L1/L2 layers per round.
 */
fun interface BootstrapPromptResolver {
    suspend fun resolve(scene: String?, sessionId: String?): BootstrapPrompt
}

class SimpleBootstrapPromptResolver(
    private val prompt: BootstrapPrompt,
) : BootstrapPromptResolver {
    override suspend fun resolve(scene: String?, sessionId: String?): BootstrapPrompt = prompt
}

class ContextBootstrapPromptResolver(
    private val hostPrompt: String?,
    private val skillRegistry: SkillCatalogPort?,
    private val contextCatalog: ContextCatalog = defaultContextCatalog(),
    private val sceneRules: Set<String> = emptySet(),
    private val toolDomainIndexProvider: suspend () -> ToolDomainIndex? = { null },
) : BootstrapPromptResolver {
    override suspend fun resolve(scene: String?, sessionId: String?): BootstrapPrompt =
        BootstrapPromptProvider.build(
            hostPrompt = hostPrompt,
            scene = scene,
            contextCatalog = contextCatalog,
            skillsCatalog = skillRegistry?.catalog(scene),
            sceneRules = sceneRules,
            toolDomainIndex = toolDomainIndexProvider(),
        )
}

fun defaultContextCatalog(): ContextCatalog = ContextCatalog(
    paths = listOf(
        ContextPathEntry("/AGENTS.md", "detailed index and rules"),
        ContextPathEntry("/mcp/tools/", "MCP tool catalog (prefer search_tools over reading this directly)"),
        ContextPathEntry("/context/memory/", "cross-run lessons by scene"),
        ContextPathEntry("/context/session/current.json", "current session history"),
        ContextPathEntry("/context/todo.json", "your task checklist"),
        ContextPathEntry("/.agents/skills/{name}/SKILL.md", "agent skill instructions"),
        ContextPathEntry("/sessions/", "durable session files exposed by the VFS workspace"),
        ContextPathEntry("/runs/", "durable run artifacts exposed by the VFS workspace"),
    ),
)

object BootstrapPromptProvider {
    fun build(
        hostPrompt: String? = null,
        scene: String? = null,
        contextCatalog: ContextCatalog? = null,
        skillsCatalog: SkillsCatalog? = null,
        sceneRules: Set<String> = emptySet(),
        toolDomainIndex: ToolDomainIndex? = null,
    ): BootstrapPrompt {
        hostPrompt?.takeIf { it.isNotBlank() }?.let(::validateHostSystemPrompt)
        val sceneSection = scene?.takeIf { it.isNotBlank() }?.let { buildSceneSection(it, sceneRules) }
        val contextSection = contextCatalog?.takeIf { it.paths.isNotEmpty() }?.toPromptSection()
        fun assemble(tools: String?, skills: String?): String = listOfNotNull(
            PROTOCOL_PROMPT, tools, contextSection, skills, sceneSection,
            hostPrompt?.takeIf { it.isNotBlank() }?.let { "Host instructions:\n$it" },
        ).joinToString(separator = "\n\n")

        val domains = toolDomainIndex?.takeIf { it.domains.isNotEmpty() }
        val skills = skillsCatalog?.takeIf { it.skills.isNotEmpty() }
        // The domain index and the skills catalog are routing hints whose size follows the device (a real device's
        // tool catalog split into many domains). Over the soft budget they are shortened step by step: domain
        // summaries go, then the smaller domains, then long skill descriptions. Whatever is left is sent as it is; like
        // Codex and Claude Code, the prompt's length never fails a run.
        val attempts = sequenceOf(
            domains?.toPromptSection() to skills?.toPromptSection(),
            domains?.toCompactPromptSection() to skills?.toPromptSection(),
            domains?.toCompactPromptSection(MAX_LISTED_DOMAINS) to skills?.toPromptSection(),
            domains?.toCompactPromptSection(MAX_LISTED_DOMAINS) to skills?.toPromptSection(MAX_SKILL_DESCRIPTION_CHARS),
        )
        val text = attempts.map { (tools, skillText) -> assemble(tools, skillText) }
            .firstOrNull { computeStaticBytesExcludingHost(it) <= STATIC_PROMPT_SOFT_BUDGET_BYTES }
            ?: assemble(domains?.toCompactPromptSection(MAX_LISTED_DOMAINS), skills?.toPromptSection(MAX_SKILL_DESCRIPTION_CHARS))
        return BootstrapPrompt(
            version = BOOTSTRAP_PROMPT_VERSION,
            text = text,
        )
    }
}

private fun buildSceneSection(scene: String, sceneRules: Set<String>): String =
    buildList {
        add("Current scene: $scene")
        if (scene in sceneRules) {
            add("Scene-specific runtime rules are applied by the runtime.")
        }
    }.joinToString("\n")

fun validateHostSystemPrompt(text: String) {
    require(text.toByteArray(Charsets.UTF_8).size <= MAX_HOST_SYSTEM_PROMPT_BYTES) {
        "systemPrompt must be at most 16 KiB."
    }
}

private fun computeStaticBytesExcludingHost(text: String): Int {
    val hostMarker = "\n\nHost instructions:\n"
    val hostIndex = text.indexOf(hostMarker)
    val staticPart = if (hostIndex >= 0) text.substring(0, hostIndex) else text
    return staticPart.toByteArray(Charsets.UTF_8).size
}

const val MAX_HOST_SYSTEM_PROMPT_BYTES = 16 * 1024

/** First line of the message that carries a host's run context (see NativeToolLoop's runContext). */
const val RUN_CONTEXT_HEADER = "From the app, not typed by the user: context for the request above."

fun validateRunContext(text: String) {
    require(text.toByteArray(Charsets.UTF_8).size <= MAX_HOST_SYSTEM_PROMPT_BYTES) {
        "runContext must be at most 16 KiB."
    }
}
/**
 * Soft budget for the prompt before the host instructions (~1536 tokens): above it the routing hints are shortened,
 * but the prompt is never refused. The parts written in code (protocol, context paths) are held under it by tests.
 */
const val STATIC_PROMPT_SOFT_BUDGET_BYTES = 6144
/** Over the budget, only the domains with the most tools are listed by name. */
const val MAX_LISTED_DOMAINS = 12
/** Over the budget even so, each skill keeps this much of its description. */
const val MAX_SKILL_DESCRIPTION_CHARS = 40

private const val BOOTSTRAP_PROMPT_VERSION = "context-driven-runtime-bootstrap-v9"

private val PROTOCOL_PROMPT = """
Model-driven harness protocol v1.
- Use the provided tools directly and keep backend tools as the primary work when they are present.
- Search with search_tools(query) to discover backend tools by keyword. Call matches marked callable directly; a match that comes with its schema is called through call_tool(name, arguments). Neither needs a schema.json read.
- Issue independent tool calls together in the same round.
- For multi-step tasks, maintain update_todo and emit it in the same round as the related backend or workspace calls, including the first useful business call.
- Prefer finish(response) as the explicit completion signal and call it exactly once when the useful result is ready. A finish with outcome success closes the remaining checklist items, so do not spend a round only to mark todos completed.
- Read skill or context files only when the compact catalog is insufficient for the current task.
- Workspace writes are limited to /scratch and /memory through write_file or edit_file.
- web_fetch returns extracted page text for HTML. For web search call https://www.bing.com/search?q={query}&format=rss first (XML results); do not refetch the same URL with a larger max_bytes.
- Read only the context you need for the current task; do not enumerate everything.
""".trimIndent()

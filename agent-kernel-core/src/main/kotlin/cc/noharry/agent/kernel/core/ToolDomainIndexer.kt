package cc.noharry.agent.kernel.core

/**
 * Derives a lightweight domain index from the current MCP tool snapshot.
 *
 * Domains are inferred purely from tool naming conventions so that dynamically
 * loaded tool sets (e.g. a vendor's MCP backend) are reflected without any hard-coded
 * prompt text. The index is injected into the system prompt as a routing hint:
 * the model learns *which domain to search in*, then uses `search_tools` for
 * precise tool discovery within that domain.
 *
 * Naming conventions recognized:
 * - `command_<domain>_<Action>` → domain = `<domain>`
 * - `mcp__<server>__<name>`     → domain = `<server>`
 * - anything else               → domain = `other`
 */
object ToolDomainIndexer {

    private val COMMAND_PREFIX = Regex("""^command_([a-zA-Z]+)_""")
    private val MCP_QUALIFIED_PREFIX = Regex("""^mcp__([a-zA-Z0-9]+)__""")

    /**
     * Groups [tools] into domains sorted by tool count descending.
     * Each domain carries a short [ToolDomain.summary] extracted from the
     * descriptions of its member tools.
     */
    fun index(tools: List<McpToolRef>): ToolDomainIndex {
        val grouped = LinkedHashMap<String, MutableList<McpToolRef>>()
        for (tool in tools) {
            val domain = extractDomain(tool)
            grouped.getOrPut(domain) { mutableListOf() }.add(tool)
        }
        val domains = grouped.entries
            .sortedByDescending { it.value.size }
            .map { (domainId, domainTools) ->
                ToolDomain(
                    id = domainId,
                    toolCount = domainTools.size,
                    summary = summarizeDomain(domainId, domainTools),
                    exampleTools = domainTools.take(3).map { it.originalToolName },
                )
            }
        return ToolDomainIndex(domains)
    }

    fun extractDomain(tool: McpToolRef): String {
        COMMAND_PREFIX.find(tool.originalToolName)?.let { return it.groupValues[1].lowercase() }
        MCP_QUALIFIED_PREFIX.find(tool.qualifiedName)?.let { return it.groupValues[1].lowercase() }
        return "other"
    }

    /**
     * Builds a concise "/"-separated summary from tool descriptions.
     *
     * Strategy: collect description tokens, keep the most informative ones
     * (longer Chinese phrases or distinct English words), de-duplicate, and
     * cap at 5 fragments so the prompt stays compact.
     */
    private fun summarizeDomain(domainId: String, tools: List<McpToolRef>): String {
        areaSummary(tools)?.let { return it }
        val fragments = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (tool in tools) {
            val desc = tool.descriptor.description.trim()
            if (desc.isNotEmpty() && seen.add(desc)) {
                fragments += extractSummaryFragment(desc)
            }
            if (fragments.size >= 6) break
        }
        val unique = fragments.distinct().take(5)
        return if (unique.isEmpty()) domainId else unique.joinToString(" / ")
    }

    /**
     * A backend whose tool names carry their own areas (`navigation_poi_search`, `system_application_app_launch`,
     * `home_lamp_ambient_set`): its areas, largest first, each with its most common sub-areas, e.g.
     * `system 87 (application/volume/…)`. Without it a 321-tool backend read as its first five tools (AiOT scans, a
     * mall, music favourites), and the model opened apps by tapping the screen instead of searching it. Null when the
     * names share no areas (fewer than three tools per area on average, or a single area), or lead with verbs as most
     * MCP servers name theirs (`get_weather`, `set_alarm`): "get 3 · set 3" says less than their descriptions.
     */
    private fun areaSummary(tools: List<McpToolRef>): String? {
        val words = tools.map { tool -> tool.originalToolName.split('_', '.').filter(String::isNotBlank) }.filter { it.size >= 2 }
        val areas = words.groupBy { it[0].lowercase() }.entries.sortedByDescending { it.value.size }
        if (words.size * 2 < tools.size || areas.size < 2 || areas.size * MIN_TOOLS_PER_AREA > words.size) return null
        if (areas.count { it.key in VERB_AREAS } * 2 >= areas.size) return null
        val listed = areas.take(MAX_AREAS).joinToString(" · ") { (area, names) ->
            val subAreas = names.map { it[1].lowercase() }.groupingBy { it }.eachCount().entries
                .sortedByDescending { it.value }.take(MAX_SUB_AREAS).joinToString("/") { it.key }
            "$area ${names.size} ($subAreas)"
        }
        return if (areas.size > MAX_AREAS) "$listed · …" else listed
    }

    private const val MIN_TOOLS_PER_AREA = 3
    private val VERB_AREAS = setOf(
        "get", "set", "list", "create", "update", "delete", "remove", "add", "search", "query", "find", "fetch", "read",
        "write", "open", "close", "start", "stop", "send", "check", "run", "do", "make", "edit", "cancel",
    )
    private const val MAX_AREAS = 6
    private const val MAX_SUB_AREAS = 5

    /**
     * Extracts a short representative fragment from a single tool description.
     *
     * For Chinese descriptions we keep the full short text (they are already
     * concise, e.g. "周边搜索导航POI信息"). For mixed/English text we take the
     * first few words. This intentionally avoids any NLP dependency.
     */
    private fun extractSummaryFragment(description: String): String {
        val trimmed = description.take(30)
        return if (trimmed.any { it.code in 0x4E00..0x9FFF }) {
            trimmed
        } else {
            trimmed.split(' ', '_').filter { it.isNotBlank() }.take(3).joinToString(" ")
        }
    }
}

data class ToolDomain(
    val id: String,
    val toolCount: Int,
    val summary: String,
    val exampleTools: List<String>,
)

data class ToolDomainIndex(
    val domains: List<ToolDomain>,
) {
    fun toPromptSection(): String {
        if (domains.isEmpty()) return ""
        val lines = domains.joinToString(separator = "\n") { domain ->
            "- ${domain.id} (${domain.toolCount} tools): ${domain.summary}"
        }
        return "Tool domains (use search_tools to discover specific tools by keyword):\n$lines"
    }

    /**
     * Names and tool counts only, at most [maxDomains] of them (the largest first): the form the bootstrap prompt
     * falls back to when the full index would push it over the static budget.
     */
    fun toCompactPromptSection(maxDomains: Int = Int.MAX_VALUE): String {
        if (domains.isEmpty()) return ""
        val largestFirst = domains.sortedByDescending { it.toolCount }
        val listed = largestFirst.take(maxDomains)
        val rest = largestFirst.drop(maxDomains)
        val lines = listed.joinToString(separator = "\n") { domain -> "- ${domain.id} (${domain.toolCount} tools)" }
        val more = if (rest.isEmpty()) "" else "\n- and ${rest.size} more domains (${rest.sumOf { it.toolCount }} tools)"
        return "Tool domains (use search_tools to discover specific tools by keyword):\n$lines$more"
    }
}

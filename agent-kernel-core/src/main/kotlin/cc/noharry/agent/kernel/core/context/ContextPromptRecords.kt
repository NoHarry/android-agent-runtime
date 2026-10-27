package cc.noharry.agent.kernel.core.context

import cc.noharry.agent.kernel.core.loop.completed
/**
 * Compact catalog of context paths injected into the L1 system prompt section.
 *
 * Each entry maps a virtual filesystem path to a one-line description so the model
 * knows what it can `read_file` without enumerating every path upfront.
 */
data class ContextCatalog(
    val paths: List<ContextPathEntry>,
) {
    fun toPromptSection(): String =
        if (paths.isEmpty()) {
            ""
        } else {
            paths.joinToString(prefix = "Context paths (read_file to access):\n", separator = "\n") { entry ->
                "- ${entry.path} — ${entry.description}"
            }
        }
}

data class ContextPathEntry(
    val path: String,
    val description: String,
)

/**
 * Compact skill catalog injected into the L2 system prompt section.
 *
 * Contains only name + one-line description. Full `SKILL.md` content is loaded
 * on demand via `read_file("/.agents/skills/{name}/SKILL.md")` (progressive disclosure).
 */
data class SkillsCatalog(
    val skills: List<SkillCatalogEntry>,
) {
    /** Each description is cut to [maxDescriptionChars] (with an ellipsis) when the full catalog does not fit. */
    fun toPromptSection(maxDescriptionChars: Int = Int.MAX_VALUE): String =
        if (skills.isEmpty()) {
            ""
        } else {
            skills.joinToString(
                prefix = "Available Skills:\n",
                separator = "\n",
                postfix = "\nRead /.agents/skills/{name}/SKILL.md only when the catalog description is insufficient; do not use a standalone skill-read round when useful backend or search_tools calls can run.",
            ) { entry ->
                val description = entry.description.takeIf { it.length <= maxDescriptionChars } ?: "${entry.description.take(maxDescriptionChars)}…"
                "- ${entry.name}: $description"
            }
        }
}

data class SkillCatalogEntry(
    val name: String,
    val description: String,
)

/**
 * Current todo state injected as a per-round runtime context message.
 *
 * This stays out of the system prompt so provider prefix caches can reuse the
 * static bootstrap prefix across rounds. All other context (memory, session,
 * skills full text) is accessed on-demand via virtual FS.
 */
data class TodoState(
    val sessionId: String,
    val items: List<TodoItem>,
    val updatedAtEpochMillis: Long,
) {
    fun toPromptSection(): String =
        if (items.isEmpty()) {
            "Todo: (none)"
        } else {
            items.joinToString(prefix = "Todo:\n", separator = "\n") { item ->
                "- [${item.status.symbol}] ${item.content} (${item.status.name})"
            }
        }
}

data class TodoItem(
    val content: String,
    val status: TodoStatus,
)

enum class TodoStatus(val symbol: String) {
    pending(" "),
    in_progress("~"),
    completed("x"),
    ;

    companion object {
        fun fromWire(value: String): TodoStatus =
            entries.firstOrNull { it.name == value.normalizeTodoStatusWire() }
                ?: throw IllegalArgumentException("Unknown todo status: $value")
    }
}

private fun String.normalizeTodoStatusWire(): String =
    trim().lowercase().replace("-", "_")

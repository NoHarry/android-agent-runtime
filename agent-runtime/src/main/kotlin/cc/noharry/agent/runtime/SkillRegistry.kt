package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.core.context.SkillCatalogEntry
import cc.noharry.agent.kernel.core.context.SkillCatalogPort
import cc.noharry.agent.kernel.core.context.SkillsCatalog
import cc.noharry.agent.runtime.workspace.AGENTS_SKILLS_ROOT
import cc.noharry.agent.vfs.VFS_ENTRY_DIR
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsListResult
import cc.noharry.agent.vfs.VfsReadResult

/**
 * Merges backend-provided and host-provided skills into a unified catalog.
 *
 * The compact catalog (name + description) is injected into the L2 system prompt.
 * Full `SKILL.md` content is loaded on demand via `read_file("/.agents/skills/{name}/SKILL.md")`
 * (progressive disclosure, SPEC-CDR-008).
 *
 * The catalog is cached after the first successful load (Q-CDR-004: first run
 * awaits `listSkills()`, subsequent runs read the cache).
 */
internal class SkillRegistry(
    hostSkills: List<HostSkill>,
    private val workspace: VfsFileSystem? = null,
) : SkillCatalogPort {
    private val hostSkillIndex: Map<String, HostSkill> = hostSkills.associateBy { it.name }
    private var cachedSkills: List<CachedSkill>? = null

    override suspend fun catalog(scene: String?): SkillsCatalog {
        cachedSkills?.let { return catalogForScene(it, scene) }
        val skills = (workspaceSkills() +
            hostSkillIndex.values.map { CachedSkill(it.name, it.description, it.scenes, it.skillMd) })
            .distinctBy { it.name }
        cachedSkills = skills
        return catalogForScene(skills, scene)
    }

    suspend fun skillContent(name: String, scene: String?): String? {
        val allowedNames = catalog(scene).skills.map { it.name }.toSet()
        if (name !in allowedNames) return null
        return cachedSkills.orEmpty()
            .firstOrNull { it.name == name && it.matchesScene(scene) }
            ?.skillMd
    }

    private suspend fun workspaceSkills(): List<CachedSkill> {
        val activeWorkspace = workspace ?: return emptyList()
        val entries = when (val listed = activeWorkspace.list(AGENTS_SKILLS_ROOT)) {
            is VfsListResult.Entries -> listed.entries
            else -> return emptyList()
        }
        return entries
            .filter { it.type == VFS_ENTRY_DIR }
            .mapNotNull { entry ->
                val content = activeWorkspace.read(skillPath(entry.name), offset = 0, maxBytes = MAX_SKILL_MD_BYTES)
                (content as? VfsReadResult.Found)?.let { found ->
                    cachedWorkspaceSkill(entry.name, found.text)
                }
            }
    }

    private fun catalogForScene(skills: List<CachedSkill>, scene: String?): SkillsCatalog =
        SkillsCatalog(
            skills
                .filter { skill -> skill.matchesScene(scene) }
                .map { SkillCatalogEntry(it.name, it.description) },
        )

    private fun CachedSkill.matchesScene(scene: String?): Boolean =
        scene == null || scenes.isEmpty() || scene in scenes
}

private data class CachedSkill(
    val name: String,
    val description: String,
    val scenes: List<String>,
    val skillMd: String?,
)

private fun cachedWorkspaceSkill(directoryName: String, skillMd: String): CachedSkill {
    val frontmatter = parseFrontmatter(skillMd)
    return CachedSkill(
        name = directoryName,
        description = frontmatter["description"]?.takeIf { it.isNotBlank() }
            ?: "Workspace skill at ${skillPath(directoryName)}",
        scenes = parseScenes(frontmatter["scenes"]),
        skillMd = skillMd,
    )
}

private fun parseFrontmatter(markdown: String): Map<String, String> {
    if (!markdown.startsWith(FRONTMATTER_MARKER)) return emptyMap()
    val end = markdown.indexOf(FRONTMATTER_END_MARKER, startIndex = FRONTMATTER_MARKER.length)
    if (end <= 0) return emptyMap()
    return markdown.substring(FRONTMATTER_MARKER.length, end)
        .lineSequence()
        // An indented line belongs to the key above it (metadata's entries): its `description` is not the skill's.
        .filter { line -> line.isNotEmpty() && !line.first().isWhitespace() }
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator < 0) return@mapNotNull null
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim().unquoted()
            key.takeIf { it.isNotBlank() }?.let { it to value }
        }
        .toMap()
}

/** A YAML scalar in double or single quotes, without them. */
private fun String.unquoted(): String =
    if (length >= 2 && (first() == '"' || first() == '\'') && last() == first()) substring(1, length - 1) else trim('"')

private fun parseScenes(value: String?): List<String> =
    value.orEmpty()
        .trim('[', ']')
        .split(',')
        .map { it.trim().trim('"') }
        .filter { it.isNotBlank() }

private fun skillPath(name: String): String = "$AGENTS_SKILLS_ROOT/$name/SKILL.md"

/** Host-provided skill definition (via `AgentRuntimeBuilder.skill(...)`). Candidate API. */
data class HostSkill(
    val name: String,
    val description: String,
    val scenes: List<String> = emptyList(),
    val skillMd: String,
)

private const val MAX_SKILL_MD_BYTES = 64 * 1024
private const val FRONTMATTER_MARKER = "---"
private const val FRONTMATTER_END_MARKER = "\n---"

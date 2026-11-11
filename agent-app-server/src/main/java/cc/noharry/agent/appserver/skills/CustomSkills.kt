package cc.noharry.agent.appserver.skills
import android.content.Context
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.lines
import cc.noharry.agent.appserver.protocol.listing
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.agent.appserver.runtime.modelText
import java.io.File

/**
 * Skills added on the device, typed in by the person: each is a SKILL.md under files/custom-skills/<name>/, checked by [checkCustomSkill] before it is saved. They load like
 * a built-in skill that is not preloaded: name and description in the skill catalog of the system prompt, the whole
 * file read when the model needs it (/.agents/skills/<name>/SKILL.md). On when added; Settings switches them as any other.
 */
internal object CustomSkills {
    fun load(context: Context): List<RuntimeSkill> = root(context).listFiles().orEmpty()
        .mapNotNull { dir -> File(dir, SKILL_FILE).takeIf(File::isFile) }
        .sortedBy { it.parentFile!!.name }
        .map { file ->
            val name = file.parentFile!!.name
            val description = checkCustomSkill(file.readText(Charsets.UTF_8), emptySet()).description.orEmpty()
            RuntimeSkill(name, name, description, assetPath = "", enabled = true, file = file)
        }

    fun text(context: Context, name: String): String? =
        File(root(context), "$name/$SKILL_FILE").takeIf(File::isFile)?.readText(Charsets.UTF_8)

    /** Names a new skill (or one renamed from [editing]) may not take: the built-in ones and the other custom ones. */
    internal fun takenNames(context: Context, editing: String?): Set<String> =
        builtinRuntimeSkillIds(context) + cc.noharry.agent.appserver.runtime.AppHost.additions.skills.map { it.name } +
            load(context).map { it.id }.filter { it != editing }

    /**
     * Saves [text] as a new skill, or in place of [editing] (renamed if its name changed); null when saved, else why
     * not.
     */
    fun save(context: Context, text: String, editing: String?): Finding? {
        val check = checkCustomSkill(text, takenNames(context, editing))
        if (check.errors.isNotEmpty()) return lines(check.errors.map { it.finding() })
        val name = checkNotNull(check.name)
        val dir = File(root(context), name)
        val saved = runCatching {
            dir.mkdirs()
            File(dir, SKILL_FILE).writeText(check.normalized, Charsets.UTF_8)
        }
        if (saved.isFailure) {
            return finding(FindingCode.SKILLS_CUSTOM_SAVE_FAILED, saved.exceptionOrNull()?.message.orEmpty())
        }
        if (editing != null && editing != name) {
            renameRuntimeSkillChoice(context, editing, name)
            File(root(context), editing).deleteRecursively()
        }
        return null
    }

    fun delete(context: Context, name: String): Boolean {
        if (name !in load(context).map { it.id }) return false
        forgetRuntimeSkillChoice(context, name)
        return File(root(context), name).deleteRecursively()
    }

    private fun root(context: Context) = File(context.filesDir, CUSTOM_SKILLS_DIR)
}

/** What [checkCustomSkill] found: [errors] keep a skill from being saved, [warnings] do not. */
internal data class SkillCheck(
    val name: String?,
    val description: String?,
    val errors: List<SkillIssue>,
    val warnings: List<SkillIssue>,
    /** The text as saved: LF line ends, no byte-order mark, one trailing newline. */
    val normalized: String,
)

/** One thing [checkCustomSkill] found, for the form: what it is and the values it is about, a list among them as one. */
internal class SkillIssue(val code: FindingCode, private vararg val args: Any) {
    fun finding(): Finding = Finding(code, more = args.map { arg -> (arg as? List<*>)?.let { listing(it.map { item -> text(item ?: "") }) } ?: text(arg) })
}

/**
 * Whether [text] is a SKILL.md the runtime reads as meant: the Agent Skills layout (a YAML header between `---` lines
 * with `name` and `description`, then the instructions), held to what this runtime's line-by-line header reader takes
 * (SkillRegistry: one `key: value` per line, double quotes at most), and named apart from [taken].
 */
internal fun checkCustomSkill(text: String, taken: Set<String>): SkillCheck {
    val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trimEnd() + "\n"
    if (text.isBlank()) {
        return SkillCheck(null, null, listOf(SkillIssue(FindingCode.SKILLS_CHECK_EMPTY)), emptyList(), normalized)
    }
    val errors = mutableListOf<SkillIssue>()
    val warnings = mutableListOf<SkillIssue>()
    if (normalized.toByteArray(Charsets.UTF_8).size > MAX_SKILL_BYTES) {
        errors += SkillIssue(FindingCode.SKILLS_CHECK_TOO_BIG)
    }
    val lines = normalized.lines()
    if (lines.first().trimEnd() != FENCE) {
        errors += SkillIssue(FindingCode.SKILLS_CHECK_FIRST_LINE)
        return SkillCheck(null, null, errors, warnings, normalized)
    }
    val close = lines.drop(1).indexOfFirst { it.trimEnd() == FENCE }.takeIf { it >= 0 }?.plus(1)
    if (close == null) {
        errors += SkillIssue(FindingCode.SKILLS_CHECK_UNCLOSED)
        return SkillCheck(null, null, errors, warnings, normalized)
    }
    val fields = readHeader(lines.subList(1, close), errors)
    val name = fields["name"]?.let { unquoted(it, "name", errors) }
    val validName = name?.let { checkName(it, taken, errors) }
    if (name == null) errors += SkillIssue(FindingCode.SKILLS_CHECK_NO_NAME)
    val description = fields["description"]?.let { unquoted(it, "description", errors) }
    checkDescription(description, errors, warnings)
    fields["metadata"]?.takeIf(String::isNotBlank)?.let { errors += SkillIssue(FindingCode.SKILLS_CHECK_METADATA) }
    fields["scenes"]?.takeUnless { SCENES.matches(it) }?.let { errors += SkillIssue(FindingCode.SKILLS_CHECK_SCENES) }
    val body = lines.drop(close + 1)
    if (body.all(String::isBlank)) errors += SkillIssue(FindingCode.SKILLS_CHECK_EMPTY_BODY)
    if (body.size > LONG_BODY_LINES) warnings += SkillIssue(FindingCode.SKILLS_CHECK_LONG_BODY, body.size, LONG_BODY_LINES)
    checkSections(body.joinToString("\n"), errors)
    return SkillCheck(validName, description, errors, warnings, normalized)
}

/** The header's top-level `key: value` lines; indented lines only under a key with no value (metadata's entries). */
private fun readHeader(header: List<String>, errors: MutableList<SkillIssue>): Map<String, String> {
    val fields = linkedMapOf<String, String>()
    var nested = false
    header.forEachIndexed { index, line ->
        val number = index + 2
        when {
            line.isBlank() || line.trimStart().startsWith("#") -> Unit
            line.first().isWhitespace() -> if (!nested) errors += SkillIssue(FindingCode.SKILLS_CHECK_INDENT, number)
            else -> {
                val match = FIELD.matchEntire(line)
                if (match == null) {
                    errors += SkillIssue(FindingCode.SKILLS_CHECK_NOT_FIELD, number)
                    nested = false
                    return@forEachIndexed
                }
                val (key, value) = match.destructured
                if (key in fields) errors += SkillIssue(FindingCode.SKILLS_CHECK_DUPLICATE, key)
                if (key !in HEADER_FIELDS) {
                    errors += SkillIssue(FindingCode.SKILLS_CHECK_UNKNOWN_FIELD, number, key, HEADER_FIELDS)
                }
                fields[key] = value.trim()
                nested = value.isBlank()
            }
        }
    }
    return fields
}

/** Double quotes may wrap a value; single ones stay in it for this runtime's header reader. */
private fun unquoted(value: String, key: String, errors: MutableList<SkillIssue>): String {
    if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) return value.substring(1, value.length - 1).trim()
    if (value.startsWith('\'')) errors += SkillIssue(FindingCode.SKILLS_CHECK_SINGLE_QUOTES, key)
    return value
}

private fun checkName(name: String, taken: Set<String>, errors: MutableList<SkillIssue>): String? {
    when {
        name.isEmpty() -> errors += SkillIssue(FindingCode.SKILLS_CHECK_NAME_EMPTY)
        name.length > MAX_NAME_CHARS || !NAME.matches(name) ->
            errors += SkillIssue(FindingCode.SKILLS_CHECK_NAME_FORMAT, name, MAX_NAME_CHARS)
        name in taken -> errors += SkillIssue(FindingCode.SKILLS_CHECK_NAME_TAKEN, name)
        else -> return name
    }
    return null
}

private fun checkDescription(
    description: String?,
    errors: MutableList<SkillIssue>,
    warnings: MutableList<SkillIssue>,
) {
    when {
        description == null -> errors += SkillIssue(FindingCode.SKILLS_CHECK_NO_DESCRIPTION)
        description.isEmpty() || description in BLOCK_SCALARS ->
            errors += SkillIssue(FindingCode.SKILLS_CHECK_DESCRIPTION_LINE)
        description.length > MAX_DESCRIPTION_CHARS ->
            errors += SkillIssue(FindingCode.SKILLS_CHECK_DESCRIPTION_LONG, MAX_DESCRIPTION_CHARS)
        '<' in description || '>' in description -> errors += SkillIssue(FindingCode.SKILLS_CHECK_DESCRIPTION_BRACKETS)
        description.length < SHORT_DESCRIPTION_CHARS -> warnings += SkillIssue(FindingCode.SKILLS_CHECK_DESCRIPTION_SHORT)
    }
}

/**
 * `<!-- key -->` … `<!-- /key -->` sections ([withSections]) come in pairs: an extra opening or closing stays in the text
 * as is, and its condition does not apply. An opening never closed is an ordinary comment.
 */
private fun checkSections(body: String, errors: MutableList<SkillIssue>) {
    val opened = SECTION_OPEN.findAll(body).map { it.groupValues[1] }.groupingBy { it }.eachCount()
    val closed = SECTION_CLOSE.findAll(body).map { it.groupValues[1] }.groupingBy { it }.eachCount()
    closed.keys.filter { opened[it] != closed[it] }.forEach { key ->
        errors += SkillIssue(FindingCode.SKILLS_CHECK_SECTIONS, key)
    }
}

/** A new skill's starting text, in the words the model reads. */
internal fun customSkillTemplate(): String = modelText(
    """---
name: my-skill
description: 一句话写清这个技能做什么、什么时候用（模型靠它决定要不要读）。
---

# 技能标题

写给助手的做法：先做什么、再做什么，什么时候要先问用户。
""",
    """---
name: my-skill
description: In one sentence, what this skill does and when to use it (the model reads this to decide whether to load it).
---

# Skill title

How the assistant should go about it: what to do first, what next, and when to ask the user first.
""",
)

private const val CUSTOM_SKILLS_DIR = "custom-skills"
private const val SKILL_FILE = "SKILL.md"
private const val FENCE = "---"
/** SkillRegistry's MAX_SKILL_MD_BYTES. */
private const val MAX_SKILL_BYTES = 64 * 1024
private const val MAX_NAME_CHARS = 64
private const val MAX_DESCRIPTION_CHARS = 1024
private const val SHORT_DESCRIPTION_CHARS = 8
private const val LONG_BODY_LINES = 500
private val HEADER_FIELDS = listOf("name", "description", "license", "compatibility", "metadata", "allowed-tools", "scenes")
private val BLOCK_SCALARS = setOf(">", "|", ">-", "|-", ">+", "|+")
private val FIELD = Regex("""([A-Za-z][A-Za-z0-9_-]*):(?:\s+(.*)|\s*)""")
private val NAME = Regex("""[a-z0-9]+(?:-[a-z0-9]+)*""")
private val SCENES = Regex("""\[\s*("[^"]+"\s*(,\s*"[^"]+"\s*)*)?]""")
private val SECTION_OPEN = Regex("""<!-- ((?:no-)?[a-z0-9:-]+) -->""")
private val SECTION_CLOSE = Regex("""<!-- /((?:no-)?[a-z0-9:-]+) -->""")

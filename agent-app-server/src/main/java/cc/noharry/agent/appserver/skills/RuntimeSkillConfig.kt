package cc.noharry.agent.appserver.skills
import android.content.Context
import cc.noharry.agent.appserver.R
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.vfs.VfsMemorySource

data class RuntimeSkill(
    val id: String,
    val label: String,
    val description: String,
    val assetPath: String,
    val enabled: Boolean,
    val preload: Boolean = false,
    val manifestPath: String? = null,
    /**
     * Loaded only in a run a running plugin gives a tool provider of this id
     * ([cc.noharry.agent.appserver.server.PluginContext.runTools]; operating the screen's `android_use`), so it never
     * describes tools the model cannot see; null for always.
     */
    val requiresToolServer: String? = null,
    /**
     * Loaded only while a running plugin says this key of skills' conditional sections holds
     * ([cc.noharry.agent.appserver.server.PluginContext.skillSections]; the A2A plugin's `a2a` while the person's own
     * agent is connected); null for always.
     */
    val requiresSection: String? = null,
    /** Never in a subagent's runtime: the workflow is the main agent's (it spawns, shows cards, plans or delegates). */
    val mainAgentOnly: Boolean = false,
    /** A skill added on the device ([CustomSkills]): its SKILL.md, in place of [assetPath]. */
    val file: java.io.File? = null,
    /** In the system prompt, the same every run (so it stays cached), rather than in the run's context. */
    val systemPrompt: Boolean = false,
    /** A plugin's skill ([cc.noharry.agent.appserver.server.PluginSkill]): its SKILL.md, in place of [assetPath]. */
    val text: String? = null,
)

/**
 * The host's built-in skills ([cc.noharry.agent.appserver.server.AppServerConfig.skills]), then the running plugins' ([pluginRuntimeSkills]), then those
 * added on the device, each switched as last saved.
 */
internal fun loadRuntimeSkills(context: Context): List<RuntimeSkill> {
    val preferences = context.getSharedPreferences(SKILL_PREFS, Context.MODE_PRIVATE)
    return (builtinRuntimeSkills(context) + pluginRuntimeSkills(builtinRuntimeSkillIds(context)) + CustomSkills.load(context))
        .map { it.copy(enabled = preferences.getBoolean(it.id, it.enabled)) }
}

/**
 * The running plugins' skills ([cc.noharry.agent.appserver.server.PluginAdditions.skills]), but for a name one of
 * [taken] has already.
 */
internal fun pluginRuntimeSkills(taken: Set<String>): List<RuntimeSkill> =
    AppHost.additions.skills.filter { it.name !in taken }.map { skill ->
        RuntimeSkill(skill.name, skill.name, skill.description, assetPath = "", enabled = true, preload = skill.preload, text = skill.text)
    }

internal fun saveRuntimeSkill(context: Context, id: String, enabled: Boolean) {
    val known = id in builtinRuntimeSkillIds(context) || AppHost.additions.skills.any { it.name == id } ||
        CustomSkills.load(context).any { it.id == id }
    require(known) { "Unknown runtime skill." }
    context.getSharedPreferences(SKILL_PREFS, Context.MODE_PRIVATE).edit().putBoolean(id, enabled).apply()
}

internal fun builtinRuntimeSkillIds(context: Context): Set<String> = builtinRuntimeSkills(context).map { it.id }.toSet()

/** A custom skill renamed keeps its switch. */
internal fun renameRuntimeSkillChoice(context: Context, from: String, to: String) {
    val preferences = context.getSharedPreferences(SKILL_PREFS, Context.MODE_PRIVATE)
    val editor = preferences.edit().remove(from)
    if (preferences.contains(from)) editor.putBoolean(to, preferences.getBoolean(from, true))
    editor.apply()
}

internal fun forgetRuntimeSkillChoice(context: Context, id: String) {
    context.getSharedPreferences(SKILL_PREFS, Context.MODE_PRIVATE).edit().remove(id).apply()
}

/** Line endings are normalized so a Windows (CRLF) checkout builds the same prompt bytes as the repo. */
internal fun runtimeSkillText(context: Context, skill: RuntimeSkill): String =
    (skill.text ?: skill.file?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrDefault("") }
        ?: context.assets.open(skill.assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() })
        .replace("\r\n", "\n")
        .withSections(skillSections(context))

/**
 * What a skill's conditional sections may depend on: the keys running plugins say hold (the A2A plugin's `a2a` while
 * the person's own agent is connected), and `agent:<id>` per subagent type.
 */
internal fun skillSections(context: Context): Set<String> = AppHost.additions.skillSections(context) + subagentKindSections(context)

/**
 * A skill's `<!-- key -->` … `<!-- /key -->` lines are kept while [on] holds the key, its `<!-- no-key -->` lines while
 * not: a plugin's keys ([skillSections]) and `agent:<id>`, so a step can name a subagent type while it is switched on
 * and spell the whole task out while it is off. A step of another skill reaches the model where it acts: in a
 * different skill, and then as an extra paragraph under the step, the trip's backup offer was skipped; a step written
 * whole for each case is followed.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
fun String.withSections(on: Set<String>): String =
    SECTION.replace(this) { section ->
        val (negated, key, body) = section.destructured
        if ((key in on) != (negated == "no-")) body else ""
    }

private val SECTION = Regex("""<!-- (no-|)([a-z0-9:-]+) -->\n?(.*?)<!-- /\1\2 -->\n?""", RegexOption.DOT_MATCHES_ALL)

internal fun selectedSkillFiles(context: Context, skills: List<RuntimeSkill>): Map<String, String> =
    skills.filter { it.enabled }.associate { "${it.id}/SKILL.md" to runtimeSkillText(context, it) }

internal fun appSkillSource(context: Context, skills: List<RuntimeSkill>): VfsMemorySource =
    VfsMemorySource(selectedSkillFiles(context, skills))

internal fun preloadedSkillPrompt(context: Context, skills: List<RuntimeSkill>, input: String = ""): String {
    return preloadedSkillBodies(context, skills, input).joinToString("\n\n") { (id, body) ->
        "Active runtime skill: $id\n$body"
    }
}

/**
 * The skills read in full with every run; one that needs a plugin's tool server ([RuntimeSkill.requiresToolServer])
 * only when it is among [runToolServers], those the plugins gave this run.
 */
internal fun preloadedSkillBodies(context: Context, skills: List<RuntimeSkill>, input: String, runToolServers: Set<String> = emptySet()): List<Pair<String, String>> {
    val matched = selectedWorkflowSkills(context, skills, input).map { it.skill.id }.toSet()
    val sections = AppHost.additions.skillSections(context)
    return skills.filter {
        it.enabled && it.preload && (it.manifestPath == null || it.id in matched) &&
            (it.requiresToolServer == null || it.requiresToolServer in runToolServers) &&
            (it.requiresSection == null || it.requiresSection in sections)
    }.map {
        it.id to runtimeSkillText(context, it)
    }
}

private const val SKILL_PREFS = "app_server_runtime_skills"

/** The ids of [this] that go in the system prompt ([RuntimeSkill.systemPrompt]). */
internal fun List<RuntimeSkill>.systemPromptIds(): Set<String> = filter { it.systemPrompt }.mapTo(hashSetOf()) { it.id }

private fun builtinRuntimeSkills(context: Context): List<RuntimeSkill> = AppHost.skills(context)

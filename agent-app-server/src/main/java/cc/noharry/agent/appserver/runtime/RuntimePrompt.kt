@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.model.appSystemPrompt
import cc.noharry.agent.appserver.skills.RuntimeSkill
import cc.noharry.agent.appserver.trajectory.render
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.runtime.RuntimeInspection.MAX_HOST_SYSTEM_PROMPT_BYTES
import cc.noharry.agent.runtime.RuntimeInspection.validateHostSystemPrompt

/**
 * The host prompt in two parts. [system] is the same every run (base, tool settings, voice skill); [runContext] is what
 * changes per run (external companion/reminder context and workflow skills picked for this input) and is sent after the
 * input, so the provider's prompt cache keeps the system prompt and tool list from run to run. [text] is both together,
 * which is what the 16 KiB host budget covers.
 */
internal data class RuntimePrompt(val system: String, val runContext: String) {
    val text: String get() = listOf(system, runContext).joinPromptSections()
}

/** Budget the final host prompt, including separators and UTF-8, before constructing a runtime. */
internal fun runtimePrompt(
    sessionContext: String,
    basicTools: List<BasicTool>,
    skillBodies: List<Pair<String, String>>,
    previousExchange: PreviousExchange? = null,
    nativeTools: Boolean = false,
    /** 「现在：2026-10-19 周一 11:35」 ([clockLine]): per run, so it goes with the run context, never the system prompt. */
    clock: String = "",
    /** The host's rules ([RunAddOns.systemRules]), after the server's own; cached with the system prompt. */
    hostRules: List<String> = emptyList(),
    /** Skills that go in the system prompt ([RuntimeSkill.systemPrompt]), in full and before history and workflows. */
    systemSkills: Set<String> = emptySet(),
    /** The language the server's own lines are in, the persona's. */
    words: ModelWords = AppHost.words,
): RuntimePrompt {
    val (voice, workflows) = skillBodies.partition { it.first in systemSkills }
    val fixed = (listOf(appSystemPrompt("", nativeTools, words), basicToolPrompt(basicTools)) + hostRules +
        voice.map { (id, body) -> "$ACTIVE_SKILL_PREFIX$id\n$body" }).joinPromptSections()
    val canRead = "read_file" !in disabledBasicTools(basicTools)
    val externalContext = previousExchange?.externalContext(canRead).orEmpty()
    val sections = workflows.map { (id, _) ->
        "Deferred runtime skill: $id. Before using its workflow, read /.agents/skills/$id/SKILL.md in full."
    }.toMutableList()
    // Reserve the latest external exchange before optional background history and workflow bodies.
    // The thinking's language last, nearest to where the model starts thinking.
    fun render(context: String) = RuntimePrompt(fixed, (listOf(clock, context) + sections + externalContext + words.thinkLine).joinPromptSections())

    // Continuity takes priority over eager loading. Keep whole history lines; the full history
    // and all enabled skill files remain in the same VFS snapshot used by this runtime.
    val contextBudget = MAX_HOST_SYSTEM_PROMPT_BYTES - render("").text.utf8Bytes() - 2
    val context = budgetSessionContext(sessionContext, contextBudget, canRead)
    validateHostSystemPrompt(render(context).text)
    workflows.forEachIndexed { index, (id, body) ->
        val reference = sections[index]
        sections[index] = "$ACTIVE_SKILL_PREFIX$id\n$body"
        if (render(context).text.utf8Bytes() > MAX_HOST_SYSTEM_PROMPT_BYTES) {
            sections[index] = reference
            require(canRead) {
                "Runtime skills exceed the host prompt budget. Enable read_file to load deferred skills."
            }
        }
    }
    return render(context).also { validateHostSystemPrompt(it.text) }
}

private fun budgetSessionContext(context: String, maxBytes: Int, canRead: Boolean): String {
    if (context.isBlank()) return ""
    val header = "Current debug session context for continuity:"
    val full = "$header\n$context"
    if (full.utf8Bytes() <= maxBytes) return full
    require(canRead) { "Session context exceeds the host prompt budget. Enable read_file to read full history." }
    val notice = "History preview limited by host prompt budget. Read /context/session/current.json for omitted history before resolving context-dependent requests."
    var preview = header
    require("$preview\n$notice".utf8Bytes() <= maxBytes) { "Host instructions leave no space for session context." }
    for (line in context.lineSequence()) {
        if ("$preview\n$line\n$notice".utf8Bytes() > maxBytes) break
        preview += "\n$line"
    }
    return "$preview\n$notice"
}

/** Whether an earlier run already sent workflow skill [id] in full (the voice skill lives in the system prompt). */
internal fun List<ModelMessage>.carriesSkill(id: String): Boolean =
    any { "$ACTIVE_SKILL_PREFIX$id\n" in it.content }

private const val ACTIVE_SKILL_PREFIX = "Active runtime skill: "

private fun String.utf8Bytes(): Int = toByteArray(Charsets.UTF_8).size
private fun List<String>.joinPromptSections(): String = filter { it.isNotBlank() }.joinToString("\n\n")

/**
 * The device's date and time for the run context: a question such as whether it is too early to leave needs the time,
 * and the plans' context gives only the date, kept stable for the prompt cache.
 */
internal fun clockLine(now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), words: ModelWords = AppHost.words): String =
    words.clock(java.time.Instant.ofEpochMilli(now).atZone(zone))

/**
 * The app in front, as a plugin that sees the screen knows it ([cc.noharry.agent.appserver.server.ForegroundApps]):
 * named each run, so the assistant does not offer to open the app the person already has open.
 * Empty without such a plugin, or with this app itself in front.
 */
internal fun appForegroundLine(context: android.content.Context): String {
    val app = cc.noharry.agent.appserver.server.foregroundApp(context) ?: return ""
    return if (app.packageName == context.packageName) "" else foregroundLine(app.label, app.packageName)
}

internal fun foregroundLine(label: String, packageName: String, words: ModelWords = AppHost.words): String =
    words.foregroundApp(label, packageName)


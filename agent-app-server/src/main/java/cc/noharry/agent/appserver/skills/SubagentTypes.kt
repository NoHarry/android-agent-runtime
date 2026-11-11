@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.skills
import android.content.Context
import androidx.annotation.StringRes
import cc.noharry.agent.appserver.R
import cc.noharry.agent.appserver.protocol.MAX_SUBAGENT_LOOKUPS
import cc.noharry.agent.appserver.protocol.MIN_SUBAGENT_LOOKUPS
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.modelText
import cc.noharry.agent.runtime.SubagentType

/**
 * Kinds of subagent with their own instructions, each switched on or off, each with a prompt of its own. The main agent names one in spawn_agent's `agent` and writes only the specifics; the child gets the type's
 * `AGENT.md` with its task, at most [maxLookups] pages and searches, and a service's search API when [searchApi] says
 * so (null: the subagent setting). Switched on and off in the subagent settings; a switched-off type is
 * not offered, and skills fall back to writing the whole task (`<!-- no-agent:… -->` sections). Each type may call only
 * its [tools], listed and set on the type's page.
 */
data class SubagentKind(
    val id: String,
    /** Its name in Settings. */
    @StringRes val label: Int,
    /** What the model reads in spawn_agent: when to use it. */
    val description: String,
    val assetPath: String,
    val maxLookups: Int = MAX_CHILD_LOOKUPS,
    val searchApi: Boolean? = null,
    val enabled: Boolean = true,
    val tools: Set<SubagentToolGroup> = DEFAULT_SUBAGENT_TOOLS,
    /** Offered only while some profile has a service's search and fetch APIs ([cc.noharry.agent.appserver.model.ProviderAdapter.lookup]). */
    val requiresWebLookup: Boolean = false,
    /**
     * What the main agent is told to do with this type's report, in the persona's language, instead of summing it up for
     * the user: a check of an answer already given goes onto its card, say. Null sums it up.
     */
    val reportClosing: String? = null,
) {
    internal fun toSubagentType(): SubagentType = SubagentType(id, description)

    /** Whether the child searches at all: without it, neither web_search nor the provider's own search is offered. */
    val searches: Boolean get() = SubagentToolGroup.SEARCH in tools

    /** Its tools in the page's order as the model reads them, e.g. 联网搜索、打开网页、读文件. */
    internal fun toolLabels(separator: String = modelText("、", ", ")): String =
        SubagentToolGroup.entries.filter { it in tools }.joinToString(separator) { it.label }
}

/**
 * What a subagent type may call, by kind of tool rather than by name: the tool list stays the main agent's (one
 * prompt cache), and [SubagentGuard] refuses the rest. [changes] marks the ones that change something. [label] is
 * the name the model reads (a refused call, the child's prompt); [screenLabel] and [detail] are what Settings shows.
 */
enum class SubagentToolGroup(val wire: String, private val chinese: String, private val english: String, val changes: Boolean = false) {
    SEARCH("search", "联网搜索", "web search"),
    FETCH("fetch", "打开网页", "open web pages"),
    READ("read", "读文件", "read files"),
    WRITE("write", "写文件", "write files", changes = true),
    TODO("todo", "待办", "todo list"),
    DEVICE_QUERY("device_query", "查设备状态", "read device state"),
    DEVICE_CONTROL("device_control", "操作设备", "operate devices", changes = true),
    SERVICES("services", "其他服务", "other services", changes = true),
    ;

    val label: String get() = modelText(chinese, english)

    companion object {
        internal fun fromWire(wire: String): SubagentToolGroup? = entries.firstOrNull { it.wire == wire }
    }
}

@cc.noharry.agent.appserver.AppServerPluginApi
fun loadSubagentKinds(context: Context): List<SubagentKind> {
    val preferences = context.getSharedPreferences(SUBAGENT_KIND_PREFS, Context.MODE_PRIVATE)
    return SUBAGENT_KINDS.map { kind ->
        kind.copy(
            enabled = preferences.getBoolean(kind.id, kind.enabled),
            tools = preferences.getStringSet(kind.id + KEY_TOOLS, null)?.mapNotNullTo(linkedSetOf(), SubagentToolGroup::fromWire) ?: kind.tools,
            maxLookups = preferences.getInt(kind.id + KEY_MAX_LOOKUPS, kind.maxLookups),
        )
    }
}

/** A type's tools and its share of pages and searches, from its next spawn. */
internal fun saveSubagentKindTools(context: Context, id: String, tools: Set<SubagentToolGroup>, maxLookups: Int) {
    require(SUBAGENT_KINDS.any { it.id == id }) { "Unknown subagent type." }
    context.getSharedPreferences(SUBAGENT_KIND_PREFS, Context.MODE_PRIVATE).edit()
        .putStringSet(id + KEY_TOOLS, tools.mapTo(mutableSetOf()) { it.wire })
        .putInt(id + KEY_MAX_LOOKUPS, maxLookups.coerceIn(MIN_LOOKUPS, MAX_LOOKUPS))
        .apply()
}

/** Back to the type's own tools and share; its switch stays as it is. */
internal fun resetSubagentKindTools(context: Context, id: String) {
    context.getSharedPreferences(SUBAGENT_KIND_PREFS, Context.MODE_PRIVATE).edit()
        .remove(id + KEY_TOOLS)
        .remove(id + KEY_MAX_LOOKUPS)
        .apply()
}

/** The type as it ships, for 恢复默认. */
internal fun subagentKindDefaults(id: String): SubagentKind? = SUBAGENT_KINDS.firstOrNull { it.id == id }

internal fun appSubagentKind(context: Context, id: String): SubagentKind? = loadSubagentKinds(context).firstOrNull { it.id == id && it.enabled }

/** Saves the switch and offers the change from the next spawn. */
@cc.noharry.agent.appserver.AppServerPluginApi
fun saveSubagentKind(context: Context, id: String, enabled: Boolean) {
    require(SUBAGENT_KINDS.any { it.id == id }) { "Unknown subagent type." }
    context.getSharedPreferences(SUBAGENT_KIND_PREFS, Context.MODE_PRIVATE).edit().putBoolean(id, enabled).apply()
    AppSubagents.apply(context)
}

/** The type's instructions without the front matter. */
internal fun subagentKindText(context: Context, kind: SubagentKind): String =
    context.assets.open(kind.assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }.replace("\r\n", "\n")
        .let { text -> FRONT_MATTER.replace(text, "").trim() }

/** `agent:<id>` for each switched-on type, the keys of a skill's conditional sections. */
internal fun subagentKindSections(context: Context): Set<String> =
    loadSubagentKinds(context).filter { it.enabled }.mapTo(linkedSetOf()) { "$AGENT_SECTION_PREFIX${it.id}" }

/**
 * What a type can use unless it says otherwise: the web, its skills, a todo list, and connected services such as maps;
 * none of the devices.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
val DEFAULT_SUBAGENT_TOOLS = setOf(
    SubagentToolGroup.SEARCH, SubagentToolGroup.FETCH, SubagentToolGroup.READ, SubagentToolGroup.TODO,
    SubagentToolGroup.SERVICES,
)

/** The host's types ([RunAddOns.subagentKinds]). */
private val SUBAGENT_KINDS: List<SubagentKind> get() = AppHost.runs.subagentKinds()

internal const val AGENT_SECTION_PREFIX = "agent:"
private const val SUBAGENT_KIND_PREFS = "app_server_subagent_types"
private const val KEY_TOOLS = ".tools"
private const val KEY_MAX_LOOKUPS = ".max_lookups"
internal const val MIN_LOOKUPS = MIN_SUBAGENT_LOOKUPS
internal const val MAX_LOOKUPS = MAX_SUBAGENT_LOOKUPS
private val FRONT_MATTER = Regex("""\A---\n.*?\n---\n""", RegexOption.DOT_MATCHES_ALL)

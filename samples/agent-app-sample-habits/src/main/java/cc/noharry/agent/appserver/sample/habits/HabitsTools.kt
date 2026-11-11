package cc.noharry.agent.appserver.sample.habits

import android.content.Context
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import java.io.File
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/** The full name of the tool that forgets a habit, the one call of Tally's that asks. */
const val RESET_TOOL = "mcp__habits__reset_habit"

/** The habits on disk: `files/habits.json`, each habit's name with the days (ISO dates) it was done. */
class HabitStore(context: Context) {
    private val file = File(context.filesDir, "habits.json")

    /** Records [name] as done on [day] (once a day) and returns its streak. */
    fun log(name: String, day: LocalDate = LocalDate.now()): Int = synchronized(LOCK) {
        val all = read()
        val days = all.optJSONArray(name) ?: JSONArray()
        if ((0 until days.length()).none { days.getString(it) == day.toString() }) days.put(day.toString())
        all.put(name, days)
        file.writeText(all.toString())
        streak(name, day)
    }

    /** Days in a row up to [today]; a streak not yet continued today still counts up to yesterday. */
    fun streak(name: String, today: LocalDate = LocalDate.now()): Int = synchronized(LOCK) {
        val days = read().optJSONArray(name) ?: return 0
        val done = (0 until days.length()).map { LocalDate.parse(days.getString(it)) }.toSet()
        var day = if (today in done) today else today.minusDays(1)
        var count = 0
        while (day in done) {
            count++
            day = day.minusDays(1)
        }
        count
    }

    /** Forgets [name] and its days; whether there was such a habit. */
    fun reset(name: String): Boolean = synchronized(LOCK) {
        val all = read()
        val had = all.has(name)
        all.remove(name)
        file.writeText(all.toString())
        had
    }

    fun names(): List<String> = synchronized(LOCK) { read().keys().asSequence().toList().sorted() }

    private fun read(): JSONObject = if (file.exists()) JSONObject(file.readText().ifBlank { "{}" }) else JSONObject()

    private companion object {
        /** One lock for every store: the tools and the plugin each make their own. */
        val LOCK = Any()
    }
}

/**
 * The assistant's own tools, which the model reaches as `mcp__habits__log_habit`, `mcp__habits__streak` and
 * `mcp__habits__reset_habit` ([RESET_TOOL]). Logging and resetting act (`MUTATING`), so they ask before they run unless
 * the config names them (it names logging); the streak only reads (`READ_ONLY`) and never asks.
 */
class HabitsTools(context: Context) : ToolProvider {
    private val store = HabitStore(context)

    override val providerId: String = "habits"

    override suspend fun listTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor("log_habit", "Record that the user did a habit today.", NAME_SCHEMA, effectKind = "MUTATING"),
        ToolDescriptor("streak", "How many days in a row the user has done a habit.", NAME_SCHEMA, effectKind = "READ_ONLY"),
        ToolDescriptor("reset_habit", "Forget one habit and its history. The user is asked first.", NAME_SCHEMA, effectKind = "MUTATING"),
    )

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        val name = JSONObject(argumentsJson).optString("name").trim().lowercase()
        if (name.isEmpty()) return failed("name is required")
        return when (toolName) {
            "log_habit" -> ok(JSONObject().put("habit", name).put("logged", true).put("streak", store.log(name)))
            "streak" -> ok(JSONObject().put("habit", name).put("streak", store.streak(name)))
            "reset_habit" -> ok(JSONObject().put("habit", name).put("reset", store.reset(name)))
            else -> failed("unknown tool $toolName")
        }
    }

    private fun ok(json: JSONObject) = ToolCallResult(success = true, resultJson = json.toString())

    private fun failed(why: String) = ToolCallResult(success = false, resultJson = JSONObject().put("error", why).toString())

    private companion object {
        const val NAME_SCHEMA = """{"type":"object","properties":{"name":{"type":"string","description":"The habit's name."}},"required":["name"]}"""
    }
}

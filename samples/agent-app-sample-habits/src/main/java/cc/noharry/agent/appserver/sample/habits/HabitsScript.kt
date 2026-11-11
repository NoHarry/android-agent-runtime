package cc.noharry.agent.appserver.sample.habits

import cc.noharry.agent.appserver.model.ScriptedModel
import org.json.JSONObject

/**
 * What Tally does with no model service: the script the simulated backend plays, offline, in the test and on a device.
 * "log running", "streak for running", "reset running", "list my habits".
 */
val HabitsScript = ScriptedModel {
    on(Regex("""reset\s+(?:my\s+|the\s+)?([a-z ]+?)(?:\s+habit)?\s*$""", RegexOption.IGNORE_CASE)) { found ->
        call("habits", "reset_habit", mapOf("name" to found.groupValues[1]))
        answer { results -> sentence(results.single()) }
    }
    on(Regex("""streak\s+(?:for|of)\s+([a-z ]+?)\s*$""", RegexOption.IGNORE_CASE)) { found ->
        call("habits", "streak", mapOf("name" to found.groupValues[1]))
        answer { results -> sentence(results.single()) }
    }
    on("list") {
        call("habit_list", "list_habits")
        answer { results -> sentence(results.single()) }
    }
    on(Regex("""(?:log|did|done)\s+([a-z ]+?)\s*$""", RegexOption.IGNORE_CASE)) { found ->
        call("habits", "log_habit", mapOf("name" to found.groupValues[1]))
        answer { results -> sentence(results.single()) }
    }
    otherwise { answer("I'm Tally, the habit tracker. Ask me to log, reset or list habits, or for a streak.") }
}

// guide:start results
/**
 * One sentence from what the model was given for a call: the tool's resultJson when it succeeded; otherwise the
 * server's JSON, `{"ok":false,"code":…,"message":…}`, whose code says why: BACKEND_CALL_FAILED (the tool failed; its
 * own resultJson is the message), CONFIRMATION_DENIED (nobody said yes, so it did not run), TOOL_UNAVAILABLE (its
 * server or plugin is off, so it did not run).
 */
private fun sentence(given: String): String {
    val result = JSONObject(given)
    if (result.optBoolean("ok", true)) return done(result)
    val message = result.optString("message")
    return when (result.optString("code")) {
        "BACKEND_CALL_FAILED" -> "That didn't work: " + (runCatching { JSONObject(message).getString("error") }.getOrNull() ?: message) + "."
        "CONFIRMATION_DENIED" -> "Not done: forgetting a habit was not allowed."
        "TOOL_UNAVAILABLE" -> "Not done: that is turned off right now."
        else -> "Not done: " + result.optString("code") + "."
    }
}
// guide:end results

/** One sentence from a habits tool's own result. */
private fun done(result: JSONObject): String = when {
    result.has("logged") -> "Logged ${result.getString("habit")}; streak ${result.getInt("streak")}."
    result.has("reset") -> if (result.getBoolean("reset")) "Reset ${result.getString("habit")}." else "No habit named ${result.getString("habit")}."
    result.has("habits") -> result.getJSONArray("habits").let { habits ->
        if (habits.length() == 0) "No habits yet." else "Habits: " + (0 until habits.length()).joinToString(", ") { habits.getString(it) } + "."
    }
    result.has("streak") -> "Streak for ${result.getString("habit")}: ${result.getInt("streak")}."
    else -> "Done: $result"
}

package cc.noharry.agent.appserver.sample.timer

import cc.noharry.agent.appserver.model.ScriptedModel
import org.json.JSONObject

// guide:start script
/**
 * What Pip does with no model service: the script the simulated backend plays, offline, in the tests and on a device.
 * "start a 5 minute egg timer", "list my timers", "cancel the egg timer"; and, with the kitchen measures package
 * installed, "how many ml in 2 cups".
 */
val TimerScript = ScriptedModel {
    on(Regex("""(?:cancel|stop)\s+(?:the\s+)?([a-z ]+?)\s*(?:timer|$)""")) { found ->
        call("timer", "cancel_timer", mapOf("label" to found.groupValues[1]))
        answer { results -> sentence(results.single()) }
    }
    on("list") {
        call("timer", "list_timers")
        answer { results -> sentence(results.single()) }
    }
    on(Regex("""(\d+)[\s-]*(hour|minute|second)s?\s+([a-z ]*?)\s*timer""")) { found ->
        val (amount, unit, label) = found.destructured
        val seconds = amount.toLong() * mapOf("hour" to 3600, "minute" to 60, "second" to 1).getValue(unit)
        call("timer", "start_timer", mapOf("label" to label.ifBlank { "kitchen" }, "seconds" to seconds))
        answer { results -> sentence(results.single()) }
    }
    // A plugin package's script tools go by its id's last segment: com.example.kitchen.measures's are mcp__measures__….
    on(Regex("""(\d+(?:\.\d+)?)\s+cups?""")) { found ->
        call("measures", "cups_to_ml", mapOf("cups" to found.groupValues[1].toDouble()))
        answer { results -> sentence(results.single()) }
    }
    otherwise { answer("I'm Pip, the kitchen timer. Ask me to start, list or cancel a timer.") }
}
// guide:end script

/**
 * One short sentence from what the model was given for a tool's call: its resultJson, or, for a call that failed
 * (`success = false`), `{"ok":false,"code":"BACKEND_CALL_FAILED","message":"<its resultJson>"}`. A script tool that fails
 * gives `{"error": "…"}` as its resultJson, as the timer's tools do.
 */
private fun sentence(given: String): String {
    val outer = runCatching { JSONObject(given) }.getOrNull() ?: return "Done: $given"
    val result = if (outer.optBoolean("ok", true)) outer else runCatching { JSONObject(outer.getString("message")) }.getOrElse { outer }
    return when {
        result.has("error") -> "That didn't work: ${result.optString("error")}."
        result.has("started") -> "Started the ${result.getString("started")} timer for ${result.getLong("seconds")} seconds."
        result.has("cancelled") -> "Cancelled the ${result.getString("cancelled")} timer."
        result.has("ml") -> "That is ${result.getLong("ml")} ml."
        result.has("timers") -> {
            val timers = result.getJSONArray("timers")
            val running = (0 until timers.length()).map { timers.getJSONObject(it) }
                .joinToString(", ") { "${it.getString("label")} (${it.getLong("secondsLeft")}s left)" }
            if (running.isEmpty()) "No timers are running." else "Running: $running."
        }
        else -> "Done: $given"
    }
}

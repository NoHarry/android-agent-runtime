package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.kernel.api.model.ModelMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * After dsh's repeat-tool-reminder. A run has no round cap, so a model that keeps making the exact same call (same
 * tool, same arguments, in a row) is told to change approach or answer: gently at the first threshold, with the tool
 * and arguments named at the later ones. It is advice only; the calls still run, and [ToolCallFailureBreaker] still
 * stops calls that keep failing. One instance per run.
 */
internal class RepeatToolCallReminder(private val thresholds: List<Int> = REPEAT_REMINDER_THRESHOLDS) {
    private var lastCall: String? = null
    private var repeats = 0

    /** The reminder for this round's calls when a run of identical calls just reached a threshold, else null. */
    fun after(roundMessages: List<ModelMessage>): String? {
        var reminder: String? = null
        for (message in roundMessages) {
            if (message.role != "assistant") continue
            val tool = message.toolName ?: continue
            val arguments = canonicalArguments(message.toolArgumentsJson.orEmpty())
            val call = "$tool\n$arguments"
            repeats = if (call == lastCall) repeats + 1 else 1
            lastCall = call
            if (repeats in thresholds) {
                reminder = if (repeats == thresholds.first()) GENTLE_REMINDER else detailedReminder(tool, repeats, arguments)
            }
        }
        return reminder
    }
}

/** Key-sorted JSON, so arguments that differ only in key order count as the same call; raw text when not JSON. */
private fun canonicalArguments(json: String): String =
    runCatching { Json.parseToJsonElement(json).sortedKeys().toString() }.getOrDefault(json.trim())

private fun JsonElement.sortedKeys(): JsonElement = when (this) {
    is JsonObject -> JsonObject(entries.sortedBy { it.key }.associate { it.key to it.value.sortedKeys() })
    is JsonArray -> JsonArray(map { it.sortedKeys() })
    else -> this
}

private fun detailedReminder(tool: String, count: Int, arguments: String): String =
    "Repeated tool call detected:\n- tool: $tool\n- consecutive_calls: $count\n- arguments: ${arguments.preview()}\n" +
        "The repeated calls are not making progress. Do not call this tool with these exact arguments again. " +
        "Inspect the latest result and choose a different action, different arguments, or answer if enough evidence " +
        "has been gathered."

private fun String.preview(): String =
    if (length <= ARGUMENTS_PREVIEW_CHARS) this else "${take(ARGUMENTS_PREVIEW_CHARS)}… (+${length - ARGUMENTS_PREVIEW_CHARS} more chars)"

private const val GENTLE_REMINDER =
    "You are repeating the exact same tool call with identical arguments. Carefully analyze the previous result " +
        "before calling again: if the task is not complete, try a different approach or different arguments instead " +
        "of repeating the call."

/** dsh's defaults. */
private val REPEAT_REMINDER_THRESHOLDS = listOf(3, 5, 8)
private const val ARGUMENTS_PREVIEW_CHARS = 500

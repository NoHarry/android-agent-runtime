package cc.noharry.agent.appserver.sample.timer

import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Kitchen timers kept in memory: label to the time each rings (epoch milliseconds). Starting and cancelling one act (their
 * effect is left `UNKNOWN`, which counts as acting, so they would ask: the app allows all, having nobody to ask); listing
 * them only reads.
 */
class TimerTools(private val now: () -> Long = System::currentTimeMillis) : ToolProvider {
    private val timers = ConcurrentHashMap<String, Long>()

    override val providerId: String = "timer"
    override val displayName: String = "Kitchen timers"

    override suspend fun listTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = "start_timer",
            description = "Starts a kitchen timer named label that rings after seconds.",
            inputSchemaJson = """{"type":"object","properties":{"label":{"type":"string"},"seconds":{"type":"integer","minimum":1}},"required":["label","seconds"]}""",
        ),
        ToolDescriptor(
            name = "list_timers",
            description = "Lists the running kitchen timers and the seconds each has left.",
            inputSchemaJson = """{"type":"object","properties":{}}""",
            effectKind = "READ_ONLY",
        ),
        ToolDescriptor(
            name = "cancel_timer",
            description = "Cancels the kitchen timer named label.",
            inputSchemaJson = """{"type":"object","properties":{"label":{"type":"string"}},"required":["label"]}""",
        ),
    )

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrElse { JSONObject() }
        return when (toolName) {
            "start_timer" -> {
                val label = arguments.optString("label").trim()
                val seconds = arguments.optLong("seconds", 0)
                when {
                    label.isEmpty() || seconds <= 0 -> failed("label and a positive seconds are required")
                    seconds > MAX_SECONDS -> failed("timers longer than 4 hours are not allowed")
                    else -> {
                        timers[label] = now() + seconds * 1000
                        ok(JSONObject().put("started", label).put("seconds", seconds))
                    }
                }
            }
            "list_timers" -> ok(
                JSONObject().put(
                    "timers",
                    JSONArray(
                        timers.entries.sortedBy { it.value }.map { (label, ringsAt) ->
                            JSONObject().put("label", label).put("secondsLeft", ((ringsAt - now()) / 1000).coerceAtLeast(0))
                        },
                    ),
                ),
            )
            "cancel_timer" -> {
                val label = arguments.optString("label").trim()
                if (timers.remove(label) != null) ok(JSONObject().put("cancelled", label)) else failed("no timer named $label")
            }
            else -> failed("unknown tool $toolName")
        }
    }

    private fun ok(json: JSONObject) = ToolCallResult(success = true, resultJson = json.toString())

    private fun failed(why: String) = ToolCallResult(success = false, resultJson = JSONObject().put("error", why).toString())

    private companion object {
        const val MAX_SECONDS = 4 * 60 * 60L
    }
}

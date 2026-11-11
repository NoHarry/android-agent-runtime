package cc.noharry.agent.appserver.sample.habits

import android.content.Context
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.PluginContext
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import org.json.JSONArray
import org.json.JSONObject

// guide:start plugin
/**
 * Listing every habit, as a plugin the person can turn off and on while the app runs
 * (`plugins/set {"id":"habit-list","enabled":false}`). Its tool is `mcp__habit_list__list_habits`; while the plugin is
 * off a conversation that found it before is told, when it calls it, that its server is off. Each run's context also
 * says how many habits are tracked, so a model knows whether there is anything to list before it calls.
 */
object HabitListPlugin : AppServerPlugin {
    override val id: String = "habit-list"

    override fun apply(ctx: PluginContext) {
        // Tools for each run, from the app's Context (and the run's other tool providers, unused here).
        ctx.tools { context, _ -> listOf(HabitList(context)) }
        // Lines for the system prompt, after the host's: the same every run, so the prompt stays cached.
        ctx.rules(listOf("When asked which habits are tracked, list them with the habit list tool."))
        // A line for each run's context, read anew every run (@AppServerPluginApi, its module's second opt-in).
        ctx.contextLines { facts -> listOf("Habits tracked: ${HabitStore(facts.context).names().size}.") }
    }
}
// guide:end plugin

private class HabitList(context: Context) : ToolProvider {
    private val store = HabitStore(context)

    override val providerId: String = "habit_list"

    override suspend fun listTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor("list_habits", "List every habit the user tracks.", """{"type":"object","properties":{}}""", effectKind = "READ_ONLY"),
    )

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult =
        ToolCallResult(success = true, resultJson = JSONObject().put("habits", JSONArray(store.names())).toString())
}

package cc.noharry.agent.appserver.sample.habits

import android.app.Application
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.runtime.Persona
import cc.noharry.agent.appserver.server.AppServer
import cc.noharry.agent.appserver.server.AppServerConfig

/**
 * Tally, a habit tracker with no screen and no model service: its persona, its tools and rule, which of its tools that
 * act need no asking, its plugin, and the script it plays.
 */
fun habitsConfig(): AppServerConfig = AppServerConfig(
    name = "agent-app-sample-habits",
    persona = Persona(
        name = "Tally",
        identity = "You are Tally, a habit tracker assistant on the user's device. You log habits, report streaks " +
            "and answer in one short sentence.",
        language = "en",
    ),
    tools = { context -> listOf(HabitsTools(context)) },
    rules = listOf("Never tell the user a streak from memory: read it with the streak tool every time."),
    // Logging a habit acts but needs no asking; forgetting one (reset_habit, which acts too) asks, as a tool that acts
    // does, and HabitsAgent gives or refuses the yes in code.
    unaskedTools = setOf("mcp__habits__log_habit"),
    // Listing every habit, which can be turned off and on while the app runs.
    plugins = listOf(HabitListPlugin),
    approvals = ConfirmationMode.ASK_EVERY_TIME,
    // No model service: every turn plays the script, from the first one on.
    simulatedModel = { HabitsScript },
    simulatedBackend = true,
)

/** Starts the app server as Tally when the process starts. */
class HabitsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServer.install(this, habitsConfig())
    }
}

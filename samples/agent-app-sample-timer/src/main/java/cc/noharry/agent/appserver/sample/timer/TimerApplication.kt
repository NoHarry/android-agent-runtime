package cc.noharry.agent.appserver.sample.timer

import android.app.Application
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.runtime.Persona
import cc.noharry.agent.appserver.server.AppServer
import cc.noharry.agent.appserver.server.AppServerConfig

/** The 4-hour rule as the system prompt carries it, for a real model; the tool refuses longer timers either way. */
const val MAX_TIMER_RULE =
    "Never start a timer longer than 4 hours (14400 seconds). If the user asks for a longer one, do not call start_timer; " +
        "ask them to confirm a shorter duration instead."

// guide:start config
/** Pip, a kitchen timer with no screen and no model service: its persona, its tools and rule, and the script it plays. */
fun timerConfig(): AppServerConfig {
    val timers = TimerTools()
    return AppServerConfig(
        name = "agent-app-sample-timer",
        persona = Persona(
            name = "Pip",
            identity = "You are Pip, a kitchen timer assistant running on the user's device. You start, list and " +
                "cancel cooking timers through your tools and answer in one short sentence.",
            language = "en",
        ),
        tools = { listOf(timers) },
        rules = listOf(MAX_TIMER_RULE),
        // No screen to answer on: nothing waits for a yes.
        approvals = ConfirmationMode.ALLOW_ALL_SESSION,
        // No model service: every turn plays the script, from the first one on.
        simulatedModel = { TimerScript },
        simulatedBackend = true,
    )
}

class TimerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServer.install(this, timerConfig())
    }
}
// guide:end config

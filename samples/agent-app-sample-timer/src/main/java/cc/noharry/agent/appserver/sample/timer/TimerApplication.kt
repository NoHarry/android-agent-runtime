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

/**
 * The publisher of the kitchen measures package (`package/` in this sample, built with tools/agent-plugin): the public
 * key, hex of its DER encoding, that signed it. The private key was thrown away once it had.
 */
const val KITCHEN_PUBLISHER =
    "3059301306072a8648ce3d020106082a8648ce3d03010703420004305e006742b825fae7ba91905f6bb0a6cb7a8c8034f62cbdbbf08e74d5a35f04" +
        "78ce082cbf6d40ab2a6070a5e6885b41c043652c76d646d9c08405bd3d07ccd3"

// guide:start config
/**
 * Pip, a kitchen timer with no screen and no model service: its persona, its tools and rule, the script it plays, and
 * the publisher whose plugin packages it takes with no second yes.
 */
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
        // Its tools that say they only read run unasked; any other publisher's package needs the person's second yes.
        trustedPublishers = setOf(KITCHEN_PUBLISHER),
    )
}

class TimerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServer.install(this, timerConfig())
    }
}
// guide:end config

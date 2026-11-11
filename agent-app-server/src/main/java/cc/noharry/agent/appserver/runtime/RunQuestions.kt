package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.TurnStatus

/** The turn that ended to ask the person something, with its question. */
@cc.noharry.agent.appserver.AppServerPluginApi
fun ThreadEvent.followUpQuestion(): Pair<String, String>? =
    (this as? ThreadEvent.TurnCompleted)?.result?.takeIf { it.status == TurnStatus.WAITING_FOR_ANSWER }
        ?.let { it.turnId to it.finalResponse }

internal const val OUTSIDE_TURN_SCENE = "companion"

/**
 * The scene of words a plugin had said as written, without the model ([cc.noharry.agent.appserver.server.Delivery.said],
 * a plan's reminder): a turn kept outside the runtime's history, like a companion's. Named `plan` for the turns kept
 * before plans were a plugin.
 */
internal const val SAID_TURN_SCENE = "plan"
internal const val OUTSIDE_CONTEXT_TURNS = 4

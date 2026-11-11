@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.session.SessionStore
import cc.noharry.agent.spi.reliability.RuntimeRunStatus

/** The input a run gets: what a plugin stands in for it, else what the person sent. */
internal fun SessionStore.prepareRunInput(sessionId: String, text: String): Pair<String, PreviousExchange?> {
    ensureSession(sessionId)
    val previous = previousExchange(sessionId)
    // A plugin may stand its own input in (a pick of an offered option carries its checked call); each hears of every turn.
    val prepared = AppHost.additions.inputs.mapNotNull { it(sessionId, text, previous) }
    return (prepared.firstOrNull() ?: text) to previous
}

@cc.noharry.agent.appserver.AppServerPluginApi
val ANSWERABLE_STATUSES = setOf(RuntimeRunStatus.COMPLETED.name, RuntimeRunStatus.WAITING_USER_INPUT.name)

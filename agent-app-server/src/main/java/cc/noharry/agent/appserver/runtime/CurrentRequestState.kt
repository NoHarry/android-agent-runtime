package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.protocol.CurrentRequest
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest

internal const val DEBUG_SESSION_ID = "debug-console-session"
internal const val DEFAULT_USE_SIMULATED_MODEL = false
internal const val DEFAULT_STREAM_ENABLED = true

internal fun RuntimeTurnRequest.toCurrentRequest(text: String): CurrentRequest =
    CurrentRequest(requestId = requestId, text = text)

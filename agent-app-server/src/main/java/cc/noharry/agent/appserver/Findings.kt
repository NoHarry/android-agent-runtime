package cc.noharry.agent.appserver

import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.logText
import cc.noharry.agent.appserver.protocol.text
import java.io.IOException

/** A failure the screens are told about as [finding]; its message is the finding in no language, for logs. */
@cc.noharry.agent.appserver.AppServerPluginApi
class FoundException(val finding: Finding) : IOException(finding.logText())

/** What this failure is, as a finding: the server's own when it made one, else the error's message as it is. */
@cc.noharry.agent.appserver.AppServerPluginApi
fun Throwable.finding(): Finding = (this as? FoundException)?.finding ?: text(message ?: javaClass.simpleName)

/** 「HTTP 404」, with the answer's own error [message] when it has one. */
@cc.noharry.agent.appserver.AppServerPluginApi
fun httpFailure(status: Int, message: String?): Finding =
    if (message == null) finding(FindingCode.HTTP_STATUS, status) else finding(FindingCode.MODEL_HTTP_ERROR, status, message)

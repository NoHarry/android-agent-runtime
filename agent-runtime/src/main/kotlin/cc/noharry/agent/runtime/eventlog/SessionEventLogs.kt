@file:JvmName("SessionEventLogs")

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.SessionEventLog
import java.io.File
import java.util.UUID

/**
 * Opens a durable session event log under a host-owned private [root]. It can be supplied with
 * `AgentRuntimeBuilder.sessionEventLog`; Android hosts normally use `appPrivateSessionEventLog(context)`.
 */
@ExperimentalAgentRuntimeApi
@JvmOverloads
fun openFileBackedSessionEventLog(
    root: File,
    eventIdFactory: () -> String = { UUID.randomUUID().toString() },
): SessionEventLog = FileBackedSessionEventLog(root, eventIdFactory = eventIdFactory)

@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.AgentCapabilities
import cc.noharry.agent.kernel.api.AgentPlugin
import cc.noharry.agent.kernel.api.AgentPluginDescriptor
import cc.noharry.agent.kernel.api.AgentPluginScope
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.KernelApiRange
import cc.noharry.agent.kernel.api.KernelApiVersions
import cc.noharry.agent.kernel.api.PluginVersion
import cc.noharry.agent.kernel.api.SessionEventLog
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import java.io.File
import java.util.UUID

/**
 * Packaged SINGLE [AgentCapabilities.SESSION_EVENT_LOG] using a host-owned app-private
 * no-backup root. Official composition entry; not a second Builder.
 */
@ExperimentalAgentRuntimeApi
class DurableSessionEventLogPlugin private constructor(
    private val root: File,
) : AgentPlugin {
    override val id: String = PLUGIN_ID
    override val descriptor: AgentPluginDescriptor = AgentPluginDescriptor(
        id = PLUGIN_ID,
        pluginVersion = PluginVersion(1, 0, 0),
        kernelApiRange = KernelApiRange(
            min = KernelApiVersions.CURRENT,
            maxInclusive = KernelApiVersions.CURRENT,
        ),
        provides = setOf(AgentCapabilities.SESSION_EVENT_LOG),
    )

    override fun install(scope: AgentPluginScope) {
        scope.provide(
            AgentCapabilities.SESSION_EVENT_LOG,
            AsyncSessionEventLog(FileBackedSessionEventLog(root)),
            ProviderConcurrencyDeclaration.serialized(),
        )
    }

    companion object {
        const val PLUGIN_ID: String = "durable-session-event-log"
        /** The root's directory name under an app's no-backup storage (`agent-runtime-android`'s `appPrivate`). */
        const val ROOT_DIRECTORY: String = "agent-runtime"

        fun forRoot(root: File): DurableSessionEventLogPlugin = DurableSessionEventLogPlugin(root)
    }
}

/**
 * Opens the durable file-backed session event log under [root] directly, the same log [DurableSessionEventLogPlugin]
 * installs. Candidate API, not frozen.
 */
@ExperimentalAgentRuntimeApi
fun openFileBackedSessionEventLog(
    root: File,
    eventIdFactory: () -> String = { UUID.randomUUID().toString() },
): SessionEventLog = FileBackedSessionEventLog(root, eventIdFactory = eventIdFactory)

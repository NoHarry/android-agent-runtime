@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.spi.AgentCapabilities
import cc.noharry.agent.spi.AgentPlugin
import cc.noharry.agent.spi.AgentPluginDescriptor
import cc.noharry.agent.spi.AgentPluginScope
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.KernelApiRange
import cc.noharry.agent.spi.KernelApiVersions
import cc.noharry.agent.spi.PluginVersion
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import java.io.File

/**
 * Packaged SINGLE [AgentCapabilities.SESSION_EVENT_LOG] using a host-owned app-private
 * no-backup root. Official composition entry; not a second Builder.
 */
@ExperimentalAgentRuntimeApi
internal class DurableSessionEventLogPlugin private constructor(
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
        /** Directory name for the internal packaged log's default root. */
        const val ROOT_DIRECTORY: String = "agent-runtime"

        fun forRoot(root: File): DurableSessionEventLogPlugin = DurableSessionEventLogPlugin(root)
    }
}

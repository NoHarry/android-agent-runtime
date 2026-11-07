@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.run
import cc.noharry.agent.spi.AgentCapabilities
import cc.noharry.agent.spi.AgentPlugin
import cc.noharry.agent.spi.AgentProfile
import cc.noharry.agent.spi.ExecEngine
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.RuntimeContextProvider
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.RuntimeTurnObserver
import cc.noharry.agent.spi.SessionEventLog
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.kernel.core.plugin.CanonicalCapabilityKeys
import cc.noharry.agent.kernel.core.plugin.DirectCapabilityPlugin
import cc.noharry.agent.kernel.core.plugin.ProfileCompiler
import cc.noharry.agent.kernel.core.plugin.RuntimeBlueprint
import cc.noharry.agent.kernel.core.plugin.RuntimeCapabilityDeclarations
import cc.noharry.agent.runtime.eventlog.FileBackedSessionEventLog
import cc.noharry.agent.runtime.eventlog.InMemorySessionEventLog
import java.io.File

/** Compiles builder declarations plus packaged plugins through the single ProfileCompiler path. */
internal object RuntimeProfileAdapter {
    fun compile(
        profile: AgentProfile,
        declarations: RuntimeCapabilityDeclarations,
        packagedPlugins: List<AgentPlugin>,
    ): RuntimeBlueprint {
        val plugins = listOfNotNull(DirectCapabilityPlugin.from(declarations)) + packagedPlugins
        val blueprint = ProfileCompiler().compile(profile, plugins)
        try {
            CanonicalCapabilityKeys.validate(blueprint)
            return blueprint
        } catch (error: Throwable) {
            blueprint.close()
            throw error
        }
    }

    fun resolve(
        blueprint: RuntimeBlueprint,
        sessionEventLogRoot: File?,
        nowEpochMillis: () -> Long,
    ): BlueprintCapabilities {
        val eventLog = blueprint.resolveShared(AgentCapabilities.SESSION_EVENT_LOG)
            ?: sessionEventLogRoot?.let {
                cc.noharry.agent.runtime.eventlog.AsyncSessionEventLog(FileBackedSessionEventLog(it, nowEpochMillis), nowEpochMillis)
            }
            ?: InMemorySessionEventLog(nowEpochMillis)
        return BlueprintCapabilities(
            modelClient = blueprint.resolveShared(AgentCapabilities.MODEL_CLIENT),
            execEngine = blueprint.resolveShared(AgentCapabilities.EXEC_ENGINE),
            eventLog = eventLog,
            toolProviders = blueprint.resolveSharedAll(AgentCapabilities.TOOL_PROVIDER),
            contextProviders = uniqueById(
                "contextProvider",
                blueprint.resolveSharedAll(AgentCapabilities.RUNTIME_CONTEXT_PROVIDER),
                RuntimeContextProvider::id,
            ),
            turnObservers = uniqueById(
                "turnObserver",
                blueprint.resolveSharedAll(AgentCapabilities.RUNTIME_TURN_OBSERVER),
                RuntimeTurnObserver::id,
            ),
            hooks = blueprint.resolveSharedAll(AgentCapabilities.RUNTIME_HOOK),
        )
    }

}

internal data class BlueprintCapabilities(
    val modelClient: ModelClient?,
    val execEngine: ExecEngine?,
    val eventLog: SessionEventLog,
    val toolProviders: List<ToolProvider>,
    val contextProviders: List<RuntimeContextProvider>,
    val turnObservers: List<RuntimeTurnObserver>,
    val hooks: List<RuntimeHook>,
)

private fun <T : Any> uniqueById(
    kind: String,
    values: Collection<T>,
    id: (T) -> String,
): List<T> {
    val seen = linkedSetOf<String>()
    values.forEach { value ->
        val valueId = id(value)
        require(valueId.isNotBlank()) { "$kind id must not be blank." }
        require(seen.add(valueId)) { "Duplicate $kind id: $valueId" }
    }
    return values.toList()
}

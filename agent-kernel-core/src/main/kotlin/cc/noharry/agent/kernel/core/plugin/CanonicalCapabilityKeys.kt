@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.plugin
import cc.noharry.agent.kernel.api.AgentCapabilities
import cc.noharry.agent.kernel.api.CapabilityKey
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi

/** Canonical built-in [CapabilityKey] objects. Diagnostic aliases are rejected. */
@ExperimentalAgentRuntimeApi
object CanonicalCapabilityKeys {
    val ALL: List<CapabilityKey<*>> = listOf(
        AgentCapabilities.TOOL_PROVIDER,
        AgentCapabilities.RUNTIME_CONTEXT_PROVIDER,
        AgentCapabilities.RUNTIME_TURN_OBSERVER,
        AgentCapabilities.RUNTIME_HOOK,
        AgentCapabilities.MODEL_CLIENT,
        AgentCapabilities.EXEC_ENGINE,
        AgentCapabilities.SESSION_EVENT_LOG,
        AgentCapabilities.WORKSPACE,
    )

    private val byDiagnosticId: Map<String, CapabilityKey<*>> = ALL.associateBy { it.diagnosticId }

    fun validate(blueprint: RuntimeBlueprint) {
        rejectNoncanonical(blueprint)
        rejectUnsupportedFactories(blueprint)
    }

    fun rejectNoncanonical(blueprint: RuntimeBlueprint) {
        blueprint.providedKeys().forEach { key ->
            val canonical = byDiagnosticId[key.diagnosticId] ?: return@forEach
            if (key !== canonical) {
                error("${RuntimeCompositionMessages.NONCANONICAL_BUILTIN_KEY}: ${key.diagnosticId}")
            }
        }
    }

    fun rejectUnsupportedFactories(blueprint: RuntimeBlueprint) {
        blueprint.factoryKeys().forEach { key ->
            if (key !== AgentCapabilities.TOOL_PROVIDER) {
                error("${RuntimeCompositionMessages.UNSUPPORTED_FACTORY_COMPOSITION}: ${key.name}")
            }
        }
    }
}

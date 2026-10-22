@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.api

import cc.noharry.agent.kernel.api.model.ModelClient

/** Candidate built-in capability keys understood by the runtime Kernel. */
@ExperimentalAgentRuntimeApi
object AgentCapabilities {
    val TOOL_PROVIDER =
        CapabilityKey<ToolProvider>("tool_provider", CapabilityCardinality.MULTIPLE)

    val RUNTIME_CONTEXT_PROVIDER =
        CapabilityKey<RuntimeContextProvider>("runtime_context_provider", CapabilityCardinality.MULTIPLE)

    val RUNTIME_TURN_OBSERVER =
        CapabilityKey<RuntimeTurnObserver>("runtime_turn_observer", CapabilityCardinality.MULTIPLE)

    val RUNTIME_HOOK =
        CapabilityKey<RuntimeHook>("runtime_hook", CapabilityCardinality.MULTIPLE)

    val MODEL_CLIENT =
        CapabilityKey<ModelClient>("model_client", CapabilityCardinality.SINGLE)

    val EXEC_ENGINE =
        CapabilityKey<ExecEngine>("exec_engine", CapabilityCardinality.SINGLE)

    val SESSION_EVENT_LOG =
        CapabilityKey<SessionEventLog>("session_event_log", CapabilityCardinality.SINGLE)

    val WORKSPACE =
        CapabilityKey<WorkspacePort>("workspace", CapabilityCardinality.SINGLE)
}

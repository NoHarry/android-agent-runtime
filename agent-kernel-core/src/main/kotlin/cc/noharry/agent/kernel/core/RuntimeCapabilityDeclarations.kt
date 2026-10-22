@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.RuntimeContextProvider
import cc.noharry.agent.kernel.api.RuntimeTurnObserver
import cc.noharry.agent.kernel.api.SessionEventLog
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.api.WorkspacePort
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.ExecEngine

/**
 * Immutable host capability declarations packaged into one synthetic plugin.
 * This is the single direct-capability composition path for core and runtime.
 */
@ExperimentalAgentRuntimeApi
class RuntimeCapabilityDeclarations internal constructor(
    val modelClient: ModelClient?,
    val workspace: WorkspacePort?,
    val toolProviders: List<ToolProvider>,
    val contextProviders: List<RuntimeContextProvider>,
    val turnObservers: List<RuntimeTurnObserver>,
    val execEngine: ExecEngine?,
    val sessionEventLog: SessionEventLog?,
) {
    val isEmpty: Boolean
        get() = modelClient == null &&
            workspace == null &&
            toolProviders.isEmpty() &&
            contextProviders.isEmpty() &&
            turnObservers.isEmpty() &&
            execEngine == null &&
            sessionEventLog == null

    class Collector {
        private var modelClient: ModelClient? = null
        private var workspace: WorkspacePort? = null
        private val toolProviders = mutableListOf<ToolProvider>()
        private val contextProviders = linkedMapOf<String, RuntimeContextProvider>()
        private val turnObservers = linkedMapOf<String, RuntimeTurnObserver>()
        private var execEngine: ExecEngine? = null
        private var sessionEventLog: SessionEventLog? = null

        fun modelClient(client: ModelClient) {
            modelClient = client
        }

        fun workspace(workspace: WorkspacePort) {
            this.workspace = workspace
        }

        fun toolProvider(provider: ToolProvider) {
            toolProviders += provider
        }

        fun contextProvider(provider: RuntimeContextProvider) {
            require(provider.id.isNotBlank()) { RuntimeCompositionMessages.BLANK_CONTEXT_PROVIDER_ID }
            require(provider.id !in contextProviders) {
                "${RuntimeCompositionMessages.DUPLICATE_CONTEXT_PROVIDER}: ${provider.id}"
            }
            contextProviders[provider.id] = provider
        }

        fun turnObserver(observer: RuntimeTurnObserver) {
            require(observer.id.isNotBlank()) { RuntimeCompositionMessages.BLANK_TURN_OBSERVER_ID }
            require(observer.id !in turnObservers) {
                "${RuntimeCompositionMessages.DUPLICATE_TURN_OBSERVER}: ${observer.id}"
            }
            turnObservers[observer.id] = observer
        }

        fun execEngine(engine: ExecEngine) {
            execEngine = engine
        }

        fun sessionEventLog(log: SessionEventLog) {
            sessionEventLog = log
        }

        fun snapshot(): RuntimeCapabilityDeclarations =
            RuntimeCapabilityDeclarations(
                modelClient = modelClient,
                workspace = workspace,
                toolProviders = toolProviders.toList(),
                contextProviders = contextProviders.values.toList(),
                turnObservers = turnObservers.values.toList(),
                execEngine = execEngine,
                sessionEventLog = sessionEventLog,
            )
    }
}

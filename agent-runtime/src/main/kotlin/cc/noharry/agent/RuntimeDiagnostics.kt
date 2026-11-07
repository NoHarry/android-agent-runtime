@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent

import cc.noharry.agent.spi.*
import cc.noharry.agent.spi.reliability.*

/** Advanced diagnostic records, separate from the stable conversation API. */
@ExperimentalAgentRuntimeApi
interface RuntimeDiagnostics {
    suspend fun getRunState(turnId: String): RuntimeRunSnapshot?
    suspend fun getRuntimeResult(turnId: String): RuntimeResult?
    suspend fun getRuntimeHandoff(turnId: String): RuntimeHandoff?
    suspend fun getRuntimeEvidence(evidenceId: String): RuntimeEvidence?
    suspend fun getRuntimeTraceEvents(turnId: String): List<RuntimeTraceEvent>
    suspend fun querySessionEvents(query: SessionEventQuery): List<SessionEvent>
    suspend fun replaySessionEvents(threadId: String, turnId: String): List<SessionEvent>
    suspend fun getSessionEventLogHealth(threadId: String, turnId: String): SessionEventLogHealth
    fun subscribeSessionEvents(threadId: String, listener: (SessionEvent) -> Unit): AutoCloseable

    companion object {
        const val MAX_HOST_SYSTEM_PROMPT_BYTES = cc.noharry.agent.kernel.core.context.MAX_HOST_SYSTEM_PROMPT_BYTES
        const val STATIC_PROMPT_SOFT_BUDGET_BYTES = cc.noharry.agent.kernel.core.context.STATIC_PROMPT_SOFT_BUDGET_BYTES
        const val RUN_CONTEXT_HEADER = cc.noharry.agent.kernel.core.context.RUN_CONTEXT_HEADER
        const val DEFERRED_TOOLS_HEADER = cc.noharry.agent.kernel.core.tools.DEFERRED_TOOLS_HEADER
        const val SESSION_MODEL_CALL_SOURCE_ROUND = "round"
        const val SESSION_MODEL_CALL_SOURCE_COMPACTION = "compaction"

        @JvmStatic fun validateHostSystemPrompt(text: String) = cc.noharry.agent.kernel.core.context.validateHostSystemPrompt(text)
        @JvmStatic fun qualifiedToolName(serverId: String, name: String): String =
            cc.noharry.agent.kernel.core.tools.mcpQualifiedName(serverId, name)

        /** Names of the runtime's native tools, including optional tools a host may disable. */
        @JvmStatic fun nativeToolNames(): Set<String> =
            cc.noharry.agent.kernel.core.tools.NATIVE_TOOLS.mapTo(linkedSetOf()) { it.name }

        @JvmStatic suspend fun toolCatalog(providers: List<ToolProvider>): ToolCatalogSnapshot =
            inspectToolCatalog(providers)
    }
}

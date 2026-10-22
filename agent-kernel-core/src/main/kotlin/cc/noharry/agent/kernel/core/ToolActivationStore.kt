@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.CompletionImpact
import cc.noharry.agent.kernel.api.RuntimeEffectKind
import java.util.concurrent.ConcurrentHashMap

class ToolActivationStore(
    /**
     * Most tools a session keeps for its next run. Past it the oldest go, down to half of it: one search_tools call
     * activates 10 to 20 tools, so the room left lets the list stay put for a few runs instead of shifting (and
     * breaking the prompt cache) on nearly every run.
     */
    private val maxSessionTools: Int = Int.MAX_VALUE,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val runs = ConcurrentHashMap<String, RunActivations>()

    fun recordCatalog(runId: String, tools: List<McpToolRef>) {
        if (tools.isEmpty()) {
            return
        }
        val activations = run(runId)
        synchronized(activations) {
            tools.forEach { tool ->
                activations.catalogTools[tool.qualifiedName] = CatalogToolRecord(
                    qualifiedName = tool.qualifiedName,
                    serverId = tool.serverId,
                    originalToolName = tool.originalToolName,
                )
            }
        }
    }

    fun activate(
        runId: String,
        tool: McpToolRef,
        schemaJson: String,
        metadata: ToolActivationMetadata = ToolActivationMetadata(),
    ) {
        if (tool.qualifiedName.isBlank() || schemaJson.isBlank()) {
            return
        }
        val activations = run(runId)
        synchronized(activations) {
            val normalizedMetadata = metadata.withDefaults(nowEpochMillis())
            val priorMetadata = activations.activated[tool.qualifiedName]?.metadata
            activations.catalogTools[tool.qualifiedName] = CatalogToolRecord(
                qualifiedName = tool.qualifiedName,
                serverId = tool.serverId,
                originalToolName = tool.originalToolName,
            )
            activations.activated[tool.qualifiedName] = ToolActivationRecord(
                toolName = tool.qualifiedName,
                serverId = tool.serverId,
                originalToolName = tool.originalToolName,
                schemaJson = schemaJson,
                metadata = normalizedMetadata.copy(
                    suppliedByMemory = normalizedMetadata.suppliedByMemory || priorMetadata?.suppliedByMemory == true,
                ),
                effectKind = parseEffectKind(tool.descriptor.effectKind),
                completionImpact = parseCompletionImpact(tool.descriptor.completionImpact),
                description = tool.descriptor.description,
            )
        }
    }

    fun activated(runId: String): List<ToolActivationRecord> {
        val activations = run(runId)
        return synchronized(activations) {
            activations.activated.values.toList()
        }
    }

    fun activationCountFrom(runId: String, source: String): Int {
        val activations = runs[runKey(runId)] ?: return 0
        return synchronized(activations) {
            activations.activated.values.count { it.metadata.source == source }
        }
    }

    fun classify(
        runId: String,
        requestedModelToolName: String,
    ): BackendToolClassification {
        val activations = runs[runKey(runId)] ?: return BackendToolClassification.UnsupportedNativeFallback
        synchronized(activations) {
            if (activations.catalogTools.isEmpty()) {
                return BackendToolClassification.UnsupportedNativeFallback
            }
            for (catalog in activations.catalogTools.values.sortedBy { it.qualifiedName }) {
                if (catalog.qualifiedName in activations.activated) {
                    continue
                }
                if (catalog.qualifiedName == requestedModelToolName) {
                    return BackendToolClassification.KnownInactive(
                        toolName = catalog.qualifiedName,
                        schemaPath = "/mcp/tools/${catalog.qualifiedName}/schema.json",
                    )
                }
            }
            return BackendToolClassification.Unknown(requestedModelToolName)
        }
    }

    fun clear(runId: String) {
        runs.remove(runKey(runId))
    }

    /**
     * Copies activated tools from the session-level cache into a new run.
     * Called at run start to provide session continuity (SPEC-CDR-003).
     */
    fun inheritSessionActivations(runId: String, sessionId: String) {
        val runActivations = run(runId)
        val sessionState = sessionActivationStates.computeIfAbsent(sessionId) { SessionActivationState() }
        val (runStart, cached) = synchronized(sessionState) {
            sessionState.latestStartedGeneration += 1
            SessionRunStart(
                sessionId = sessionId,
                generation = sessionState.latestStartedGeneration,
            ) to sessionState.cache
        }
        synchronized(runActivations) {
            runActivations.sessionRunStart = runStart
            cached?.activated.orEmpty().forEach { (name, record) ->
                if (record.metadata.isMemorySupplied()) {
                    return@forEach
                }
                if (name !in runActivations.activated) {
                    runActivations.activated[name] = record.copy(
                        metadata = record.metadata.copy(source = SESSION_INHERITED_ACTIVATION_SOURCE),
                    )
                    runActivations.catalogTools[name] = CatalogToolRecord(
                        qualifiedName = name,
                        serverId = record.serverId,
                        originalToolName = record.originalToolName,
                    )
                }
            }
        }
    }

    /**
     * Snapshots the current run's activated tools to the session-level cache.
     * Called at run terminal state so the next run in the same session can inherit.
     */
    fun snapshotSessionActivations(runId: String, sessionId: String) {
        val runActivations = runs[runKey(runId)] ?: return
        val terminalSnapshot = synchronized(runActivations) {
            val runStart = runActivations.sessionRunStart
                ?.takeIf { it.sessionId == sessionId }
                ?: return
            val inheritable = runActivations.activated
                .filterValues { !it.metadata.isMemorySupplied() }
                .let { kept ->
                    if (kept.size <= maxSessionTools) kept else {
                        kept.entries.toList().takeLast(maxSessionTools / 2).associateTo(linkedMapOf()) { it.key to it.value }
                    }
                }
            SessionTerminalSnapshot(
                generation = runStart.generation,
                activated = inheritable,
            )
        }
        val sessionState = sessionActivationStates[sessionId] ?: return
        synchronized(sessionState) {
            if (terminalSnapshot.generation != sessionState.latestStartedGeneration) {
                return
            }
            sessionState.cache = terminalSnapshot.activated
                .takeIf { it.isNotEmpty() }
                ?.let { activated ->
                    SessionActivationCache(
                        activated = activated,
                        snapshotAtEpochMillis = nowEpochMillis(),
                        runStartGeneration = terminalSnapshot.generation,
                    )
                }
        }
    }

    /**
     * Drops tools this run inherited that the current catalog no longer has (a server switched off since the run that
     * found them). The rest keep their place, so the tool list stays a prefix of the earlier one.
     */
    fun dropInheritedOutside(runId: String, catalogNames: Set<String>) {
        val activations = runs[runKey(runId)] ?: return
        if (catalogNames.isEmpty()) return
        synchronized(activations) {
            activations.activated.values.removeIf {
                it.metadata.source == SESSION_INHERITED_ACTIVATION_SOURCE && it.toolName !in catalogNames
            }
        }
    }

    fun clearSession(sessionId: String) {
        val sessionState = sessionActivationStates[sessionId] ?: return
        synchronized(sessionState) {
            sessionState.latestStartedGeneration += 1
            sessionState.cache = null
        }
    }

    fun clearAllSessions() {
        sessionActivationStates.keys.toList().forEach(::clearSession)
    }

    private val sessionActivationStates = ConcurrentHashMap<String, SessionActivationState>()

    private fun run(runId: String): RunActivations =
        runs.getOrPut(runKey(runId)) { RunActivations() }

    private fun runKey(runId: String): String =
        runId.ifBlank { DEFAULT_RUN_KEY }
}

fun parseEffectKind(token: String): RuntimeEffectKind =
    RuntimeEffectKind.entries.firstOrNull { it.name == token } ?: RuntimeEffectKind.UNKNOWN

fun parseCompletionImpact(token: String): CompletionImpact =
    CompletionImpact.entries.firstOrNull { it.name == token } ?: CompletionImpact.ADVISORY

data class ToolActivationRecord(
    val toolName: String,
    val serverId: String = "",
    val originalToolName: String = toolName,
    val schemaJson: String,
    val metadata: ToolActivationMetadata,
    val effectKind: cc.noharry.agent.kernel.api.RuntimeEffectKind = cc.noharry.agent.kernel.api.RuntimeEffectKind.UNKNOWN,
    val completionImpact: cc.noharry.agent.kernel.api.CompletionImpact = cc.noharry.agent.kernel.api.CompletionImpact.ADVISORY,
    /** The catalog's description, shown with the schema when the tool is announced rather than offered. */
    val description: String = "",
)

data class ToolActivationMetadata(
    val activatedAtEpochMillis: Long = 0,
    val cacheHit: Boolean = false,
    val schemaHash: String? = null,
    val schemaVersion: String? = null,
    val source: String = SCHEMA_JSON_ACTIVATION_SOURCE,
    val suppliedByMemory: Boolean = false,
) {
    fun withDefaults(nowEpochMillis: Long): ToolActivationMetadata =
        copy(
            activatedAtEpochMillis = activatedAtEpochMillis.takeIf { it > 0 } ?: nowEpochMillis,
            suppliedByMemory = isMemorySupplied(),
        )

    fun isMemorySupplied(): Boolean =
        suppliedByMemory || source == MEMORY_HINT_ACTIVATION_SOURCE
}

sealed interface BackendToolClassification {
    data class KnownInactive(
        val toolName: String,
        val schemaPath: String,
    ) : BackendToolClassification

    data class Unknown(val requestedName: String) : BackendToolClassification

    data object UnsupportedNativeFallback : BackendToolClassification
}

private class RunActivations {
    val catalogTools: MutableMap<String, CatalogToolRecord> = linkedMapOf()
    val activated: MutableMap<String, ToolActivationRecord> = linkedMapOf()
    var sessionRunStart: SessionRunStart? = null
}

private data class CatalogToolRecord(
    val qualifiedName: String,
    val serverId: String,
    val originalToolName: String,
)

data class SessionActivationCache(
    val activated: Map<String, ToolActivationRecord>,
    val snapshotAtEpochMillis: Long,
    val runStartGeneration: Long,
)

private class SessionActivationState {
    var latestStartedGeneration: Long = 0
    var cache: SessionActivationCache? = null
}

private data class SessionRunStart(
    val sessionId: String,
    val generation: Long,
)

private data class SessionTerminalSnapshot(
    val generation: Long,
    val activated: Map<String, ToolActivationRecord>,
)

private const val DEFAULT_RUN_KEY = "default"
private const val SCHEMA_JSON_ACTIVATION_SOURCE = "schema_json"
const val SESSION_INHERITED_ACTIVATION_SOURCE = "session_inherited"
const val MEMORY_HINT_ACTIVATION_SOURCE = "memory_search_hint"

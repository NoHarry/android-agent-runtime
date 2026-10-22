package cc.noharry.agent.kernel.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class NativeLoopSummary(
    val readPaths: List<String> = emptyList(),
    val writeArtifacts: List<String> = emptyList(),
    val attemptedTools: List<String> = emptyList(),
    val modelRoundCount: Int = 0,
    val successfulBackendTools: List<String> = emptyList(),
    val backendToolFailures: List<BackendToolFailureSummary> = emptyList(),
    val toolRegistryWarnings: List<String> = emptyList(),
    val toolRegistryFailures: List<String> = emptyList(),
    val planSummaries: List<String> = emptyList(),
    val memoryRetrievalEvaluated: Boolean = false,
    val retrievedMemoryMatches: List<RuntimeMemoryMatchSummary> = emptyList(),
    val memoryCapabilityQueries: List<String> = emptyList(),
    val memoryHintTools: List<String> = emptyList(),
    val sessionInheritedTools: List<String> = emptyList(),
    /** Times the model ended a round with no tools / no accepted finish. */
    val emptyCompletionObservedCount: Int = 0,
    /** Times a budgeted empty-completion continue nudge was injected. */
    val emptyCompletionContinueCount: Int = 0,
    /** Last empty-completion kind wire name, if any. */
    val lastEmptyCompletionKind: String? = null,
)

data class BackendToolFailureSummary(
    val toolName: String,
    val code: String?,
)

data class RuntimeMemoryMatchSummary(
    val lessonId: String,
    val relevanceScore: Float,
    val matchedDomains: List<String>,
)

class NativeLoopSummaryBuilder {
    private val readPaths = linkedSetOf<String>()
    private val writeArtifacts = mutableListOf<String>()
    private val attemptedTools = mutableListOf<String>()
    private var modelRoundCount = 0
    private val successfulBackendTools = mutableListOf<String>()
    private val backendToolFailures = mutableListOf<BackendToolFailureSummary>()
    private val toolRegistryWarnings = linkedSetOf<String>()
    private val toolRegistryFailures = linkedSetOf<String>()
    private val planSummaries = mutableListOf<String>()
    private var memoryRetrievalEvaluated = false
    private val retrievedMemoryMatches = mutableListOf<RuntimeMemoryMatchSummary>()
    private val memoryCapabilityQueries = mutableListOf<String>()
    private val memoryHintTools = linkedSetOf<String>()
    private val sessionInheritedTools = linkedSetOf<String>()
    private var emptyCompletionObservedCount = 0
    private var emptyCompletionContinueCount = 0
    private var lastEmptyCompletionKind: String? = null

    fun recordModelRound() {
        modelRoundCount += 1
    }

    fun currentModelRoundCount(): Int = modelRoundCount

    fun successfulBackendToolCount(): Int = successfulBackendTools.size

    fun recordEmptyCompletion(kind: EmptyCompletionKind, continued: Boolean) {
        emptyCompletionObservedCount += 1
        lastEmptyCompletionKind = kind.name
        if (continued) emptyCompletionContinueCount += 1
    }

    fun emptyCompletionContinueCount(): Int = emptyCompletionContinueCount

    fun recordToolCall(toolName: String, argumentsJson: String) {
        attemptedTools += toolName
        when (toolName) {
            "list_dir",
            "read_file",
            "grep",
            -> pathField(argumentsJson)?.let { readPaths += it }
        }
    }

    fun recordToolResult(toolName: String, resultJson: String) {
        if (toolName == "write_file" || toolName == "edit_file") {
            writeArtifact(resultJson)?.let { writeArtifacts += it }
        }
    }

    fun recordBackendToolResult(toolName: String, resultJson: String) {
        val parsed = backendToolCallResult(resultJson)
        if (parsed.ok) {
            successfulBackendTools += toolName
        } else {
            backendToolFailures += BackendToolFailureSummary(toolName, parsed.code)
        }
    }

    fun recordToolRegistrySignals(toolSet: DynamicToolSet) {
        toolRegistryWarnings += toolSet.warnings
        toolRegistryFailures += toolSet.failures
    }

    fun recordMemoryHintTool(qualifiedName: String) {
        memoryHintTools += qualifiedName
    }

    fun recordSessionInheritedTools(qualifiedNames: Collection<String>) {
        sessionInheritedTools += qualifiedNames
    }

    /**
     * True when this run already advanced meaningful state (backend / workspace write).
     * Survives history compaction, unlike scanning [ModelMessage] tool rows.
     */
    fun hasStateAdvancingWork(): Boolean =
        successfulBackendTools.isNotEmpty() || writeArtifacts.isNotEmpty()

    fun recordMemorySelection(
        matches: List<RuntimeMemoryMatchSummary>,
        capabilityQueries: List<String>,
    ) {
        memoryRetrievalEvaluated = true
        retrievedMemoryMatches += matches
        memoryCapabilityQueries += capabilityQueries
    }

    fun build(): NativeLoopSummary = NativeLoopSummary(
        readPaths = readPaths.toList(),
        writeArtifacts = writeArtifacts.toList(),
        attemptedTools = attemptedTools.toList(),
        modelRoundCount = modelRoundCount,
        successfulBackendTools = successfulBackendTools.toList(),
        backendToolFailures = backendToolFailures.toList(),
        toolRegistryWarnings = toolRegistryWarnings.toList(),
        toolRegistryFailures = toolRegistryFailures.toList(),
        planSummaries = planSummaries.toList(),
        memoryRetrievalEvaluated = memoryRetrievalEvaluated,
        retrievedMemoryMatches = retrievedMemoryMatches.toList(),
        memoryCapabilityQueries = memoryCapabilityQueries.distinct(),
        memoryHintTools = memoryHintTools.toList(),
        sessionInheritedTools = sessionInheritedTools.toList(),
        emptyCompletionObservedCount = emptyCompletionObservedCount,
        emptyCompletionContinueCount = emptyCompletionContinueCount,
        lastEmptyCompletionKind = lastEmptyCompletionKind,
    )
}

private fun pathField(argumentsJson: String): String? =
    stringField(argumentsJson, "path")?.takeIf { it.startsWith("/") }

private fun writeArtifact(resultJson: String): String? {
    val json = try {
        Json.parseToJsonElement(resultJson) as? JsonObject
    } catch (_: RuntimeException) {
        null
    } ?: return null
    val path = json.string("path") ?: return null
    val bytes = json.string("bytes") ?: return "path=$path"
    val mode = json.string("mode")?.let { " mode=$it" }.orEmpty()
    val replacements = json.string("replacements")?.let { " replacements=$it" }.orEmpty()
    return "path=$path bytes=$bytes$mode$replacements"
}

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun stringField(json: String, name: String): String? =
    Regex(""""$name"\s*:\s*"((?:\\.|[^"])*)"""")
        .find(json)
        ?.groupValues
        ?.get(1)
        ?.unescapeJsonString()

private fun String.unescapeJsonString(): String =
    replace("\\\"", "\"").replace("\\\\", "\\")

/** Classification for empty / no-finish model rounds (probe + evidence only). */
enum class EmptyCompletionKind {
    /** No visible text and no tools (thinking may also be empty). */
    EMPTY_NO_OUTPUT,

    /** Thinking/reasoning only; no user-visible text and no tools. */
    THINKING_ONLY,

    /** Non-blank text but protocol finish not accepted (implicit finish disallowed). */
    TEXT_WITHOUT_FINISH,
}

fun classifyEmptyCompletion(text: String, thinking: String): EmptyCompletionKind {
    val visible = text.trim()
    val thought = thinking.trim()
    return when {
        visible.isNotEmpty() -> EmptyCompletionKind.TEXT_WITHOUT_FINISH
        thought.isNotEmpty() -> EmptyCompletionKind.THINKING_ONLY
        else -> EmptyCompletionKind.EMPTY_NO_OUTPUT
    }
}

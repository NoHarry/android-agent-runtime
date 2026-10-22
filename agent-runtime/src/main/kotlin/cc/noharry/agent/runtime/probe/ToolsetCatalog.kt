package cc.noharry.agent.runtime.probe

import cc.noharry.agent.kernel.api.model.ModelTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import cc.noharry.agent.kernel.core.DynamicToolSource
import cc.noharry.agent.kernel.core.ToolActivationStore
import cc.noharry.agent.kernel.core.NATIVE_EXEC_TOOLS
import cc.noharry.agent.kernel.core.NATIVE_NETWORK_TOOLS
import cc.noharry.agent.kernel.core.NATIVE_READ_TOOLS
import cc.noharry.agent.kernel.core.NATIVE_TODO_TOOLS
import cc.noharry.agent.kernel.core.NATIVE_WRITE_TOOLS
import cc.noharry.agent.kernel.core.TERMINAL_TOOLS
import cc.noharry.agent.kernel.core.INTERACTION_TOOLS

/** Digest placeholder when schema JSON cannot be canonicalized (A6 fail-closed). */
internal const val SCHEMA_DIGEST_ERROR = "schema-error"

/**
 * Builds the advertised model tool catalog for run-meta fingerprints (A6 / SPEC-RPB-003).
 * Snapshot at finish time so progressive MCP activations are included.
 * Never throws into the control path — schema failures use [SCHEMA_DIGEST_ERROR], which
 * [buildRunMeta] treats as incomplete so comparable=false.
 */
internal fun snapshotAdvertisedToolset(
    runId: String,
    activationStore: ToolActivationStore,
    nativeWebFetchEnabled: Boolean = true,
    nativeExecEnabled: Boolean = false,
    disabledNativeTools: Set<String> = emptySet(),
): List<ToolCatalogEntry> {
    val disabled = cc.noharry.agent.kernel.core.snapshotDisabledNativeTools(disabledNativeTools)
    val entries = mutableListOf<ToolCatalogEntry>()
    fun addAll(tools: List<ModelTool>, source: String) {
        tools.filterNot { it.name in disabled }.forEach { entries += toolCatalogEntry(it.name, source, it.schemaJson) }
    }
    addAll(NATIVE_READ_TOOLS, "NATIVE")
    addAll(NATIVE_WRITE_TOOLS, "NATIVE")
    if (nativeWebFetchEnabled) {
        addAll(NATIVE_NETWORK_TOOLS, "NETWORK")
    }
    if (nativeExecEnabled) addAll(NATIVE_EXEC_TOOLS, "NATIVE")
    addAll(NATIVE_TODO_TOOLS, "NATIVE")
    addAll(TERMINAL_TOOLS, "NATIVE")
    addAll(INTERACTION_TOOLS, "NATIVE")
    for (activation in activationStore.activated(runId)) {
        if (activation.schemaJson.isBlank()) continue
        entries += toolCatalogEntry(activation.toolName, "BACKEND", activation.schemaJson)
    }
    return entries.sortedBy { it.qualifiedName }
}

internal fun toolCatalogEntry(
    qualifiedName: String,
    source: String,
    schemaJson: String,
): ToolCatalogEntry {
    val digest = runCatching { schemaDigestFromJsonText(schemaJson) }
        .getOrElse { SCHEMA_DIGEST_ERROR }
    return ToolCatalogEntry(
        qualifiedName = qualifiedName,
        source = source,
        schemaDigest = digest,
    )
}

internal fun schemaDigestFromJsonText(schemaJson: String): String {
    val element = Json.parseToJsonElement(schemaJson)
    val tree = element.toExactSchemaTree()
    return FingerprintCanonicalizer.schemaDigest(tree)
}

internal fun DynamicToolSource.toCatalogSource(): String =
    when (this) {
        DynamicToolSource.BACKEND -> "BACKEND"
        DynamicToolSource.NETWORK -> "NETWORK"
        else -> "NATIVE"
    }

/**
 * Convert kotlinx JSON to a SchemaCanonical tree.
 * Numbers stay as exact decimal tokens (never Double).
 */
internal fun JsonElement.toExactSchemaTree(): Any? =
    when (this) {
        is JsonNull -> null
        is JsonPrimitive -> {
            booleanOrNull?.let { return it }
            contentOrNull?.let { text ->
                if (isString) return text
                // bare number / null already handled; reject NaN-like
                return FingerprintCanonicalizer.parseExactDecimalToken(text)
            }
            null
        }
        is JsonObject -> entries.associate { (k, v) -> k to v.toExactSchemaTree() }
        is JsonArray -> map { it.toExactSchemaTree() }
        else -> toString()
    }

/** SHA-256 hex of UTF-8 prompt bytes for CaseBinding.promptSha256. */
internal fun promptSha256Hex(prompt: String): String =
    FingerprintCanonicalizer.sha256Hex(prompt)

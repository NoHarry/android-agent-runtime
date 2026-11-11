package cc.noharry.agent.appserver.protocol.view

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ArtifactPreview

/** The newest HTML file the agent wrote in [items], as an inline preview. */
fun latestHtmlArtifact(
    items: List<ConversationItem>,
): ArtifactPreview? =
    items.asReversed()
        .asSequence()
        .filterIsInstance<ConversationItem.ToolCallItem>()
        .mapNotNull { it.toHtmlArtifactPreview() }
        .firstOrNull()

/** Inline preview payload for a completed HTML `write_file` row, or null when not previewable. */
fun ConversationItem.ToolCallItem.toHtmlArtifactPreview(): ArtifactPreview? {
    if (status != ToolCallStatus.DONE || toolName != WRITE_FILE_TOOL) return null
    // Public tool results arrive as plain JSON ({"ok":true,"path":...}); the raw hook
    // wraps the same payload in a `result={...}` line. Accept both shapes.
    val payload = resultJson?.let(::artifactResultPayload) ?: return null
    if (!payload.stringField("ok").equals("true", ignoreCase = true)) return null
    val path = payload.stringField("path") ?: return null
    if (!path.isHtmlArtifactPath()) return null
    // Arguments may be replaced by the redacted placeholder or trimmed past JSON
    // integrity for oversized pages; both simply skip the preview.
    val html = argumentsJson?.parseArtifactArguments()?.stringField("content")
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return ArtifactPreview(
        path = path,
        html = html,
        bytes = payload.stringField("bytes")?.toLongOrNull() ?: html.toByteArray(Charsets.UTF_8).size.toLong(),
        refreshToken = "${path}:${html.hashCode()}",
    )
}

private fun artifactResultPayload(resultJson: String): JsonObject? =
    resultJson.parseArtifactJson()
        ?: resultJson.lineValue("result")?.parseArtifactJson()

private fun String.parseArtifactJson(): JsonObject? =
    runCatching { ArtifactPreviewJson.parseToJsonElement(this) as? JsonObject }.getOrNull()

/** Tool rows may carry raw `arguments={...}` prefixed bodies; strip the prefix before parsing. */
private fun String.parseArtifactArguments(): JsonObject? =
    parseArtifactJson()
        ?: substringAfter("arguments=", "").takeIf { it.startsWith("{") }?.parseArtifactJson()

private fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun String.isHtmlArtifactPath(): Boolean {
    val lower = lowercase()
    return HTML_ARTIFACT_SUFFIXES.any { lower.endsWith(it) }
}

private val ArtifactPreviewJson = Json { ignoreUnknownKeys = true }
private val HTML_ARTIFACT_SUFFIXES = listOf(".html", ".htm")
private const val WRITE_FILE_TOOL = "write_file"

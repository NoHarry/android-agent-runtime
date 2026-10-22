@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

internal const val MANIFEST_SCHEMA_VERSION = 2
internal const val MANIFEST_FILE = "manifest.json"
internal const val SEGMENTS_DIR = "segments"
internal const val BLOBS_DIR = "blobs"
internal const val SEGMENT_ROLLOVER_BYTES = 2L * 1024 * 1024
internal const val BLOB_INLINE_THRESHOLD_BYTES = 512L * 1024
internal const val BLOB_ENCODING = "blob"
internal val SEGMENT_FILE_NAME = Regex("^\\d{6}\\.jsonl$")

internal data class StreamManifest(
    val schemaVersion: Int = MANIFEST_SCHEMA_VERSION,
    val sessionId: String,
    val runId: String,
    val activeSegment: Int,
    val lastAcceptedSequence: Long? = null,
    val lastPersistedSequence: Long? = null,
    val segments: List<SegmentRecord> = emptyList(),
)

internal data class SegmentRecord(
    val index: Int,
    val fileName: String,
    val closed: Boolean,
    val eventCount: Int,
    val firstSequence: Long?,
    val lastSequence: Long?,
    val sha256: String?,
    val byteLength: Long,
)

internal fun segmentFileName(index: Int): String = "%06d.jsonl".format(index)

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun encodeManifest(manifest: StreamManifest): String = buildJsonObject {
    put("schemaVersion", manifest.schemaVersion)
    put("sessionId", manifest.sessionId)
    put("runId", manifest.runId)
    put("activeSegment", manifest.activeSegment)
    manifest.lastAcceptedSequence?.let { put("lastAcceptedSequence", it) }
    manifest.lastPersistedSequence?.let { put("lastPersistedSequence", it) }
    put(
        "segments",
        buildJsonArray {
            manifest.segments.forEach { segment ->
                add(
                    buildJsonObject {
                        put("index", segment.index)
                        put("fileName", segment.fileName)
                        put("closed", segment.closed)
                        put("eventCount", segment.eventCount)
                        segment.firstSequence?.let { put("firstSequence", it) }
                        segment.lastSequence?.let { put("lastSequence", it) }
                        segment.sha256?.let { put("sha256", it) }
                        put("byteLength", segment.byteLength)
                    },
                )
            }
        },
    )
}.toString()

internal fun decodeManifest(value: String): StreamManifest {
    val json = Json.parseToJsonElement(value) as? JsonObject ?: error("Stream manifest must be an object")
    check(json.requiredTypedInt("schemaVersion") == MANIFEST_SCHEMA_VERSION) { "Unsupported stream manifest schema" }
    val segments = json["segments"] as? JsonArray ?: error("Missing array segments")
    return StreamManifest(
        schemaVersion = MANIFEST_SCHEMA_VERSION,
        sessionId = json.requiredTypedString("sessionId"),
        runId = json.requiredTypedString("runId"),
        activeSegment = json.requiredTypedInt("activeSegment"),
        lastAcceptedSequence = json.optionalTypedLong("lastAcceptedSequence"),
        lastPersistedSequence = json.optionalTypedLong("lastPersistedSequence"),
        segments = segments.map { element ->
            val obj = element as? JsonObject ?: error("Segment record must be an object")
            SegmentRecord(
                index = obj.requiredTypedInt("index"),
                fileName = obj.requiredTypedString("fileName"),
                closed = obj.optionalTypedBoolean("closed") ?: false,
                eventCount = obj.requiredTypedInt("eventCount"),
                firstSequence = obj.optionalTypedLong("firstSequence"),
                lastSequence = obj.optionalTypedLong("lastSequence"),
                sha256 = obj.optionalTypedString("sha256"),
                byteLength = obj.optionalTypedLong("byteLength") ?: 0L,
            )
        },
    )
}

internal fun encodeBlobEnvelope(sha256: String, byteSize: Long): String = buildJsonObject {
    put("schemaVersion", EVENT_SCHEMA_VERSION)
    put("encoding", BLOB_ENCODING)
    put("sha256", sha256)
    put("byteSize", byteSize)
}.toString()

internal fun blobEnvelopeOrNull(value: String): Pair<String, Long>? {
    val json = Json.parseToJsonElement(value) as? JsonObject ?: return null
    val encoding = json.optionalTypedString("encoding") ?: return null
    if (encoding != BLOB_ENCODING) return null
    return json.requiredTypedString("sha256") to (json.optionalTypedLong("byteSize") ?: error("blob byteSize"))
}

private fun JsonObject.requiredTypedInt(key: String): Int {
    val primitive = this[key] as? JsonPrimitive ?: error("Missing int $key")
    if (primitive.isString) error("$key must be a numeric primitive")
    return primitive.content.toIntOrNull() ?: error("Missing int $key")
}

internal fun emptyManifest(sessionId: String, runId: String): StreamManifest = StreamManifest(
    sessionId = sessionId,
    runId = runId,
    activeSegment = 1,
    segments = listOf(
        SegmentRecord(
            index = 1,
            fileName = segmentFileName(1),
            closed = false,
            eventCount = 0,
            firstSequence = null,
            lastSequence = null,
            sha256 = null,
            byteLength = 0L,
        ),
    ),
)

@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionEvent
import cc.noharry.agent.kernel.api.SessionEventAppend
import cc.noharry.agent.kernel.api.SessionEventLogDurability
import cc.noharry.agent.kernel.api.SessionEventLogHealth
import cc.noharry.agent.kernel.api.SessionEventLogHealthStatus
import cc.noharry.agent.kernel.api.SessionEventQuery
import cc.noharry.agent.kernel.api.SessionEventType
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Collections

internal const val EVENT_SCHEMA_VERSION = 2
internal const val HEALTH_SCHEMA_VERSION = 1
internal const val FIRST_EVENT_SEQUENCE = 1L
internal const val MAX_EVENT_IDENTITY_CHARS = 256
internal const val SESSION_EVENTS_DIR = "session-events"
internal const val SESSION_EVENTS_VERSION_DIR = "v2"
internal const val SESSION_EVENTS_LEGACY_VERSION_DIR = "v1"
internal const val STREAMS_DIR = "streams"
internal const val HEALTH_DIR = "health"
internal const val FAILURE_APPEND_REJECTED = "APPEND_REJECTED"
internal const val FAILURE_APPEND_FAILED = "APPEND_FAILED"
internal const val FAILURE_STREAM_CORRUPT = "STREAM_CORRUPT"
internal const val FAILURE_STREAM_TRUNCATED = "STREAM_TRUNCATED"
internal const val FAILURE_SEQUENCE_GAP = "SEQUENCE_GAP"
internal const val FAILURE_STREAM_LIMIT = "STREAM_LIMIT"
internal const val FAILURE_SIDECAR_UNTRUSTED = "SIDECAR_UNTRUSTED"
internal const val MAX_FAILURE_CODE_CHARS = 64

internal data class EventStreamKey(val sessionId: String, val runId: String)

internal class MutableStreamHealth(
    val backendDurability: SessionEventLogDurability,
) {
    var attemptedCount: Int = 0
    var appendedCount: Int = 0
    var failedCount: Int = 0
    var firstFailureCode: String? = null
    var lastAcceptedSequence: Long? = null
    var lastPersistedSequence: Long? = null
    var sidecarWriteFailed: Boolean = false

    fun snapshot(): SessionEventLogHealth = SessionEventLogHealth(
        status = derivedStatus(),
        attemptedCount = attemptedCount,
        appendedCount = appendedCount,
        failedCount = failedCount,
        firstFailureCode = firstFailureCode,
        lastAcceptedSequence = lastAcceptedSequence,
        lastPersistedSequence = lastPersistedSequence,
        backendDurability = backendDurability,
        recorderCoverageVersion = SessionEventLogHealth.RECORDER_COVERAGE_VERSION,
    )

    fun noteAttempt() {
        attemptedCount += 1
    }

    fun noteAccepted(sequence: Long) {
        lastAcceptedSequence = sequence
    }

    fun noteSuccess(sequence: Long) {
        appendedCount += 1
        lastPersistedSequence = sequence
        lastAcceptedSequence = sequence
    }

    fun noteFailure(code: String) {
        failedCount += 1
        if (firstFailureCode.isNullOrBlank()) {
            firstFailureCode = boundedSafeFailureCode(code) ?: FAILURE_APPEND_FAILED
        }
    }

    private fun derivedStatus(): SessionEventLogHealthStatus {
        if (attemptedCount == 0) return SessionEventLogHealthStatus.UNKNOWN
        if (backendDurability == SessionEventLogDurability.VOLATILE) {
            return when {
                failedCount == 0 -> SessionEventLogHealthStatus.VOLATILE
                appendedCount == 0 -> SessionEventLogHealthStatus.UNAVAILABLE
                else -> SessionEventLogHealthStatus.DEGRADED
            }
        }
        return when {
            appendedCount == 0 -> SessionEventLogHealthStatus.UNAVAILABLE
            lastAcceptedSequence != lastPersistedSequence -> SessionEventLogHealthStatus.DEGRADED
            failedCount == 0 && !sidecarWriteFailed -> SessionEventLogHealthStatus.COMPLETE
            else -> SessionEventLogHealthStatus.DEGRADED
        }
    }
}

internal fun validateAppendIdentity(event: SessionEventAppend) {
    validateEventIdentity(event.sessionId, "sessionId")
    validateEventIdentity(event.runId, "runId")
    require(event.requestId.isNotBlank()) { "requestId must not be blank" }
    require(event.requestId.length <= MAX_EVENT_IDENTITY_CHARS) { "requestId exceeds the bounded length" }
    require(event.sourceEventSequences.distinct().size == event.sourceEventSequences.size) {
        "sourceEventSequences must not contain duplicates"
    }
}

internal fun validateEventIdentity(value: String, field: String) {
    require(value.isNotBlank()) { "$field must not be blank" }
    require(value.length <= MAX_EVENT_IDENTITY_CHARS) { "$field exceeds the bounded length" }
}

internal fun validateQuery(query: SessionEventQuery) {
    require(query.sessionId.isNotBlank()) { "sessionId must not be blank" }
    val runId = query.runId
    val requestId = query.requestId
    val afterSequenceExclusive = query.afterSequenceExclusive
    val limit = query.limit
    require(runId == null || runId.isNotBlank()) { "runId must not be blank" }
    require(requestId == null || requestId.isNotBlank()) { "requestId must not be blank" }
    require(afterSequenceExclusive == null || runId != null) {
        "afterSequenceExclusive requires runId because sequences are stream-local"
    }
    require(afterSequenceExclusive == null || afterSequenceExclusive >= 0) {
        "afterSequenceExclusive must not be negative"
    }
    require(limit == null || limit > 0) { "limit must be positive" }
}

internal fun validateCausalSources(sources: List<Long>, stream: List<SessionEvent>, nextSequence: Long) {
    sources.forEach { source ->
        require(source >= FIRST_EVENT_SEQUENCE && source < nextSequence) {
            "source event sequence $source must refer to an earlier event in the same session/run"
        }
        require(stream.any { it.sequence == source }) {
            "source event sequence $source does not exist in the same session/run"
        }
    }
}

internal fun validatePersistedStream(events: List<SessionEvent>, firstSequence: Long = FIRST_EVENT_SEQUENCE) {
    val ids = mutableSetOf<String>()
    val streamIdentity = events.firstOrNull()?.let { it.sessionId to it.runId }
    events.forEachIndexed { index, event ->
        check(event.schemaVersion == EVENT_SCHEMA_VERSION) { "Unsupported session event schema" }
        check(event.eventId.isNotBlank() && ids.add(event.eventId)) { "Invalid or duplicate eventId" }
        check(event.sessionId.isNotBlank() && event.runId.isNotBlank() && event.requestId.isNotBlank()) {
            "Persisted event envelope contains a blank identity"
        }
        check((event.sessionId to event.runId) == streamIdentity) { "Persisted JSONL mixes session/run streams" }
        check(event.sequence == index.toLong() + firstSequence) { "Persisted event sequence is not contiguous" }
        validatePayload(event.payload)
        // The reader (or owned writer cursor) has verified the preceding contiguous prefix.
        // Range membership proves existence without copying/scanning that prefix again.
        event.sourceEventSequences.forEach { source ->
            require(source >= FIRST_EVENT_SEQUENCE && source < event.sequence) {
                "source event sequence $source must refer to an earlier event in the same session/run"
            }
        }
    }
}

internal fun validateGlobalEventIds(events: List<SessionEvent>) {
    check(events.map { it.eventId }.toSet().size == events.size) { "Duplicate eventId exists in event logs" }
}


internal fun SessionEventAppend.toStoredEvent(
    eventId: String,
    sequence: Long,
    recordedAt: Long,
): SessionEvent = SessionEvent(
    schemaVersion = EVENT_SCHEMA_VERSION,
    eventId = eventId,
    sessionId = sessionId,
    runId = runId,
    requestId = requestId,
    sequence = sequence,
    recordedAtEpochMillis = recordedAt,
    type = type,
    payload = payload.frozenCopy(),
    sourceEventSequences = Collections.unmodifiableList(sourceEventSequences.toList()),
)

internal fun encodeEvent(event: SessionEvent): String = buildJsonObject {
    put("schemaVersion", event.schemaVersion)
    put("eventId", event.eventId)
    put("sessionId", event.sessionId)
    put("runId", event.runId)
    put("requestId", event.requestId)
    put("sequence", event.sequence)
    put("recordedAtEpochMillis", event.recordedAtEpochMillis)
    put("type", event.type.name)
    put("payload", encodePayload(event.payload))
    put("sourceEventSequences", buildJsonArray {
        event.sourceEventSequences.forEach { add(JsonPrimitive(it)) }
    })
}.toString()

internal data class DecodedHealthSidecar(
    val health: SessionEventLogHealth,
    val sessionId: String?,
    val runId: String?,
)

internal fun encodeHealth(
    health: SessionEventLogHealth,
    sessionId: String,
    runId: String,
): String = buildJsonObject {
    put("schemaVersion", HEALTH_SCHEMA_VERSION)
    put("status", health.status.name)
    put("attemptedCount", health.attemptedCount)
    put("appendedCount", health.appendedCount)
    put("failedCount", health.failedCount)
    health.firstFailureCode?.let { put("firstFailureCode", it) }
    health.lastAcceptedSequence?.let { put("lastAcceptedSequence", it) }
    health.lastPersistedSequence?.let { put("lastPersistedSequence", it) }
    put("backendDurability", health.backendDurability.name)
    put("recorderCoverageVersion", health.recorderCoverageVersion)
    put("sessionId", sessionId)
    put("runId", runId)
}.toString()

internal fun decodeEvent(value: String): SessionEvent {
    val json = Json.parseToJsonElement(value) as? JsonObject ?: error("Session event line must be an object")
    val payload = json.requiredObject("payload")
    val schemaVersion = json.requiredTypedInt("schemaVersion")
    check(schemaVersion == EVENT_SCHEMA_VERSION) { "Unsupported session event schema" }
    return SessionEvent(
        schemaVersion = schemaVersion,
        eventId = json.requiredTypedString("eventId"),
        sessionId = json.requiredTypedString("sessionId"),
        runId = json.requiredTypedString("runId"),
        requestId = json.requiredTypedString("requestId"),
        sequence = json.requiredTypedLong("sequence"),
        recordedAtEpochMillis = json.requiredTypedLong("recordedAtEpochMillis"),
        type = SessionEventType.valueOf(json.requiredTypedString("type")),
        payload = decodePayload(payload),
        sourceEventSequences = json.requiredTypedLongList("sourceEventSequences"),
    )
}

internal fun decodeHealth(value: String): SessionEventLogHealth = decodeHealthSidecar(value).health

internal fun decodeHealthSidecar(value: String): DecodedHealthSidecar {
    val json = Json.parseToJsonElement(value) as? JsonObject ?: error("Health sidecar must be an object")
    check(json.requiredTypedInt("schemaVersion") == HEALTH_SCHEMA_VERSION) { "Unsupported health sidecar schema" }
    val statusToken = json.requiredTypedString("status")
    val status = SessionEventLogHealth.healthStatusOrNull(statusToken)
        ?: error("Unsupported health status")
    val durabilityToken = json.requiredTypedString("backendDurability")
    val durability = SessionEventLogDurability.entries.firstOrNull { it.name == durabilityToken }
        ?: error("Unsupported health durability")
    val coverage = json.requiredTypedInt("recorderCoverageVersion")
    check(coverage == SessionEventLogHealth.RECORDER_COVERAGE_VERSION) { "Unsupported health coverage version" }
    val attemptedCount = json.requiredTypedInt("attemptedCount")
    val appendedCount = json.requiredTypedInt("appendedCount")
    val failedCount = json.requiredTypedInt("failedCount")
    check(attemptedCount >= 0 && appendedCount >= 0 && failedCount >= 0) { "Health counts must be nonnegative" }
    val rawFailureCode = json.optionalTypedString("firstFailureCode")
    val firstFailureCode = if (rawFailureCode == null) {
        null
    } else {
        boundedSafeFailureCode(rawFailureCode) ?: error("Unsafe firstFailureCode")
    }
    return DecodedHealthSidecar(
        health = SessionEventLogHealth(
            status = status,
            attemptedCount = attemptedCount,
            appendedCount = appendedCount,
            failedCount = failedCount,
            firstFailureCode = firstFailureCode,
            lastAcceptedSequence = json.optionalTypedLong("lastAcceptedSequence")
                ?: json.optionalTypedLong("lastPersistedSequence"),
            lastPersistedSequence = json.optionalTypedLong("lastPersistedSequence"),
            backendDurability = durability,
            recorderCoverageVersion = coverage,
        ),
        sessionId = json.optionalTypedString("sessionId"),
        runId = json.optionalTypedString("runId"),
    )
}

internal fun boundedSafeFailureCode(code: String?): String? {
    val trimmed = code?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed.length > MAX_FAILURE_CODE_CHARS) return null
    return trimmed.takeIf { SAFE_FAILURE_CODE.matches(it) }
}

internal fun sidecarIdentityMatches(
    sidecarSessionId: String?,
    sidecarRunId: String?,
    sessionId: String,
    runId: String,
): Boolean = sidecarSessionId != null &&
    sidecarRunId != null &&
    sidecarSessionId == sessionId &&
    sidecarRunId == runId

internal fun healthCountersExact(
    attemptedCount: Int,
    appendedCount: Int,
    failedCount: Int,
    readableCount: Int,
    lastPersistedSequence: Long?,
    lastReadableSequence: Long?,
): Boolean {
    if (attemptedCount < 0 || appendedCount < 0 || failedCount < 0) return false
    if (attemptedCount != appendedCount + failedCount) return false
    if (appendedCount != readableCount) return false
    return lastPersistedSequence == lastReadableSequence
}

internal fun sidecarEvidenceTrusted(
    sidecar: DecodedHealthSidecar?,
    sessionId: String,
    runId: String,
    readableCount: Int,
    lastReadableSequence: Long?,
): Boolean {
    if (sidecar == null) return false
    if (!sidecarIdentityMatches(sidecar.sessionId, sidecar.runId, sessionId, runId)) return false
    val health = sidecar.health
    return health.status == SessionEventLogHealthStatus.COMPLETE &&
        health.failedCount == 0 &&
        healthCountersExact(
            attemptedCount = health.attemptedCount,
            appendedCount = health.appendedCount,
            failedCount = health.failedCount,
            readableCount = readableCount,
            lastPersistedSequence = health.lastPersistedSequence,
            lastReadableSequence = lastReadableSequence,
        )
}

internal fun mergeLiveAndOfflineHealth(
    live: SessionEventLogHealth?,
    offline: SessionEventLogHealth,
): SessionEventLogHealth {
    if (live == null) return offline
    if (live.status == SessionEventLogHealthStatus.COMPLETE &&
        offline.status != SessionEventLogHealthStatus.COMPLETE
    ) {
        return offline
    }
    return live
}

internal fun safeFailureCode(failure: Throwable): String = when (failure) {
    is IllegalArgumentException -> FAILURE_APPEND_REJECTED
    is IllegalStateException -> when {
        failure.message.orEmpty().contains("partial or corrupt") -> FAILURE_STREAM_TRUNCATED
        failure.message.orEmpty().contains("not contiguous") -> FAILURE_SEQUENCE_GAP
        failure.message.orEmpty().contains("corrupt") -> FAILURE_STREAM_CORRUPT
        failure.message.orEmpty().contains("exceeds the replay limit") -> FAILURE_STREAM_LIMIT
        else -> FAILURE_APPEND_FAILED
    }
    else -> FAILURE_APPEND_FAILED
}

private fun JsonObject.requiredTypedInt(key: String): Int {
    val primitive = this[key] as? JsonPrimitive ?: error("Missing int $key")
    if (primitive.isString) error("$key must be a numeric primitive")
    return primitive.intOrNull ?: error("Missing int $key")
}

private fun JsonObject.requiredTypedLong(key: String): Long {
    val primitive = this[key] as? JsonPrimitive ?: error("Missing long $key")
    if (primitive.isString) error("$key must be a numeric primitive")
    return primitive.longOrNull ?: error("Missing long $key")
}

private fun JsonObject.requiredObject(key: String): JsonObject =
    this[key] as? JsonObject ?: error("Missing object $key")

private fun JsonObject.requiredTypedLongList(key: String): List<Long> {
    val values = this[key] as? JsonArray ?: error("Missing array $key")
    return values.map { element ->
        val primitive = element as? JsonPrimitive ?: error("$key values must be numeric primitives")
        if (primitive.isString) error("$key values must be numeric primitives")
        primitive.longOrNull ?: error("$key values must be longs")
    }
}

private val SAFE_FAILURE_CODE = Regex("^[A-Z][A-Z0-9_]{0,63}$")

internal fun appendForcedLine(file: File, line: String) {
    file.parentFile?.mkdirs()
    rejectSymlink(file.toPath())
    val created = !file.exists()
    RandomAccessFile(file, "rw").use { raf ->
        raf.seek(raf.length())
        raf.write((line + "\n").toByteArray(Charsets.UTF_8))
        raf.fd.sync()
        forceChannel(raf.channel)
    }
    // A new segment's name is in its directory, which a power cut can lose though the file's bytes were forced.
    if (created) forceDirectory(file.parentFile)
}

internal fun writeForcedText(file: File, text: String, durableName: Boolean = true) =
    writeForcedBytes(file, text.toByteArray(Charsets.UTF_8), durableName)

/** Writes [bytes] to [file] through a forced temporary file; [durableName] also forces the rename into the directory. */
internal fun writeForcedBytes(file: File, bytes: ByteArray, durableName: Boolean = true) {
    file.parentFile?.mkdirs()
    file.parentFile?.let { if (existsNoFollow(it)) rejectLink(it.toPath()) }
    if (existsNoFollow(file)) rejectLink(file.toPath())
    val staging = File(file.parentFile, file.name + ".tmp")
    if (existsNoFollow(staging)) rejectLink(staging.toPath())
    RandomAccessFile(staging, "rw").use { raf ->
        raf.setLength(0)
        raf.write(bytes)
        raf.fd.sync()
        forceChannel(raf.channel)
    }
    if (existsNoFollow(file)) rejectLink(file.toPath())
    if (!staging.renameTo(file)) {
        staging.copyTo(file, overwrite = true)
        staging.delete()
    }
    // The rename is an entry in the directory: until that is forced, a power cut can bring back the old file.
    if (durableName) forceDirectory(file.parentFile)
}

private fun forceChannel(channel: FileChannel) {
    channel.force(true)
}

/** Makes the entries created or renamed in [dir] durable; skipped where a directory cannot be opened (Windows). */
internal fun forceDirectory(dir: File?) {
    if (dir == null) return
    try {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    } catch (_: IOException) {
    } catch (_: UnsupportedOperationException) {
    }
}

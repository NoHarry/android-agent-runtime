package cc.noharry.agent.runtime.probe

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Encode one probe event as a single JSONL line (UTF-8). */
internal fun encodeProbeEventJsonLine(event: ProbeEvent): String =
    buildJsonObject {
        put("schemaVersion", event.schemaVersion)
        put("runId", event.runId)
        event.sessionId?.let { put("sessionId", it) }
        event.requestId?.let { put("requestId", it) }
        put("sequence", event.sequence)
        put("stageId", event.stageId)
        put("probeId", event.probeId)
        event.epochMillis?.let { put("epochMillis", it) }
        put("elapsedNanos", event.elapsedNanos)
        if (event.attrs.isNotEmpty()) {
            put("attrs", attrsToJson(event.attrs))
        }
        if (event.truncated) {
            put("truncated", true)
        }
    }.toString()

internal fun encodeProbeCompletenessJson(summary: ProbeCompleteness): String =
    buildJsonObject {
        put("schemaVersion", summary.schemaVersion)
        put("runId", summary.runId)
        put("sequenceMax", summary.sequenceMax)
        put("enqueued", summary.enqueued)
        put("written", summary.written)
        put("dropped", summary.dropped)
        put("truncated", summary.truncated)
        put("error", summary.error)
        put("drainTimedOut", summary.drainTimedOut)
        put("sinkIoFailed", summary.sinkIoFailed)
        put("completenessIoFailed", summary.completenessIoFailed)
        put("complete", summary.complete)
        if (summary.probesDisabled) {
            put("probesDisabled", true)
        }
    }.toString()

internal fun attrsToJson(attrs: Map<String, ProbeAttrValue>): JsonObject =
    buildJsonObject {
        attrs.forEach { (key, value) ->
            put(key, attrToJson(value))
        }
    }

private fun attrToJson(value: ProbeAttrValue): JsonElement =
    when (value) {
        is ProbeAttrValue.Str -> JsonPrimitive(value.value)
        is ProbeAttrValue.Num -> JsonPrimitive(value.value)
        is ProbeAttrValue.Bool -> JsonPrimitive(value.value)
        is ProbeAttrValue.Obj -> attrsToJson(value.value)
        is ProbeAttrValue.Arr -> buildJsonArray {
            value.value.forEach { add(attrToJson(it)) }
        }
    }

internal fun estimateAttrsUtf8Bytes(attrs: Map<String, ProbeAttrValue>): Int =
    attrsToJson(attrs).toString().toByteArray(Charsets.UTF_8).size

internal fun coerceAttrValue(raw: Any?): ProbeAttrValue? =
    when (raw) {
        null -> null
        is ProbeAttrValue -> raw
        is String -> ProbeAttrValue.Str(raw)
        is Boolean -> ProbeAttrValue.Bool(raw)
        is Number -> ProbeAttrValue.Num(raw.toDouble())
        is Map<*, *> -> {
            val nested = linkedMapOf<String, ProbeAttrValue>()
            raw.forEach { (k, v) ->
                val key = k as? String ?: return@forEach
                coerceAttrValue(v)?.let { nested[key] = it }
            }
            ProbeAttrValue.Obj(nested)
        }
        is List<*> -> ProbeAttrValue.Arr(raw.mapNotNull { coerceAttrValue(it) })
        else -> ProbeAttrValue.Str(raw.toString())
    }

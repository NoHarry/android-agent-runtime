package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.reliability.CompletionItem
import cc.noharry.agent.kernel.api.reliability.OpenConfusion
import cc.noharry.agent.kernel.api.reliability.RuntimeBlocker
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidence
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidenceType
import cc.noharry.agent.kernel.api.reliability.RuntimeHandoff
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequestStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunEnvelope
import cc.noharry.agent.kernel.api.reliability.RuntimeRunPhase
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeTraceEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeWorkpad
import cc.noharry.agent.kernel.api.reliability.SuggestedNextAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal object ReliabilityJsonCodecs {
    fun encodeRun(snapshot: RuntimeRunSnapshot, envelope: RuntimeRunEnvelope): String =
        buildJsonObject {
            put("runId", snapshot.runId)
            put("requestId", envelope.requestId)
            put("sessionId", snapshot.sessionId)
            put("status", snapshot.status.name)
            put("phase", envelope.phase.name)
            put("createdAtEpochMillis", envelope.createdAtEpochMillis)
            put("updatedAtEpochMillis", snapshot.updatedAtEpochMillis)
            snapshot.suggestedNextAction?.let { put("suggestedNextAction", it.name) }
            snapshot.pendingConfirmationId?.let { put("pendingConfirmationId", it) }
        }.toString()

    fun decodeSnapshot(value: String): RuntimeRunSnapshot {
        val json = obj(value)
        return RuntimeRunSnapshot(
            runId = json.string("runId"),
            sessionId = json.string("sessionId"),
            status = RuntimeRunStatus.valueOf(json.string("status")),
            updatedAtEpochMillis = json.long("updatedAtEpochMillis"),
            suggestedNextAction = json.optString("suggestedNextAction")?.let(SuggestedNextAction::valueOf),
            pendingConfirmationId = json.optString("pendingConfirmationId"),
        )
    }

    fun decodeEnvelope(value: String): RuntimeRunEnvelope {
        val json = obj(value)
        return RuntimeRunEnvelope(
            runId = json.string("runId"),
            requestId = json.string("requestId"),
            sessionId = json.string("sessionId"),
            status = RuntimeRunStatus.valueOf(json.string("status")),
            phase = RuntimeRunPhase.valueOf(json.string("phase")),
            createdAtEpochMillis = json.long("createdAtEpochMillis"),
            updatedAtEpochMillis = json.long("updatedAtEpochMillis"),
        )
    }

    fun encodeWorkpad(value: RuntimeWorkpad): String = buildJsonObject {
        put("runId", value.runId)
        put("updatedAtEpochMillis", value.updatedAtEpochMillis)
        putStringList("readPaths", value.readPaths)
        putStringList("writeArtifacts", value.writeArtifacts)
        putStringList("attemptedTools", value.attemptedTools)
        putStringList("toolRegistryWarnings", value.toolRegistryWarnings)
        putStringList("toolRegistryFailures", value.toolRegistryFailures)
        putStringList("planSummaries", value.planSummaries)
        putJsonArray("blockers") { value.blockers.forEach { add(encodeBlocker(it)) } }
        putJsonArray("completionBar") { value.completionBar.forEach { add(encodeCompletionItem(it)) } }
    }.toString()

    fun decodeWorkpad(value: String): RuntimeWorkpad {
        val json = obj(value)
        return RuntimeWorkpad(
            runId = json.string("runId"),
            readPaths = json.stringList("readPaths"),
            writeArtifacts = json.stringList("writeArtifacts"),
            attemptedTools = json.stringList("attemptedTools"),
            toolRegistryWarnings = json.stringList("toolRegistryWarnings"),
            toolRegistryFailures = json.stringList("toolRegistryFailures"),
            planSummaries = json.stringList("planSummaries"),
            blockers = json.objects("blockers").map(::decodeBlocker),
            completionBar = json.objects("completionBar").map(::decodeCompletionItem),
            updatedAtEpochMillis = json.long("updatedAtEpochMillis"),
        )
    }

    fun encodeResult(value: RuntimeResult): String = buildJsonObject {
        put("runId", value.runId)
        put("status", value.status.name)
        put("response", value.response)
        putStringList("evidenceIds", value.evidenceIds)
        value.errorCode?.let { put("errorCode", it) }
        put("recoverable", value.recoverable)
        put("completion", encodeCompletion(value.completion))
    }.toString()

    fun decodeResult(value: String): RuntimeResult {
        val json = obj(value)
        return RuntimeResult(
            runId = json.string("runId"),
            status = RuntimeRunStatus.valueOf(json.string("status")),
            response = json.string("response"),
            evidenceIds = json.stringList("evidenceIds"),
            errorCode = json.optString("errorCode"),
            recoverable = json.boolOrDefault("recoverable", true),
            completion = json.objOrNull("completion")?.let(::decodeCompletion)
                ?: cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary.unknown(),
        )
    }

    fun encodeHandoff(value: RuntimeHandoff): String = encodeHandoffJson(value).toString()

    fun decodeHandoff(value: String): RuntimeHandoff = decodeHandoff(obj(value))

    fun encodeOpenConfusion(value: OpenConfusion): String = buildJsonObject {
        put("runId", value.runId)
        put("sessionId", value.sessionId)
        put("question", value.question)
        put("suggestedNextAction", value.suggestedNextAction.name)
        put("createdAtEpochMillis", value.createdAtEpochMillis)
    }.toString()

    fun decodeOpenConfusion(value: String): OpenConfusion {
        val json = obj(value)
        return OpenConfusion(
            runId = json.string("runId"),
            sessionId = json.string("sessionId"),
            question = json.string("question"),
            suggestedNextAction = SuggestedNextAction.valueOf(json.string("suggestedNextAction")),
            createdAtEpochMillis = json.long("createdAtEpochMillis"),
        )
    }

    fun encodeEvidence(value: RuntimeEvidence): String = buildJsonObject {
        put("evidenceId", value.evidenceId)
        put("runId", value.runId)
        put("type", value.type.name)
        put("summary", value.summary)
        put("createdAtEpochMillis", value.createdAtEpochMillis)
        value.traceEventName?.let { put("traceEventName", it) }
    }.toString()

    fun decodeEvidence(value: String): RuntimeEvidence {
        val json = obj(value)
        return RuntimeEvidence(
            evidenceId = json.string("evidenceId"),
            runId = json.string("runId"),
            type = RuntimeEvidenceType.valueOf(json.string("type")),
            summary = json.string("summary"),
            createdAtEpochMillis = json.long("createdAtEpochMillis"),
            traceEventName = json.optString("traceEventName"),
        )
    }

    fun encodeTrace(value: RuntimeTraceEvent): String = buildJsonObject {
        put("runId", value.runId)
        put("eventName", value.eventName)
        put("createdAtEpochMillis", value.createdAtEpochMillis)
        value.evidenceId?.let { put("evidenceId", it) }
    }.toString()

    fun decodeTrace(value: String): RuntimeTraceEvent {
        val json = obj(value)
        return RuntimeTraceEvent(
            runId = json.string("runId"),
            eventName = json.string("eventName"),
            createdAtEpochMillis = json.long("createdAtEpochMillis"),
            evidenceId = json.optString("evidenceId"),
        )
    }

    fun encodeProtocolRequest(value: RuntimeProtocolRequest): String = buildJsonObject {
        put("protocolRequestId", value.protocolRequestId)
        put("runId", value.runId)
        value.sessionId?.let { put("sessionId", it) }
        put("status", value.status.name)
        put("kind", value.kind)
        put("payloadJson", value.payloadJson)
        put("createdAtEpochMillis", value.createdAtEpochMillis)
        value.expiresAtEpochMillis?.let { put("expiresAtEpochMillis", it) }
        value.resolvedAtEpochMillis?.let { put("resolvedAtEpochMillis", it) }
        value.resolutionEvidenceId?.let { put("resolutionEvidenceId", it) }
    }.toString()

    fun decodeProtocolRequest(value: String): RuntimeProtocolRequest {
        val json = obj(value)
        return RuntimeProtocolRequest(
            protocolRequestId = json.string("protocolRequestId"),
            runId = json.string("runId"),
            sessionId = json.optString("sessionId"),
            status = RuntimeProtocolRequestStatus.valueOf(json.string("status")),
            kind = json.string("kind"),
            payloadJson = json.string("payloadJson"),
            createdAtEpochMillis = json.long("createdAtEpochMillis"),
            expiresAtEpochMillis = json.optLong("expiresAtEpochMillis"),
            resolvedAtEpochMillis = json.optLong("resolvedAtEpochMillis"),
            resolutionEvidenceId = json.optString("resolutionEvidenceId"),
        )
    }
}

private fun encodeHandoffJson(value: RuntimeHandoff): JsonObject = buildJsonObject {
    put("runId", value.runId)
    put("sessionId", value.sessionId)
    put("summary", value.summary)
    putStringList("done", value.done)
    putStringList("nextActions", value.nextActions)
    putStringList("evidenceRefs", value.evidenceRefs)
    put("updatedAtEpochMillis", value.updatedAtEpochMillis)
    value.task?.let { put("task", it) }
    putStringList("decisions", value.decisions)
    putStringList("triedAndAbandoned", value.triedAndAbandoned)
    putStringList("doNotRepeat", value.doNotRepeat)
}

private fun decodeHandoff(json: JsonObject): RuntimeHandoff = RuntimeHandoff(
    runId = json.string("runId"),
    sessionId = json.string("sessionId"),
    summary = json.string("summary"),
    done = json.stringList("done"),
    nextActions = json.stringList("nextActions"),
    evidenceRefs = json.stringList("evidenceRefs"),
    updatedAtEpochMillis = json.long("updatedAtEpochMillis"),
    task = json.optString("task"),
    decisions = json.stringList("decisions"),
    triedAndAbandoned = json.stringList("triedAndAbandoned"),
    doNotRepeat = json.stringList("doNotRepeat"),
)

private fun encodeBlocker(value: RuntimeBlocker): JsonObject = buildJsonObject {
    put("runId", value.runId)
    put("code", value.code)
    put("message", value.message)
    put("suggestedNextAction", value.suggestedNextAction.name)
    value.evidenceId?.let { put("evidenceId", it) }
    value.handoff?.let { put("handoff", encodeHandoffJson(it)) }
}

private fun decodeBlocker(json: JsonObject): RuntimeBlocker = RuntimeBlocker(
    runId = json.string("runId"),
    code = json.string("code"),
    message = json.string("message"),
    suggestedNextAction = SuggestedNextAction.valueOf(json.string("suggestedNextAction")),
    handoff = json.objOrNull("handoff")?.let(::decodeHandoff),
    evidenceId = json.optString("evidenceId"),
)

private fun encodeCompletion(value: cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary): JsonObject = buildJsonObject {
    put("outcome", value.outcome.name)
    put("requiredTotal", value.requiredTotal)
    put("requiredSucceeded", value.requiredSucceeded)
    put("requiredUnresolved", value.requiredUnresolved)
    putStringList("unresolvedCodes", value.unresolvedCodes)
    put("projectionDigest", value.projectionDigest)
}

private fun decodeCompletion(json: JsonObject): cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary {
    val outcomeToken = json.optString("outcome")
    val outcome = cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome.entries
        .firstOrNull { it.name == outcomeToken }
        ?: cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome.UNKNOWN
    return cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary(
        outcome = outcome,
        requiredTotal = json.string("requiredTotal").toIntOrNull() ?: 0,
        requiredSucceeded = json.string("requiredSucceeded").toIntOrNull() ?: 0,
        requiredUnresolved = json.string("requiredUnresolved").toIntOrNull() ?: 0,
        unresolvedCodes = json.stringList("unresolvedCodes"),
        projectionDigest = json.optString("projectionDigest").orEmpty(),
    )
}

private fun encodeCompletionItem(value: CompletionItem): JsonObject = buildJsonObject {
    put("id", value.id)
    put("label", value.label)
    put("done", value.done)
}

private fun decodeCompletionItem(json: JsonObject): CompletionItem = CompletionItem(
    id = json.string("id"),
    label = json.string("label"),
    done = json.bool("done"),
)

private fun obj(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

private fun JsonObject.string(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: ""

private fun JsonObject.optString(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(name: String): Long =
    this[name]?.jsonPrimitive?.longOrNull ?: 0L

private fun JsonObject.optLong(name: String): Long? =
    this[name]?.jsonPrimitive?.longOrNull

private fun JsonObject.bool(name: String): Boolean =
    this[name]?.jsonPrimitive?.booleanOrNull == true

private fun JsonObject.boolOrDefault(name: String, default: Boolean): Boolean =
    this[name]?.jsonPrimitive?.booleanOrNull ?: default

private fun JsonObject.stringList(name: String): List<String> =
    (this[name] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }

private fun JsonObject.objects(name: String): List<JsonObject> =
    (this[name] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

private fun JsonObject.objOrNull(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObjectBuilder.putStringList(name: String, values: List<String>) {
    putJsonArray(name) { values.forEach { add(JsonPrimitive(it)) } }
}

private typealias JsonObjectBuilder = kotlinx.serialization.json.JsonObjectBuilder

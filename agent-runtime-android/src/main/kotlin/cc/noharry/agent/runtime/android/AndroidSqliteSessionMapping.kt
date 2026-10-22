package cc.noharry.agent.runtime.android

import android.content.ContentValues
import android.database.Cursor
import cc.noharry.agent.kernel.core.SESSION_MODEL_CALL_SOURCE_ROUND
import cc.noharry.agent.kernel.core.StoredModelCall
import cc.noharry.agent.kernel.core.StoredSession
import cc.noharry.agent.kernel.core.StoredSessionMessage
import cc.noharry.agent.kernel.core.StoredToolEvent
import cc.noharry.agent.kernel.core.StoredVfsOperation
import cc.noharry.agent.kernel.core.VfsOperationTimings

internal fun StoredToolEvent.toValues(): ContentValues = ContentValues().apply {
    put(COL_CALL_ID, callId)
    put(COL_RUN_ID, runId)
    put(COL_SESSION_ID, sessionId)
    put(COL_TOOL_NAME, toolName)
    put(COL_SOURCE, source)
    put(COL_ARGUMENTS_JSON, argumentsJson)
    put(COL_INPUT_HASH, inputHash)
    put(COL_STATUS, status)
    put(COL_STARTED_AT, startedAtEpochMillis)
    putNullableLong(COL_FINISHED_AT, finishedAtEpochMillis)
    put(COL_RESULT_JSON, resultJson)
    put(COL_RESULT_HASH, resultHash)
    ok?.let { put(COL_OK, if (it) 1 else 0) } ?: putNull(COL_OK)
    put(COL_CODE, code.orEmpty())
}

internal fun ContentValues.putNullableLong(key: String, value: Long?) {
    if (value == null) putNull(key) else put(key, value)
}

internal fun <T> Cursor.useRows(mapper: (Cursor) -> T): List<T> = use {
    buildList {
        while (it.moveToNext()) add(mapper(it))
    }
}

internal fun Cursor.toSession(): StoredSession = StoredSession(
    sessionId = string(COL_SESSION_ID),
    title = string(COL_TITLE),
    modelId = string(COL_MODEL_ID),
    createdAtEpochMillis = long(COL_CREATED_AT),
    updatedAtEpochMillis = long(COL_UPDATED_AT),
)

internal fun Cursor.toMessage(): StoredSessionMessage = StoredSessionMessage(
    sessionId = string(COL_SESSION_ID),
    runId = string(COL_RUN_ID),
    role = string(COL_ROLE),
    content = string(COL_CONTENT),
    toolCallId = string(COL_TOOL_CALL_ID),
    toolName = string(COL_TOOL_NAME),
    toolArgumentsJson = string(COL_TOOL_ARGUMENTS_JSON),
    modelRef = string(COL_MODEL_REF),
    createdAtEpochMillis = long(COL_CREATED_AT),
)

internal fun Cursor.toToolEvent(): StoredToolEvent = StoredToolEvent(
    callId = string(COL_CALL_ID),
    runId = string(COL_RUN_ID),
    sessionId = string(COL_SESSION_ID),
    toolName = string(COL_TOOL_NAME),
    source = string(COL_SOURCE),
    argumentsJson = string(COL_ARGUMENTS_JSON),
    inputHash = string(COL_INPUT_HASH),
    status = string(COL_STATUS),
    startedAtEpochMillis = long(COL_STARTED_AT),
    finishedAtEpochMillis = nullableLong(COL_FINISHED_AT),
    resultJson = string(COL_RESULT_JSON),
    resultHash = string(COL_RESULT_HASH),
    ok = nullableInt(COL_OK)?.let { it == 1 },
    code = string(COL_CODE).ifBlank { null },
)

internal fun Cursor.toVfsOperation(): StoredVfsOperation = StoredVfsOperation(
    runId = string(COL_RUN_ID),
    sessionId = string(COL_SESSION_ID),
    op = string(COL_OP),
    path = string(COL_PATH),
    mountId = string(COL_MOUNT_ID),
    status = string(COL_STATUS),
    contentHash = string(COL_CONTENT_HASH),
    timings = VfsOperationTimings(
        resolveMs = long(COL_RESOLVE_MS),
        materializeMs = long(COL_MATERIALIZE_MS),
        scanMs = long(COL_SCAN_MS),
        serializeMs = long(COL_SERIALIZE_MS),
        prepareMs = long(COL_PREPARE_MS),
        batchWaitMs = long(COL_BATCH_WAIT_MS),
        executeMs = long(COL_EXECUTE_MS),
        batchResultWaitMs = long(COL_BATCH_RESULT_WAIT_MS),
        summaryMs = long(COL_SUMMARY_MS),
        publicFinishMs = long(COL_PUBLIC_FINISH_MS),
    ),
    schemaCacheHit = nullableInt(COL_SCHEMA_CACHE_HIT)?.let { it == 1 },
    createdAtEpochMillis = long(COL_CREATED_AT),
)

internal fun Cursor.toModelCall(): StoredModelCall = StoredModelCall(
    runId = string(COL_RUN_ID),
    sessionId = string(COL_SESSION_ID),
    callId = string(COL_CALL_ID),
    status = string(COL_STATUS),
    source = string(COL_MODEL_CALL_SOURCE).ifBlank { SESSION_MODEL_CALL_SOURCE_ROUND },
    modelRef = string(COL_MODEL_REF),
    providerModel = string(COL_PROVIDER_MODEL),
    systemFingerprint = string(COL_SYSTEM_FINGERPRINT),
    startedAtEpochMillis = long(COL_STARTED_AT),
    firstEventAtEpochMillis = nullableLong(COL_FIRST_EVENT_AT),
    firstToolCallAtEpochMillis = nullableLong(COL_FIRST_TOOL_CALL_AT),
    finishedAtEpochMillis = long(COL_FINISHED_AT),
    durationMs = long(COL_DURATION_MS),
    messageCount = int(COL_MESSAGE_COUNT),
    toolCount = int(COL_TOOL_COUNT),
    eventCount = int(COL_EVENT_COUNT),
    thinkingEventCount = int(COL_THINKING_EVENT_COUNT),
    textEventCount = int(COL_TEXT_EVENT_COUNT),
    toolCallEventCount = int(COL_TOOL_CALL_EVENT_COUNT),
    usageEventCount = int(COL_USAGE_EVENT_COUNT),
    inputTokens = int(COL_INPUT_TOKENS),
    outputTokens = int(COL_OUTPUT_TOKENS),
    reasoningTokens = int(COL_REASONING_TOKENS),
    cacheReadTokens = int(COL_CACHE_READ_TOKENS),
    cacheWriteTokens = int(COL_CACHE_WRITE_TOKENS),
    errorCode = string(COL_ERROR_CODE).ifBlank { null },
)

private fun Cursor.string(column: String): String =
    getString(getColumnIndexOrThrow(column)).orEmpty()

private fun Cursor.long(column: String): Long =
    getLong(getColumnIndexOrThrow(column))

private fun Cursor.int(column: String): Int =
    getInt(getColumnIndexOrThrow(column))

private fun Cursor.nullableLong(column: String): Long? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getLong(index)
}

private fun Cursor.nullableInt(column: String): Int? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getInt(index)
}

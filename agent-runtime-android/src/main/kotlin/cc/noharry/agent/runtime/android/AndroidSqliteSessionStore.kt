package cc.noharry.agent.runtime.android

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import cc.noharry.agent.kernel.core.state.SessionQuery
import cc.noharry.agent.kernel.core.state.SessionStore
import cc.noharry.agent.kernel.core.state.StoredModelCall
import cc.noharry.agent.kernel.core.state.StoredRun
import cc.noharry.agent.kernel.core.state.StoredRunStatusUpdate
import cc.noharry.agent.kernel.core.state.StoredSession
import cc.noharry.agent.kernel.core.state.StoredSessionMessage
import cc.noharry.agent.kernel.core.state.StoredToolEvent
import cc.noharry.agent.kernel.core.state.StoredToolResult
import cc.noharry.agent.kernel.core.state.StoredUsageDelta
import cc.noharry.agent.kernel.core.state.StoredUsageSummary
import cc.noharry.agent.kernel.core.state.StoredVfsOperation
import cc.noharry.agent.kernel.core.state.TranscriptPage
import cc.noharry.agent.kernel.core.state.TranscriptRange
import java.io.File

internal class AndroidSqliteSessionStore(
    private val databaseFile: File,
) : SessionStore {
    private val lock = Any()
    private val databaseHolder = lazy {
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).also(::ensureSchema)
    }
    private val database: SQLiteDatabase
        get() = databaseHolder.value

    fun close() {
        if (databaseHolder.isInitialized()) database.close()
    }

    override fun upsertSession(session: StoredSession) {
        locked {
        val values = ContentValues().apply {
            put(COL_SESSION_ID, session.sessionId)
            put(COL_TITLE, session.title)
            put(COL_MODEL_ID, session.modelId)
            put(COL_CREATED_AT, session.createdAtEpochMillis)
            put(COL_UPDATED_AT, session.updatedAtEpochMillis)
        }
        if (exists(TABLE_SESSIONS, COL_SESSION_ID, session.sessionId)) {
            values.remove(COL_CREATED_AT)
            database.update(TABLE_SESSIONS, values, "$COL_SESSION_ID=?", arrayOf(session.sessionId))
        } else {
            database.insert(TABLE_SESSIONS, null, values)
        }
    }
    }

    override fun upsertRun(run: StoredRun) {
        locked {
        val values = ContentValues().apply {
            put(COL_RUN_ID, run.runId)
            put(COL_REQUEST_ID, run.requestId)
            put(COL_SESSION_ID, run.sessionId)
            put(COL_STATUS, run.status)
            put(COL_SCENE, run.scene)
            put(COL_PROVIDER_ID, run.providerId)
            put(COL_MODEL_ID, run.modelId)
            put(COL_STARTED_AT, run.startedAtEpochMillis)
            put(COL_UPDATED_AT, run.updatedAtEpochMillis)
            putNullableLong(COL_FINISHED_AT, run.finishedAtEpochMillis)
            put(COL_MODEL_REF, run.modelRef)
            put(COL_VARIANT, run.variant)
            put(COL_RESPONSE, "")
            put(COL_ERROR_CODE, "")
        }
        if (exists(TABLE_RUNS, COL_RUN_ID, run.runId)) {
            values.remove(COL_STARTED_AT)
            database.update(TABLE_RUNS, values, "$COL_RUN_ID=?", arrayOf(run.runId))
        } else {
            database.insert(TABLE_RUNS, null, values)
        }
    }
    }

    override fun updateRunStatus(update: StoredRunStatusUpdate) {
        locked {
        val values = ContentValues().apply {
            put(COL_STATUS, update.status)
            put(COL_UPDATED_AT, update.updatedAtEpochMillis)
            putNullableLong(COL_FINISHED_AT, update.finishedAtEpochMillis)
            put(COL_RESPONSE, update.response)
            put(COL_ERROR_CODE, update.errorCode.orEmpty())
        }
        database.update(TABLE_RUNS, values, "$COL_RUN_ID=?", arrayOf(update.runId))
    }
    }

    override fun appendMessage(message: StoredSessionMessage) {
        locked {
        val sequence = nextSequence(TABLE_MESSAGES, message.runId)
        val values = ContentValues().apply {
            put(COL_MESSAGE_ID, "${message.runId}-message-$sequence")
            put(COL_SESSION_ID, message.sessionId)
            put(COL_RUN_ID, message.runId)
            put(COL_SEQUENCE, sequence)
            put(COL_ROLE, message.role)
            put(COL_CONTENT, message.content)
            put(COL_TOOL_CALL_ID, message.toolCallId)
            put(COL_TOOL_NAME, message.toolName)
            put(COL_TOOL_ARGUMENTS_JSON, message.toolArgumentsJson)
            put(COL_MODEL_REF, message.modelRef)
            put(COL_CREATED_AT, message.createdAtEpochMillis)
        }
        database.insert(TABLE_MESSAGES, null, values)
    }
    }

    override fun recordToolStarted(event: StoredToolEvent) {
        locked {
        val values = event.toValues()
        database.insertWithOnConflict(TABLE_TOOL_EVENTS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        database.update(TABLE_TOOL_EVENTS, values, "$COL_CALL_ID=?", arrayOf(event.callId))
    }
    }

    override fun recordToolFinished(result: StoredToolResult) {
        locked {
        val values = ContentValues().apply {
            put(COL_STATUS, result.status)
            put(COL_FINISHED_AT, result.finishedAtEpochMillis)
            put(COL_RESULT_JSON, result.resultJson)
            put(COL_RESULT_HASH, result.resultHash)
            put(COL_OK, if (result.ok) 1 else 0)
            put(COL_CODE, result.code.orEmpty())
        }
        val updated = database.update(TABLE_TOOL_EVENTS, values, "$COL_CALL_ID=?", arrayOf(result.callId))
        if (updated == 0) {
            values.put(COL_CALL_ID, result.callId)
            values.put(COL_RUN_ID, result.runId)
            values.put(COL_SESSION_ID, "")
            values.put(COL_TOOL_NAME, "")
            values.put(COL_SOURCE, "")
            values.put(COL_ARGUMENTS_JSON, "")
            values.put(COL_INPUT_HASH, "")
            values.put(COL_STARTED_AT, result.finishedAtEpochMillis)
            database.insert(TABLE_TOOL_EVENTS, null, values)
        }
    }
    }

    override fun appendUsage(usage: StoredUsageDelta) {
        locked {
        database.insert(TABLE_USAGE, null, ContentValues().apply {
            put(COL_RUN_ID, usage.runId)
            put(COL_SESSION_ID, usage.sessionId)
            put(COL_INPUT_TOKENS, usage.inputTokens)
            put(COL_OUTPUT_TOKENS, usage.outputTokens)
            put(COL_REASONING_TOKENS, usage.reasoningTokens)
            put(COL_CACHE_READ_TOKENS, usage.cacheReadTokens)
            put(COL_CACHE_WRITE_TOKENS, usage.cacheWriteTokens)
            put(COL_CREATED_AT, usage.createdAtEpochMillis)
        })
    }
    }

    override fun appendVfsOperation(operation: StoredVfsOperation) {
        locked {
        database.insert(TABLE_VFS_OPS, null, ContentValues().apply {
            put(COL_RUN_ID, operation.runId)
            put(COL_SESSION_ID, operation.sessionId)
            put(COL_OP, operation.op)
            put(COL_PATH, operation.path)
            put(COL_MOUNT_ID, operation.mountId)
            put(COL_STATUS, operation.status)
            put(COL_CONTENT_HASH, operation.contentHash)
            put(COL_RESOLVE_MS, operation.timings.resolveMs)
            put(COL_MATERIALIZE_MS, operation.timings.materializeMs)
            put(COL_SCAN_MS, operation.timings.scanMs)
            put(COL_SERIALIZE_MS, operation.timings.serializeMs)
            put(COL_PREPARE_MS, operation.timings.prepareMs)
            put(COL_BATCH_WAIT_MS, operation.timings.batchWaitMs)
            put(COL_EXECUTE_MS, operation.timings.executeMs)
            put(COL_BATCH_RESULT_WAIT_MS, operation.timings.batchResultWaitMs)
            put(COL_SUMMARY_MS, operation.timings.summaryMs)
            put(COL_PUBLIC_FINISH_MS, operation.timings.publicFinishMs)
            operation.schemaCacheHit
                ?.let { put(COL_SCHEMA_CACHE_HIT, if (it) 1 else 0) }
                ?: putNull(COL_SCHEMA_CACHE_HIT)
            put(COL_CREATED_AT, operation.createdAtEpochMillis)
        })
    }
    }

    override fun appendModelCall(call: StoredModelCall) {
        locked {
        database.insertWithOnConflict(TABLE_MODEL_CALLS, null, ContentValues().apply {
            put(COL_RUN_ID, call.runId)
            put(COL_SESSION_ID, call.sessionId)
            put(COL_CALL_ID, call.callId)
            put(COL_STATUS, call.status)
            put(COL_MODEL_CALL_SOURCE, call.source)
            put(COL_MODEL_REF, call.modelRef)
            put(COL_PROVIDER_MODEL, call.providerModel)
            put(COL_SYSTEM_FINGERPRINT, call.systemFingerprint)
            put(COL_STARTED_AT, call.startedAtEpochMillis)
            putNullableLong(COL_FIRST_EVENT_AT, call.firstEventAtEpochMillis)
            putNullableLong(COL_FIRST_TOOL_CALL_AT, call.firstToolCallAtEpochMillis)
            put(COL_FINISHED_AT, call.finishedAtEpochMillis)
            put(COL_DURATION_MS, call.durationMs)
            put(COL_MESSAGE_COUNT, call.messageCount)
            put(COL_TOOL_COUNT, call.toolCount)
            put(COL_EVENT_COUNT, call.eventCount)
            put(COL_THINKING_EVENT_COUNT, call.thinkingEventCount)
            put(COL_TEXT_EVENT_COUNT, call.textEventCount)
            put(COL_TOOL_CALL_EVENT_COUNT, call.toolCallEventCount)
            put(COL_USAGE_EVENT_COUNT, call.usageEventCount)
            put(COL_INPUT_TOKENS, call.inputTokens)
            put(COL_OUTPUT_TOKENS, call.outputTokens)
            put(COL_REASONING_TOKENS, call.reasoningTokens)
            put(COL_CACHE_READ_TOKENS, call.cacheReadTokens)
            put(COL_CACHE_WRITE_TOKENS, call.cacheWriteTokens)
            put(COL_ERROR_CODE, call.errorCode.orEmpty())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    }

    override fun listSessions(query: SessionQuery): List<StoredSession> = locked {
        database.query(
            TABLE_SESSIONS,
            arrayOf(COL_SESSION_ID, COL_TITLE, COL_MODEL_ID, COL_CREATED_AT, COL_UPDATED_AT),
            null,
            null,
            null,
            null,
            "$COL_UPDATED_AT DESC",
            query.limit.coerceIn(1, SQLITE_SESSION_MAX_QUERY_LIMIT).toString(),
        ).useRows { cursor -> cursor.toSession() }
    }

    override fun transcript(sessionId: String, range: TranscriptRange): TranscriptPage = locked {
        val messages = database.query(
            TABLE_MESSAGES,
            MESSAGE_COLUMNS,
            "$COL_SESSION_ID=?",
            arrayOf(sessionId),
            null,
            null,
            "$COL_ID DESC",
            range.limit.coerceIn(1, SQLITE_SESSION_MAX_QUERY_LIMIT).toString(),
        ).useRows { cursor -> cursor.toMessage() }.asReversed()
        TranscriptPage(sessionId, messages)
    }

    override fun toolEvents(runId: String): List<StoredToolEvent> = locked {
        database.query(
            TABLE_TOOL_EVENTS,
            TOOL_COLUMNS,
            "$COL_RUN_ID=?",
            arrayOf(runId),
            null,
            null,
            "$COL_STARTED_AT ASC, $COL_ID ASC",
        ).useRows { cursor -> cursor.toToolEvent() }
    }

    override fun usage(sessionId: String): StoredUsageSummary = locked {
        database.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM($COL_INPUT_TOKENS),0), COALESCE(SUM($COL_OUTPUT_TOKENS),0), " +
                "COALESCE(SUM($COL_REASONING_TOKENS),0), COALESCE(SUM($COL_CACHE_READ_TOKENS),0), " +
                "COALESCE(SUM($COL_CACHE_WRITE_TOKENS),0) FROM $TABLE_USAGE WHERE $COL_SESSION_ID=?",
            arrayOf(sessionId),
        ).use {
            if (!it.moveToFirst()) return@locked StoredUsageSummary(0, 0, 0, 0, 0, 0)
            StoredUsageSummary(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3), it.getInt(4), it.getInt(5))
        }
    }

    override fun vfsOperations(runId: String): List<StoredVfsOperation> = locked {
        database.query(
            TABLE_VFS_OPS,
            arrayOf(
                COL_RUN_ID,
                COL_SESSION_ID,
                COL_OP,
                COL_PATH,
                COL_MOUNT_ID,
                COL_STATUS,
                COL_CONTENT_HASH,
                COL_RESOLVE_MS,
                COL_MATERIALIZE_MS,
                COL_SCAN_MS,
                COL_SERIALIZE_MS,
                COL_PREPARE_MS,
                COL_BATCH_WAIT_MS,
                COL_EXECUTE_MS,
                COL_BATCH_RESULT_WAIT_MS,
                COL_SUMMARY_MS,
                COL_PUBLIC_FINISH_MS,
                COL_SCHEMA_CACHE_HIT,
                COL_CREATED_AT,
            ),
            "$COL_RUN_ID=?",
            arrayOf(runId),
            null,
            null,
            "$COL_ID ASC",
        ).useRows { cursor -> cursor.toVfsOperation() }
    }

    override fun modelCalls(runId: String): List<StoredModelCall> = locked {
        database.query(
            TABLE_MODEL_CALLS,
            null,
            "$COL_RUN_ID=?",
            arrayOf(runId),
            null,
            null,
            "$COL_ID ASC",
        ).useRows { cursor -> cursor.toModelCall() }
    }

    private fun ensureSchema(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL(SESSIONS_SQL)
            db.execSQL(RUNS_SQL)
            db.execSQL(MESSAGES_SQL)
            db.execSQL(TOOL_EVENTS_SQL)
            db.execSQL(USAGE_SQL)
            db.execSQL(VFS_OPS_SQL)
            db.execSQL(MODEL_CALLS_SQL)
            db.execSQL(MEMORY_INDEX_SQL)
            ensureColumn(db, TABLE_RUNS, COL_PROVIDER_ID, "TEXT NOT NULL DEFAULT ''")
            ensureColumn(db, TABLE_RUNS, COL_MODEL_ID, "TEXT NOT NULL DEFAULT ''")
            ensureColumn(db, TABLE_VFS_OPS, COL_RESOLVE_MS, "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, TABLE_VFS_OPS, COL_MATERIALIZE_MS, "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, TABLE_VFS_OPS, COL_SCAN_MS, "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, TABLE_VFS_OPS, COL_SERIALIZE_MS, "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, TABLE_VFS_OPS, COL_PREPARE_MS, "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, TABLE_VFS_OPS, COL_BATCH_WAIT_MS, "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, TABLE_VFS_OPS, COL_EXECUTE_MS, "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, TABLE_VFS_OPS, COL_BATCH_RESULT_WAIT_MS, "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, TABLE_VFS_OPS, COL_SUMMARY_MS, "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, TABLE_VFS_OPS, COL_PUBLIC_FINISH_MS, "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, TABLE_VFS_OPS, COL_SCHEMA_CACHE_HIT, "INTEGER")
            ensureColumn(db, TABLE_MODEL_CALLS, COL_MODEL_CALL_SOURCE, "TEXT NOT NULL DEFAULT 'round'")
            ensureColumn(db, TABLE_MODEL_CALLS, COL_PROVIDER_MODEL, "TEXT NOT NULL DEFAULT ''")
            ensureColumn(db, TABLE_MODEL_CALLS, COL_SYSTEM_FINGERPRINT, "TEXT NOT NULL DEFAULT ''")
            INDICES_SQL.forEach(db::execSQL)
            if (db.version < SQLITE_SESSION_SCHEMA_VERSION) db.version = SQLITE_SESSION_SCHEMA_VERSION
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun exists(table: String, column: String, value: String): Boolean =
        database.query(table, arrayOf(column), "$column=?", arrayOf(value), null, null, null, "1").use {
            it.moveToFirst()
        }

    private fun nextSequence(table: String, runId: String): Int =
        database.rawQuery("SELECT COALESCE(MAX($COL_SEQUENCE),0)+1 FROM $table WHERE $COL_RUN_ID=?", arrayOf(runId))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 1 }

    private fun ensureColumn(db: SQLiteDatabase, table: String, column: String, definition: String) {
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return
            }
        }
        db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }

    private fun <T> locked(block: () -> T): T = synchronized(lock) { block() }
}

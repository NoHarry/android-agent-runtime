package cc.noharry.agent.runtime.android

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import cc.noharry.agent.kernel.core.DynamicToolSource
import cc.noharry.agent.kernel.core.SESSION_MODEL_CALL_STATUS_COMPLETED
import cc.noharry.agent.kernel.core.SESSION_TOOL_STATUS_COMPLETED
import cc.noharry.agent.kernel.core.SESSION_TOOL_STATUS_STARTED
import cc.noharry.agent.kernel.core.StoredModelCall
import cc.noharry.agent.kernel.core.StoredRun
import cc.noharry.agent.kernel.core.StoredSession
import cc.noharry.agent.kernel.core.StoredSessionMessage
import cc.noharry.agent.kernel.core.StoredToolEvent
import cc.noharry.agent.kernel.core.StoredToolResult
import cc.noharry.agent.kernel.core.StoredUsageDelta
import cc.noharry.agent.kernel.core.StoredVfsOperation
import cc.noharry.agent.kernel.core.VfsOperationTimings

@RunWith(RobolectricTestRunner::class)
class AndroidSqliteSessionStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun persistsTranscriptToolVfsAndUsageEvidence() {
        val databaseFile = temp.newFile("runtime-session-store.sqlite")
        val store = AndroidSqliteSessionStore(databaseFile)
        try {
            store.persistSampleEvidence()
            store.assertSampleEvidence(databaseFile.absolutePath)
        } finally {
            store.close()
        }
    }

    @Test
    fun toolCallIdMergesStartedAndFinishedRows() {
        val store = AndroidSqliteSessionStore(temp.newFile("tool-merge.sqlite"))
        try {
            store.recordToolStarted(readTool(argumentsJson = """{"path":"/AGENTS.md"}"""))
            store.recordToolStarted(readTool(argumentsJson = """{"path":"/mcp/tools.json"}"""))
            store.recordToolFinished(
                StoredToolResult(
                    callId = "call-1",
                    runId = "run-1",
                    status = SESSION_TOOL_STATUS_COMPLETED,
                    finishedAtEpochMillis = 10,
                    resultJson = """{"ok":true}""",
                    resultHash = "hash",
                    ok = true,
                ),
            )

            val events = store.toolEvents("run-1")
            assertEquals(1, events.size)
            assertEquals("""{"path":"/mcp/tools.json"}""", events.single().argumentsJson)
            assertTrue(events.single().finishedAtEpochMillis != null)
        } finally {
            store.close()
        }
    }
}

private fun AndroidSqliteSessionStore.persistSampleEvidence() {
    upsertSession(StoredSession("session-1", "Guangzhou", createdAtEpochMillis = 1, updatedAtEpochMillis = 1))
    upsertRun(storedRun())
    appendMessage(storedMessage())
    recordToolStarted(readTool(status = SESSION_TOOL_STATUS_STARTED))
    recordToolFinished(
        StoredToolResult(
            callId = "call-1",
            runId = "run-1",
            status = SESSION_TOOL_STATUS_COMPLETED,
            finishedAtEpochMillis = 4,
            resultJson = """{"ok":true}""",
            resultHash = "result-hash",
            ok = true,
        ),
    )
    appendVfsOperation(sampleStoredVfsOperation())
    appendModelCall(sampleStoredModelCall())
    appendUsage(StoredUsageDelta("run-1", "session-1", inputTokens = 7, outputTokens = 5, reasoningTokens = 1, createdAtEpochMillis = 6))
}

private fun AndroidSqliteSessionStore.assertSampleEvidence(databasePath: String) {
    val vfsOperation = vfsOperations("run-1").single()
    assertEquals("session-1", listSessions().single().sessionId)
    assertEquals(listOf("user"), transcript("session-1").messages.map { it.role })
    assertEquals(SESSION_TOOL_STATUS_COMPLETED, toolEvents("run-1").single().status)
    assertEquals("/mcp/tools.json", vfsOperation.path)
    assertEquals(sampleVfsOperationTimings(), vfsOperation.timings)
    assertEquals(true, vfsOperation.schemaCacheHit)
        assertEquals(sampleStoredModelCall(), modelCalls("run-1").single())
        assertEquals(13, usage("session-1").totalTokens)
        assertRunModelColumns(databasePath)
        assertModelCallMetadataColumns(databasePath)
}

private fun sampleStoredVfsOperation(): StoredVfsOperation =
    StoredVfsOperation(
        "run-1",
        "session-1",
        "read_file",
        "/mcp/tools.json",
        status = "OK",
        createdAtEpochMillis = 5,
        timings = sampleVfsOperationTimings(),
        schemaCacheHit = true,
    )

private fun sampleVfsOperationTimings(): VfsOperationTimings =
    VfsOperationTimings(
        resolveMs = 1,
        materializeMs = 2,
        serializeMs = 3,
        prepareMs = 4,
        batchWaitMs = 5,
        executeMs = 6,
        batchResultWaitMs = 7,
        summaryMs = 8,
        publicFinishMs = 9,
    )

private fun sampleStoredModelCall(): StoredModelCall =
    StoredModelCall(
        runId = "run-1",
        sessionId = "session-1",
        callId = "run-1:model-1",
        status = SESSION_MODEL_CALL_STATUS_COMPLETED,
        modelRef = "openai:gpt-5:high",
        startedAtEpochMillis = 7,
        firstEventAtEpochMillis = 8,
        firstToolCallAtEpochMillis = 9,
        finishedAtEpochMillis = 11,
        durationMs = 4,
        messageCount = 2,
        toolCount = 3,
        eventCount = 4,
        thinkingEventCount = 1,
        textEventCount = 1,
        toolCallEventCount = 1,
        usageEventCount = 1,
        inputTokens = 7,
        outputTokens = 5,
        reasoningTokens = 1,
        providerModel = "deepseek-v4-flash",
        systemFingerprint = "fp-sqlite",
    )

private fun readTool(
    argumentsJson: String = """{"path":"/mcp/tools.json"}""",
    status: String = SESSION_TOOL_STATUS_STARTED,
): StoredToolEvent =
    StoredToolEvent(
        callId = "call-1",
        runId = "run-1",
        sessionId = "session-1",
        toolName = "read_file",
        source = DynamicToolSource.NATIVE_READ.name,
        argumentsJson = argumentsJson,
        inputHash = "input-hash",
        status = status,
        startedAtEpochMillis = 3,
    )

private fun storedRun(): StoredRun =
    StoredRun(
        runId = "run-1",
        requestId = "request-1",
        sessionId = "session-1",
        status = "RUNNING",
        providerId = "openai",
        modelId = "gpt-5",
        startedAtEpochMillis = 2,
        updatedAtEpochMillis = 2,
        modelRef = "openai:gpt-5:high",
        variant = "high",
    )

private fun storedMessage(): StoredSessionMessage =
    StoredSessionMessage(
        sessionId = "session-1",
        runId = "run-1",
        role = "user",
        content = "find parking",
        createdAtEpochMillis = 3,
    )

private fun assertRunModelColumns(path: String) {
    val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
    try {
        db.rawQuery(
            "SELECT $COL_PROVIDER_ID, $COL_MODEL_ID, $COL_MODEL_REF, $COL_VARIANT FROM $TABLE_RUNS WHERE $COL_RUN_ID=?",
            arrayOf("run-1"),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("openai", cursor.getString(0))
            assertEquals("gpt-5", cursor.getString(1))
            assertEquals("openai:gpt-5:high", cursor.getString(2))
            assertEquals("high", cursor.getString(3))
        }
    } finally {
        db.close()
    }
}

private fun assertModelCallMetadataColumns(path: String) {
    val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
    try {
        db.rawQuery(
            "SELECT $COL_PROVIDER_MODEL, $COL_SYSTEM_FINGERPRINT FROM $TABLE_MODEL_CALLS WHERE $COL_CALL_ID=?",
            arrayOf("run-1:model-1"),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("deepseek-v4-flash", cursor.getString(0))
            assertEquals("fp-sqlite", cursor.getString(1))
        }
    } finally {
        db.close()
    }
}

@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.session
import cc.noharry.agent.appserver.evidence.RAW_STATUS_FAILED
import cc.noharry.agent.appserver.evidence.RAW_STATUS_OK
import cc.noharry.agent.appserver.evidence.RunReplayResult
import cc.noharry.agent.appserver.model.appSystemPrompt
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.SessionSummary
import cc.noharry.agent.appserver.protocol.SessionToolCall
import cc.noharry.agent.appserver.protocol.SessionTurn
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.runtime.encodeSessionId
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.appserver.runtime.*
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest
import cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.spi.reliability.RuntimeCompletionSummary
import cc.noharry.agent.spi.reliability.RuntimeResult
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStoreTest {
    @Test
    fun boardReusesSnapshotUntilItsSessionSummaryChanges() {
        val root = tempRoot()
        try {
            val store = SessionStore(root)
            store.ensureSession(SESSION_ID)
            val coordinator = SessionCoordinator(TranscriptStore())
            coordinator.attachStoreForTest(store)
            coordinator.adoptActiveSessionId(SESSION_ID)
            val first = coordinator.board()
            repeat(20) { org.junit.Assert.assertSame(first, coordinator.board()) }
            coordinator.appendTurn(turn("board-turn", "hello", "read_file", "{}"))
            val updated = coordinator.board()
            assertEquals(1, updated.recentTurns.size)
            org.junit.Assert.assertNotSame(first, updated)
            assertTrue(first.recentTurns.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun repeatedContextReadsDoNotRewriteCrossSessionQueryFiles() {
        val root = tempRoot()
        try {
            val store = SessionStore(root)
            store.ensureSession(SESSION_ID)
            store.ensureSession(OTHER_SESSION_ID)
            val queries = listOf(File(root, "index.json")) +
                listOf(SESSION_ID, OTHER_SESSION_ID).map { File(root, "${encodeSessionId(it)}/summary.json") }
            queries.forEach { assertTrue(it.setLastModified(1_000L)) }
            repeat(10) { store.readContext(SESSION_ID) }
            queries.forEach { assertEquals("Read rewrote ${it.name}", 1_000L, it.lastModified()) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun storesTurnsAndReconstructsModelContext() {
        val root = tempRoot()
        try {
            val store = SessionStore(root)
            store.ensureSession(SESSION_ID)
            store.appendTurn(
                turn(
                    requestId = "turn-1",
                    userText = "空调温度调高一点",
                    toolName = "command_ac_SetTemperature",
                    args = """{"temperature":25}""",
                ),
            )

            val context = store.modelContext(SESSION_ID)
            val restored = store.transcriptItems(SESSION_ID)

            assertTrue(context.contains("空调温度调高一点"))
            assertTrue(context.contains("command_ac_SetTemperature:OK"))
            assertTrue(context.contains("lastSuccessfulAction=command_ac_SetTemperature"))
            assertEquals(4, restored.size)
            assertEquals("done", restored.filterIsInstance<ConversationItem.MessageItem>().single().content)
            assertEquals(TranscriptRole.ASSISTANT, restored.filterIsInstance<ConversationItem.MessageItem>().single().role)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun publishesQueryableCrossSessionIndexAndSummaries() {
        val root = tempRoot()
        try {
            val store = SessionStore(root)
            store.appendTurn(turn("turn-1", "空调温度调高一点", "command_ac_SetTemperature", """{"temperature":25}"""))
            store.appendTurn(
                turn(
                    requestId = "turn-2",
                    userText = "导航去公司",
                    toolName = "command_navi_CalRoute",
                    args = """{"destination":"office"}""",
                    sessionId = OTHER_SESSION_ID,
                ),
            )

            val index = File(root, "index.json").readText()
            val otherSummaryPath = "$SESSION_QUERY_ROOT/${encodeSessionId(OTHER_SESSION_ID)}/summary.json"
            val otherSummary = File(File(root, encodeSessionId(OTHER_SESSION_ID)), "summary.json").readText()

            assertTrue(index.contains("actionDomains"))
            assertTrue(index.contains("lastAction"))
            assertTrue(index.contains("air_control"))
            assertTrue(index.contains("navigation"))
            assertTrue(index.contains(otherSummaryPath))
            assertTrue(index.contains(OTHER_SESSION_ID))
            assertTrue(otherSummary.contains("导航去公司"))
            assertTrue(otherSummary.contains("command_navi_CalRoute"))
            assertTrue(otherSummary.contains("domainId"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recreatingStoreRefreshesStaleQueryIndex() {
        val root = tempRoot()
        try {
            val store = SessionStore(root)
            store.appendTurn(turn("turn-1", "空调温度调高一点", "command_ac_SetTemperature", """{"temperature":25}"""))
            File(root, "index.json").writeText(
                """{"schemaVersion":1,"description":"old","sessions":[]}""",
                Charsets.UTF_8,
            )

            SessionStore(root)

            val index = File(root, "index.json").readText()
            assertTrue(index.contains("actionDomains"))
            assertTrue(index.contains("lastAction"))
            assertTrue(index.contains("air_control"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun compactsOlderTurnsButKeepsRecentTurns() {
        val root = tempRoot()
        try {
            val store = SessionStore(root)
            repeat(8) { index ->
                store.appendTurn(turn("turn-$index", "用户请求 $index", "tool_$index", "{}"))
            }

            val summary = store.listSessions().single()
            val context = store.modelContext(SESSION_ID)

            assertTrue(summary.compactedSummary.contains("用户请求 0"))
            assertTrue(context.contains("用户请求 7"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun semanticHistoryKeepsPartialConversationButOnlySucceededActionsAreReusable() {
        val root = tempRoot()
        try {
            val store = SessionStore(root)
            store.appendTurn(turn("succeeded", "播放音乐", "command_music_PlaySearchMusic", "{}"))
            listOf(
                RuntimeCompletionOutcome.PARTIAL,
                RuntimeCompletionOutcome.BLOCKED,
                RuntimeCompletionOutcome.FAILED,
                RuntimeCompletionOutcome.UNKNOWN,
            ).forEach { outcome ->
                store.appendTurn(
                    turn(
                        requestId = "completed-${outcome.name.lowercase()}",
                        userText = "不应复用 ${outcome.name}",
                        toolName = "command_navi_CalRoute",
                        args = "{}",
                        completionOutcome = outcome.name,
                    ),
                )
            }

            val context = store.modelContext(SESSION_ID)
            assertTrue(context.contains("播放音乐"))
            assertTrue(context.contains("lastSuccessfulAction=command_music_PlaySearchMusic"))
            listOf("PARTIAL", "BLOCKED", "FAILED", "UNKNOWN").forEach { token ->
                if (token == "UNKNOWN") {
                    assertTrue(!context.contains("不应复用 $token"))
                } else {
                    assertTrue(context.contains("不应复用 $token"))
                    assertTrue(context.contains("completion=$token"))
                }
            }
            assertTrue(context.contains("successfulActionEvidence=unverified"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun succeededTurnDoesNotLabelItsFailedBusinessToolAsSuccessfulActionEvidence() {
        val root = tempRoot()
        try {
            val store = SessionStore(root)
            store.appendTurn(
                turnWithTools(
                    SessionToolCall(
                        sequence = 1,
                        toolName = "mcp__device__home_ac_temperature_adjust",
                        argumentsPreview = "{}",
                        resultPreview = """{"ok":false}""",
                        status = RAW_STATUS_FAILED,
                    ),
                    SessionToolCall(2, "finish", "{}", "{}", RAW_STATUS_OK),
                ),
            )

            val context = store.modelContext(SESSION_ID)

            assertTrue(context.contains("lastSuccessfulAction=none"))
            assertTrue(context.contains("successfulActionEvidence=none"))
            assertTrue(context.contains("assistant=done"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun modelContextIgnoresLegacyPersistedCompactedSummary() {
        val root = tempRoot()
        try {
            val store = seedLegacyCompactedSummaryStore(root)
            writeUnsafeLegacyCompactedSummary(root)
            val context = store.modelContext(SESSION_ID)
            val summaryAfterRead = store.listSessions().single()
            assertLegacyCompactedSummaryIgnored(context, summaryAfterRead)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun seedLegacyCompactedSummaryStore(root: File): SessionStore {
        val store = SessionStore(root)
        store.appendTurn(turn("old-succeeded", "旧的成功请求", "tool_old_ok", "{}"))
        store.appendTurn(
            turn(
                requestId = "old-partial",
                userText = "旧的部分成功",
                toolName = "tool_old_partial",
                args = "{}",
                completionOutcome = RuntimeCompletionOutcome.PARTIAL.name,
            ),
        )
        val legacyJson =
            """{"schemaVersion":1,"turnId":"legacy-old","requestId":"legacy-old","runId":"legacy-old","sessionId":"$SESSION_ID","scene":"debug-console","userText":"遗留无完成态","startedAtEpochMillis":1,"completedAtEpochMillis":2,"finalStatus":"COMPLETED","finalResponse":"done","tools":[]}"""
        store.appendTurn(AppSessionJson.decodeTurn(legacyJson)!!)
        store.appendTurn(
            turn(
                requestId = "old-failed",
                userText = "旧的失败请求",
                toolName = "tool_old_failed",
                args = "{}",
                finalStatus = "FAILED",
                finalResponse = "MODEL_FINISHED_WITHOUT_RUNTIME_FINISH",
                completionOutcome = RuntimeCompletionOutcome.FAILED.name,
            ),
        )
        repeat(6) { index ->
            store.appendTurn(turn("recent-$index", "最近成功 $index", "tool_recent_$index", "{}"))
        }
        return store
    }

    private fun writeUnsafeLegacyCompactedSummary(root: File) {
        val meta = File(File(root, encodeSessionId(SESSION_ID)), "meta.json")
        val persisted = AppSessionJson.decodeSummary(meta.readText(Charsets.UTF_8))!!
        meta.writeText(
            AppSessionJson.encodeSummary(
                persisted.copy(
                    compactedSummary = listOf(
                        "- 旧的成功请求 -> tool_old_ok -> done",
                        "- 旧的部分成功 -> tool_old_partial -> done",
                        "- 遗留无完成态 -> none -> done",
                        "- 旧的失败请求 -> tool_old_failed -> MODEL_FINISHED_WITHOUT_RUNTIME_FINISH",
                        "- UNSAFE_LEGACY_COMPACTED_SUMMARY",
                    ).joinToString("\n"),
                ),
            ),
            Charsets.UTF_8,
        )
    }

    private fun assertLegacyCompactedSummaryIgnored(context: String, summaryAfterRead: SessionSummary) {
        assertTrue(context.contains("olderConversationSummary:"))
        assertTrue(context.contains("旧的成功请求"))
        assertTrue(context.contains("最近成功 5"))
        assertTrue(context.contains("旧的部分成功"))
        assertTrue(!context.contains("遗留无完成态"))
        assertTrue(!context.contains("旧的失败请求"))
        assertTrue(!context.contains("UNSAFE_LEGACY_COMPACTED_SUMMARY"))
        assertTrue(!context.contains("MODEL_FINISHED_WITHOUT_RUNTIME_FINISH"))
        assertTrue(summaryAfterRead.compactedSummary.contains("UNSAFE_LEGACY_COMPACTED_SUMMARY"))
    }

    @Test
    fun compactedPartialConversationIsSemanticButLegacyUnknownIsExcluded() {
        val root = tempRoot()
        try {
            val store = SessionStore(root)
            store.appendTurn(
                turn(
                    requestId = "old-partial",
                    userText = "旧的部分成功",
                    toolName = "tool_old",
                    args = "{}",
                    completionOutcome = RuntimeCompletionOutcome.PARTIAL.name,
                ),
            )
            repeat(6) { index ->
                store.appendTurn(turn("recent-$index", "最近成功 $index", "tool_recent_$index", "{}"))
            }
            val summary = store.listSessions().single()
            assertTrue(summary.compactedSummary.contains("旧的部分成功"))

            val legacyJson =
                """{"schemaVersion":1,"turnId":"legacy","requestId":"legacy","runId":"legacy","sessionId":"$SESSION_ID","scene":"debug-console","userText":"遗留无完成态","startedAtEpochMillis":1,"completedAtEpochMillis":2,"finalStatus":"COMPLETED","finalResponse":"done","tools":[]}"""
            val decoded = AppSessionJson.decodeTurn(legacyJson)!!
            assertEquals(RuntimeCompletionOutcome.UNKNOWN.name, decoded.completionOutcome)
            assertTrue(!decoded.isFirstPartyReusableHistory())

            store.appendTurn(decoded)
            val context = store.modelContext(SESSION_ID)
            assertTrue(!context.contains("遗留无完成态"))
            assertTrue(context.contains("旧的部分成功"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recorderPersistsResultAvailableCompletionOutcome() {
        val recorder = SessionTurnRecorder(
            request = RuntimeTurnRequest("request-1", SESSION_ID, "播放音乐", "debug-console"),
            userText = "播放音乐",
        )
        recorder.recordEvent(
            testTurnCompleted(
                RuntimeResult(
                    "run-1",
                    RuntimeRunStatus.COMPLETED,
                    "done",
                    completion = RuntimeCompletionSummary(outcome = RuntimeCompletionOutcome.SUCCEEDED),
                ),
            ),
        )
        val turn = recorder.snapshot()
        assertEquals(RuntimeRunStatus.COMPLETED.name, turn.finalStatus)
        assertEquals(RuntimeCompletionOutcome.SUCCEEDED.name, turn.completionOutcome)
        assertTrue(turn.isFirstPartyReusableHistory())
    }

    @Test fun recorderRedactsToolCredentialsAndPreservesContactNumbers() {
        val recorder = SessionTurnRecorder(RuntimeTurnRequest("request-1", SESSION_ID, "write", "app"), "write")
        recorder.recordEvent(testToolStarted("run-1", "write_file", """{"path":"/scratch/page.html","api_key":"private-key"}""", "call-1"))
        recorder.recordEvent(testToolCompleted("run-1", "write_file", """{"ok":true,"secret":"private-secret","phone":"13912345678"}""", "call-1"))
        val tool = recorder.snapshot().tools.single()
        assertTrue(tool.argumentsPreview.contains("/scratch/page.html"))
        assertFalse(tool.argumentsPreview.contains("private-key"))
        assertFalse(tool.resultPreview.contains("private-secret"))
        assertTrue(tool.resultPreview.contains("13912345678"))
    }

    @Test
    fun modelContextExcludesFailedTurnsFromReusableHistory() {
        val root = tempRoot()
        try {
            val store = SessionStore(root)
            store.appendTurn(
                turn(
                    requestId = "failed-turn",
                    userText = "复杂导航失败",
                    toolName = "command_navi_CalRoute",
                    args = "{}",
                    finalStatus = "FAILED",
                    finalResponse = "MODEL_FINISHED_WITHOUT_RUNTIME_FINISH",
                ),
            )
            store.appendTurn(turn("completed-turn", "播放音乐", "command_music_PlaySearchMusic", "{}"))

            val context = store.modelContext(SESSION_ID)

            assertTrue(context.contains("historyPolicy=conversation semantics only"))
            assertTrue(context.contains("recentTurnsNewestFirst"))
            assertTrue(context.contains("播放音乐"))
            assertTrue(context.contains("lastSuccessfulAction=command_music_PlaySearchMusic"))
            assertTrue(!context.contains("复杂导航失败"))
            assertTrue(!context.contains("MODEL_FINISHED_WITHOUT_RUNTIME_FINISH"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteSessionRemovesPersistedTurnsAndSummary() {
        val root = tempRoot()
        try {
            val store = SessionStore(root)
            store.appendTurn(turn("turn-delete", "删除这个会话", "command_debug_Delete", "{}"))

            assertTrue(store.listSessions().any { it.sessionId == SESSION_ID })

            assertTrue(store.deleteSession(SESSION_ID))
            assertTrue(store.listSessions().none { it.sessionId == SESSION_ID })
            assertTrue(store.readTurns(SESSION_ID).isEmpty())
            assertTrue(File(root, "index.json").readText().contains("\"sessions\":[]"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun lastActionSkipsInternalTools() {
        val context = SessionContext(
            summary = SessionSummary(SESSION_ID, "title", 100, 1, ""),
            recentTurns = listOf(
                turnWithTools(
                    SessionToolCall(1, "read_file", "{}", "{}", RAW_STATUS_OK),
                    SessionToolCall(2, "command_music_PlaySearchMusic", "{}", "{}", RAW_STATUS_OK),
                    SessionToolCall(3, "finish", "{}", "{}", RAW_STATUS_OK),
                ),
            ),
        )

        assertEquals("command_music_PlaySearchMusic", context.lastAction?.toolName)
        assertEquals("command_music_PlaySearchMusic", context.recentTurns.single().meaningfulAction()?.toolName)
    }

    @Test
    fun replayFailureDoesNotOverridePersistedTurnConversationOrRuntimeOutcome() {
        val recorder = SessionTurnRecorder(
            request = RuntimeTurnRequest("request-1", SESSION_ID, "导航去广州塔", "debug-console"),
            userText = "导航去广州塔",
        )
        val replay = RunReplayResult(
            passed = false,
            summary = "replay=FAIL final=COMPLETED public=1/1 business=0 actionable=true",
            failures = listOf("actionable request completed without a meaningful business tool"),
            warnings = emptyList(),
        )

        recorder.recordEvent(testTurnCompleted(RuntimeResult("run-1", RuntimeRunStatus.COMPLETED, "done")))
        recorder.recordReplay(replay)
        val turn = recorder.snapshot()

        assertEquals(RuntimeRunStatus.COMPLETED.name, turn.finalStatus)
        assertEquals("done", turn.finalResponse)
        assertEquals(DEBUG_REPLAY_STATUS_FAILED, turn.replayStatus)
        assertEquals(replay.summary, turn.replaySummary)

        val restored = AppSessionJson.decodeTurn(AppSessionJson.encodeTurn(turn))!!
        assertEquals(turn, restored)
    }

    @Test
    fun replayFailureDoesNotInventARuntimeOutcomeForRunningTurn() {
        val recorder = SessionTurnRecorder(
            request = RuntimeTurnRequest("request-1", SESSION_ID, "导航去广州塔", "debug-console"),
            userText = "导航去广州塔",
        )
        val replay = RunReplayResult(
            passed = false,
            summary = "replay=FAIL final=missing public=0/1 business=0 actionable=true",
            failures = listOf("final result status is missing"),
            warnings = emptyList(),
        )

        recorder.recordEvent(testToolStarted("run-1", "read_file", "{}"))
        recorder.recordReplay(replay)
        val turn = recorder.snapshot()

        assertEquals(RuntimeRunStatus.RUNNING.name, turn.finalStatus)
        assertEquals("", turn.finalResponse)
        assertEquals(DEBUG_REPLAY_STATUS_FAILED, turn.replayStatus)
        assertEquals(replay.summary, turn.replaySummary)
    }

    @Test
    fun partialAssistantQuestionRemainsAvailableToNextTurn() {
        val root = tempRoot()
        try {
            val store = SessionStore(root)
            store.appendTurn(
                turn(
                    requestId = "offer-climate-settings",
                    userText = "还是有点热",
                    toolName = "read_file",
                    args = "{}",
                    finalResponse = "当前无法直接调节温度，需要我为您打开空调设置页面吗？",
                    completionOutcome = RuntimeCompletionOutcome.PARTIAL.name,
                ),
            )

            val context = store.modelContext(SESSION_ID)

            assertTrue(context.contains("user=还是有点热"))
            assertTrue(context.contains("assistant=当前无法直接调节温度，需要我为您打开空调设置页面吗？"))
            assertTrue(context.contains("completion=PARTIAL"))
            assertTrue(context.contains("successfulActionEvidence=unverified"))
            assertTrue(context.contains("lastSuccessfulAction=none"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun longChineseHistoryKeepsNewestAssistantQuestionWithinUtf8PromptBudget() {
        val root = tempRoot()
        try {
            val store = SessionStore(root)
            repeat(8) { index ->
                store.appendTurn(
                    turn("older-$index", "较早的中文请求".repeat(40), "tool_$index", "{}"),
                )
            }
            val question = "需要我为您打开空调设置页面吗？"
            store.appendTurn(
                turn(
                    requestId = "latest-offer",
                    userText = "还是有点热",
                    toolName = "read_file",
                    args = "{}",
                    finalResponse = "补充说明".repeat(120) + question,
                    completionOutcome = RuntimeCompletionOutcome.PARTIAL.name,
                ),
            )

            val context = store.modelContext(SESSION_ID)
            val prompt = appSystemPrompt(context)

            assertTrue(context.toByteArray(Charsets.UTF_8).size <= MAX_DEBUG_SESSION_CONTEXT_BYTES)
            assertTrue(prompt.toByteArray(Charsets.UTF_8).size <= MAX_HOST_PROMPT_BYTES)
            assertTrue(context.contains("user=还是有点热"))
            assertTrue(context.contains(question))
            assertTrue(context.contains("completion=PARTIAL"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun toolItemsFinishOutOfOrderWithoutRawHooks() {
        val recorder = SessionTurnRecorder(RuntimeTurnRequest("request-1", SESSION_ID, "input", "app"), "input")
        recorder.recordEvent(testToolStarted("run-1", TOOL_NAME, "{}", "call-1"))
        recorder.recordEvent(testToolStarted("run-1", TOOL_NAME, "{}", "call-2"))
        recorder.recordEvent(testToolCompleted("run-1", TOOL_NAME, "{\"items\":[]}", "call-2"))
        recorder.recordEvent(testToolCompleted("run-1", TOOL_NAME, "{\"failed\":true}", "call-1", success = false))
        val tools = recorder.snapshot().tools
        assertEquals(RAW_STATUS_FAILED, tools.single { it.toolCallId == "call-1" }.status)
        assertEquals(RAW_STATUS_OK, tools.single { it.toolCallId == "call-2" }.status)
    }

    private fun turn(
        requestId: String,
        userText: String,
        toolName: String,
        args: String,
        sessionId: String = SESSION_ID,
        finalStatus: String = "COMPLETED",
        finalResponse: String = "done",
        completionOutcome: String = RuntimeCompletionOutcome.SUCCEEDED.name,
    ): SessionTurn =
        SessionTurn(
            turnId = requestId,
            requestId = requestId,
            runId = requestId,
            sessionId = sessionId,
            scene = "debug-console",
            userText = userText,
            startedAtEpochMillis = 100,
            completedAtEpochMillis = 200,
            finalStatus = finalStatus,
            finalResponse = finalResponse,
            tools = listOf(SessionToolCall(1, toolName, args, "{}", RAW_STATUS_OK)),
            completionOutcome = completionOutcome,
        )

    private fun turnWithTools(vararg tools: SessionToolCall): SessionTurn =
        SessionTurn(
            turnId = "turn-tools",
            requestId = "turn-tools",
            runId = "turn-tools",
            sessionId = SESSION_ID,
            scene = "debug-console",
            userText = "complex request",
            startedAtEpochMillis = 100,
            completedAtEpochMillis = 200,
            finalStatus = "COMPLETED",
            finalResponse = "done",
            tools = tools.toList(),
            completionOutcome = RuntimeCompletionOutcome.SUCCEEDED.name,
        )

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "debug-session-store-${System.nanoTime()}").apply { mkdirs() }

    private companion object {
        const val SESSION_ID = "session-1"
        const val OTHER_SESSION_ID = "session-other"
        const val TOOL_NAME = "command_navi_SearchParking"
        const val MAX_HOST_PROMPT_BYTES = 16 * 1024
    }
}

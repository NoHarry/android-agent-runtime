@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.evidence
import cc.noharry.agent.appserver.protocol.LessonEntry
import cc.noharry.agent.appserver.runtime.AnswerProblems
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.ReplayCheck
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.appserver.runtime.*
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest
import cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.spi.reliability.RuntimeCompletionSummary
import cc.noharry.agent.spi.reliability.RuntimeResult
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RunEvidenceTest {
    // A host that says which requests must act and what is wrong with an answer (a host's own tests read its words); the
    // server judges neither by itself.
    @Before
    fun actingHost() {
        AppHost.install(replayCheck = ACTING_HOST)
    }

    @After
    fun noHost() {
        AppHost.install(replayCheck = ReplayCheck.NONE)
    }

    @Test
    fun withoutAHostNothingMustActAndNoAnswerIsWrong() {
        AppHost.install(replayCheck = ReplayCheck.NONE)
        val recorder = recorder(input = "我想去广州塔，在那找一个停车场")
        recorder.recordEvent(testTurnStarted(RUN_ID))
        recorder.recordEvent(testTurnCompleted(result(RuntimeRunStatus.COMPLETED, "process only")))

        val evidence = recorder.snapshot()
        assertFalse(evidence.requiresBusinessAction)
        assertTrue(RunReplayVerifier.verify(evidence).passed)
    }

    @Test
    fun aTurnARunningPluginLabelsAsNotThePersonsAsksForNoAction() {
        // As the A2A plugin labels its reports and questions (PluginContext.turnLabel).
        AppHost.install(additions = cc.noharry.agent.appserver.server.PluginAdditions(turnLabels = listOf { text -> "report".takeIf { text.startsWith("【回报】") } }))
        try {
            assertFalse("【回报】助手：想去广州塔哪个门？回答后发回去".requiresBusinessAction())
            assertTrue("导航去广州塔".requiresBusinessAction())
        } finally {
            AppHost.install(additions = cc.noharry.agent.appserver.server.PluginAdditions.NONE)
        }
    }

    @Test
    fun theHostsAnswerChecksFailOrWarnARunThatPassedOtherwise() {
        val recorder = recorder(input = "user text")
        recorder.recordEvent(testTurnStarted(RUN_ID))
        recorder.recordEvent(testTurnCompleted(result(RuntimeRunStatus.COMPLETED, "process only")))
        val failed = RunReplayVerifier.verify(recorder.snapshot())
        assertFalse(failed.passed)
        assertEquals(listOf("final response is process narration"), failed.failures)

        val warned = recorder(input = "user text")
        warned.recordEvent(testTurnStarted(RUN_ID))
        warned.recordEvent(testTurnCompleted(result(RuntimeRunStatus.COMPLETED, "short")))
        val replay = RunReplayVerifier.verify(warned.snapshot())
        assertTrue(replay.passed)
        assertTrue(replay.warnings.contains("final response is short"))

        // A run that failed by itself is not judged by its answer.
        val stopped = recorder(input = "user text")
        stopped.recordEvent(testTurnStarted(RUN_ID))
        stopped.recordEvent(testTurnCompleted(result(RuntimeRunStatus.FAILED, "process only")))
        assertFalse(RunReplayVerifier.verify(stopped.snapshot()).failures.contains("final response is process narration"))
    }

    @Test
    fun missingPublicFinishFailsReplayEvenWhenFinalResultCompleted() {
        val recorder = recorder()
        recorder.recordEvent(testTurnStarted(RUN_ID))
        recorder.recordEvent(testToolStarted(RUN_ID, TOOL_NAME, """{"redacted":true}"""))
        recorder.recordEvent(testTurnCompleted(result(RuntimeRunStatus.COMPLETED)))

        val replay = RunReplayVerifier.verify(recorder.snapshot())

        assertFalse(replay.passed)
        assertTrue(replay.failures.single().contains("public tool calls still pending"))
    }

    @Test
    fun memoryEvidenceRoundTripsThroughJson() {
        val recorder = recorder()
        recorder.recordMemoryEvidence(
            RuntimeMemoryEvidence(
                activeCount = 1,
                pendingCount = 1,
                active = listOf(LessonEntry("navigation", "destination POI", 0.9f, 2)),
                pending = listOf(LessonEntry("navigation", "screen brighten adjust", 0.45f, 1)),
            ),
        )

        val evidence = RunEvidenceJson.parse(RunEvidenceJson.write(recorder.snapshot()))

        assertEquals(1, evidence.memory.activeCount)
        assertEquals(1, evidence.memory.pendingCount)
        assertEquals("destination POI", evidence.memory.active.single().content)
        assertEquals("screen brighten adjust", evidence.memory.pending.single().content)
    }

    @Test
    fun actionableRequestWithoutBusinessToolFailsReplayEvenWhenCompleted() {
        val recorder = recorder(input = "我想去广州塔，在那找一个停车场")
        recorder.recordEvent(testTurnStarted(RUN_ID))
        recorder.recordEvent(testToolStarted(RUN_ID, "read_file", """{"redacted":true}"""))
        recorder.recordEvent(testToolCompleted(RUN_ID, "read_file", """{"redacted":true}"""))
        recorder.recordEvent(testToolStarted(RUN_ID, "finish", """{"redacted":true}"""))
        recorder.recordEvent(testToolCompleted(RUN_ID, "finish", """{"redacted":true}"""))
        recorder.recordEvent(testTurnCompleted(result(RuntimeRunStatus.COMPLETED)))

        val evidence = recorder.snapshot()
        val replay = RunReplayVerifier.verify(evidence)

        assertTrue(evidence.requiresBusinessAction)
        assertEquals(0, evidence.counts.meaningfulBusinessTools)
        assertFalse(replay.passed)
        assertTrue(replay.failures.single().contains("without a meaningful business tool"))
    }

    @Test
    fun musicChatWithoutSearchOrPlayFailsReplay() {
        val recorder = recorder(input = "最近有个很火的电视剧，主题曲是王菲唱的，你知道是什么歌吗")
        recorder.recordEvent(testTurnStarted(RUN_ID))
        recorder.recordEvent(testToolStarted(RUN_ID, "finish", """{"redacted":true}"""))
        recorder.recordEvent(testToolCompleted(RUN_ID, "finish", """{"redacted":true}"""))
        recorder.recordEvent(
            testTurnCompleted(result(RuntimeRunStatus.COMPLETED, "应该是《主角》。")),
        )

        val evidence = recorder.snapshot()
        val replay = RunReplayVerifier.verify(evidence)

        assertTrue(evidence.requiresBusinessAction)
        assertEquals(0, evidence.counts.meaningfulBusinessTools)
        assertFalse(replay.passed)
        assertTrue(replay.failures.single().contains("without a meaningful business tool"))
    }

    @Test
    fun hotFollowUpRequiresBusinessTool() {
        val recorder = recorder(input = "还是很热")
        recorder.recordEvent(testTurnStarted(RUN_ID))
        recorder.recordEvent(testToolStarted(RUN_ID, "read_file", """{"redacted":true}"""))
        recorder.recordEvent(testToolCompleted(RUN_ID, "read_file", """{"redacted":true}"""))
        recorder.recordEvent(testToolStarted(RUN_ID, "finish", """{"redacted":true}"""))
        recorder.recordEvent(testToolCompleted(RUN_ID, "finish", """{"redacted":true}"""))
        recorder.recordEvent(testTurnCompleted(result(RuntimeRunStatus.COMPLETED)))

        val evidence = recorder.snapshot()
        val replay = RunReplayVerifier.verify(evidence)

        assertTrue(evidence.requiresBusinessAction)
        assertFalse(replay.passed)
        assertTrue(replay.failures.single().contains("without a meaningful business tool"))
    }

    @Test fun aRunThatStoppedToAskPassesWithoutAFinish() {
        val recorder = recorder()
        recorder.recordEvent(testTurnStarted(RUN_ID))
        recorder.recordEvent(testToolStarted(RUN_ID, "ask", "{\"question\":\"几点出发？\"}", "q"))
        recorder.recordEvent(testToolCompleted(RUN_ID, "ask", "{\"ok\":true,\"waitingForUser\":true}", "q"))
        recorder.recordEvent(testTurnCompleted(RuntimeResult(RUN_ID, RuntimeRunStatus.COMPLETED, response = "几点出发？",
            completion = cc.noharry.agent.spi.reliability.RuntimeCompletionSummary(
                outcome = cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome.UNKNOWN))))
        assertTrue(RunReplayVerifier.verify(recorder.snapshot()).passed)
    }

    /**
     * A partial or blocked reply, recorded as the server records a run: the turn as the server reports it, then the
     * runtime's own result. One that asks the person stopped to ask; one that asks nothing is not counted as done.
     */
    @Test fun aPartialOrBlockedReplyThatAsksPassesAndOneThatDoesNotIsNotCountedAsDone() {
        AppHost.install(replayCheck = ReplayCheck.NONE)
        for (outcome in listOf(RuntimeCompletionOutcome.PARTIAL, RuntimeCompletionOutcome.BLOCKED)) {
            val asking = endedWith(outcome, "I cannot reach the car yet. Which car should I use?")
            assertEquals(outcome.name, RuntimeRunStatus.WAITING_USER_INPUT.name, asking.finalStatus)
            assertEquals(outcome.name, outcome.name, asking.finalCompletionOutcome)
            assertTrue(outcome.name, RunReplayVerifier.verify(asking).passed)
            val told = endedWith(outcome, "The habit list is turned off, so I cannot list your habits.")
            assertEquals(outcome.name, RuntimeRunStatus.COMPLETED.name, told.finalStatus)
            assertEquals(outcome.name, outcome.name, told.finalCompletionOutcome)
            assertTrue(outcome.name, RunReplayVerifier.verify(told).failures.single().startsWith("final result is not a first-party pass"))
        }
    }

    private fun endedWith(outcome: RuntimeCompletionOutcome, reply: String): RunEvidence {
        val result = RuntimeResult(RUN_ID, RuntimeRunStatus.COMPLETED, reply, completion = RuntimeCompletionSummary(outcome))
        val recorder = recorder()
        recorder.recordEvent(testTurnStarted(RUN_ID))
        recorder.recordEvent(hostTurnOutcome(testTurnCompleted(result), result))
        recorder.recordTerminalResult(result)
        return recorder.snapshot()
    }

    @Test fun completedToolItemsRoundTripThroughJson() {
        val recorder = recorder()
        recorder.recordEvent(testTurnStarted(RUN_ID))
        recorder.recordEvent(testToolStarted(RUN_ID, TOOL_NAME, "{\"tracking\":\"SF1234567890123\"}", "a"))
        recorder.recordEvent(testToolCompleted(RUN_ID, TOOL_NAME, "{\"ok\":true}", "a"))
        recorder.recordEvent(testTurnCompleted(result(RuntimeRunStatus.COMPLETED)))
        val evidence = recorder.snapshot()
        assertEquals(1, evidence.counts.publicStarted)
        assertEquals(1, evidence.counts.publicFinished)
        assertEquals(0, evidence.counts.pendingPublicTools)
        assertEquals(0, evidence.counts.failedTools)
        assertTrue(RunReplayVerifier.verify(evidence).passed)
        assertEquals(evidence, RunEvidenceJson.parse(recorder.write()!!.readText()))
        val json = Json.parseToJsonElement(recorder.write()!!.readText()).jsonObject
        assertFalse(json.containsKey("replay"))
        assertEquals("public_thread_events", json.getValue("checks").jsonObject.getValue("source").jsonPrimitive.content)
        assertFalse(json.getValue("counts").jsonObject.keys.any { it.startsWith("raw") })
        assertFalse(json.getValue("tools").jsonArray.single().jsonObject.keys.any { it.startsWith("raw") })
    }

    @Test fun sameNameToolsFinishOutOfOrderByStableId() {
        var now = 100L
        val recorder = recorder(nowEpochMillis = { now })
        recorder.recordEvent(testToolStarted(RUN_ID, TOOL_NAME, "{}", "one"))
        now = 200
        recorder.recordEvent(testToolStarted(RUN_ID, TOOL_NAME, "{}", "two"))
        now = 250
        recorder.recordEvent(testToolCompleted(RUN_ID, TOOL_NAME, "{}", "two", success = false))
        now = 400
        recorder.recordEvent(testToolCompleted(RUN_ID, TOOL_NAME, "{}", "one"))
        val tools = recorder.snapshot().tools.associateBy { it.toolCallId }
        assertEquals(300L, tools.getValue("one").publicDurationMs)
        assertEquals(50L, tools.getValue("two").publicDurationMs)
        assertEquals("FAILED", tools.getValue("two").status)
        assertEquals("COMPLETED", tools.getValue("one").status)
        assertEquals(1, recorder.snapshot().counts.failedTools)
    }

    @Test fun legacySyntheticRawFieldsRetainOnlyThePublicOutcomeWhenRead() {
        val recorder = recorder()
        recorder.recordEvent(testToolStarted(RUN_ID, TOOL_NAME, "{}", "one"))
        recorder.recordEvent(testToolCompleted(RUN_ID, TOOL_NAME, "{}", "one", success = false))
        val json = Json.parseToJsonElement(RunEvidenceJson.write(recorder.snapshot())).jsonObject
        val tool = json.getValue("tools").jsonArray.single().jsonObject
        val oldTool = JsonObject(tool - "status" + mapOf("rawPre" to JsonPrimitive(true), "rawPost" to JsonPrimitive(true), "rawPostStatus" to JsonPrimitive("FAILED")))
        val old = JsonObject(json + mapOf("schemaVersion" to JsonPrimitive(2), "tools" to JsonArray(listOf(oldTool))))
        val restored = RunEvidenceJson.parse(old.toString())
        assertEquals("FAILED", restored.tools.single().status)
        assertEquals(1, restored.counts.failedTools)
        assertFalse(RunEvidenceJson.write(restored).contains("rawPost"))
    }

    @Test fun hostedSearchCountsAsBusinessTool() {
        val recorder = recorder(input = "最近流行什么歌")
        recorder.recordEvent(testSearch(RUN_ID, "provider", "popular songs", 3))
        recorder.recordEvent(testTurnCompleted(result(RuntimeRunStatus.COMPLETED, "查到了这些歌曲。")))
        assertEquals(1, recorder.snapshot().counts.meaningfulBusinessTools)
        assertTrue(RunReplayVerifier.verify(recorder.snapshot()).passed)
    }

    @Test fun emptyCompletionDiagnosticsRoundTripWithoutPublicEvents() {
        val recorder = recorder()
        recorder.recordLoopProbes(listOf(cc.noharry.agent.spi.reliability.RuntimeTraceEvent(
            RUN_ID, "EMPTY_COMPLETION_OBSERVED", 1, details = mapOf(
                "modelRound" to "2", "kind" to "thinking_only", "continued" to "true", "continueCount" to "1",
                "textChars" to "0", "thinkingChars" to "120", "hadStateAdvancingWork" to "false", "successfulBackendToolCount" to "0",
            ),
        )))
        val probes = recorder.snapshot().loopProbes
        assertEquals(1, probes.emptyCompletionObservedCount)
        assertEquals(1, probes.emptyCompletionContinueCount)
        assertEquals(120, probes.lastEmptyCompletionThinkingChars)
        assertEquals(probes, RunEvidenceJson.parse(recorder.write()!!.readText()).loopProbes)
    }

    private fun recorder(
        input: String = "user text",
        nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    ): RunEvidenceRecorder =
        RunEvidenceRecorder(
            evidenceDir = Files.createTempDirectory("debug-run-evidence-").toFile(),
            request = RuntimeTurnRequest(REQUEST_ID, SESSION_ID, input, "debug-console"),
            providerLabel = "OpenAI",
            streamLabel = "stream",
            backendLabel = "simulated MCP",
            nowEpochMillis = nowEpochMillis,
        )

    private fun result(
        status: RuntimeRunStatus,
        response: String = "done",
        errorCode: String? = null,
    ): RuntimeResult =
        RuntimeResult(
            RUN_ID,
            status,
            response = response,
            errorCode = errorCode,
            completion = cc.noharry.agent.spi.reliability.RuntimeCompletionSummary(
                outcome = if (status == RuntimeRunStatus.COMPLETED) {
                    cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome.SUCCEEDED
                } else {
                    cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome.FAILED
                },
            ),
        )

    private companion object {
        const val REQUEST_ID = "request-1"
        const val SESSION_ID = "session-1"
        const val RUN_ID = "run-1"
        const val TOOL_NAME = "command_navi_SearchParking"
    }
}

private val ACTING_HOST = object : ReplayCheck {
    override fun requiresAction(request: String): Boolean = listOf("广州塔", "歌", "热").any { it in request }

    override fun answerProblems(request: String, answer: String): AnswerProblems = when (answer) {
        "process only" -> AnswerProblems(failures = listOf("final response is process narration"))
        "short" -> AnswerProblems(warnings = listOf("final response is short"))
        else -> AnswerProblems.NONE
    }
}

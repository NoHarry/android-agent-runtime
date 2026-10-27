package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.reliability.RuntimeCandidate
import cc.noharry.agent.kernel.api.reliability.RuntimeCandidateType
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.context.*
import cc.noharry.agent.kernel.core.loop.*
import cc.noharry.agent.kernel.core.memory.*
import cc.noharry.agent.kernel.core.plugin.*
import cc.noharry.agent.kernel.core.state.*
import cc.noharry.agent.kernel.core.tools.*
import cc.noharry.agent.kernel.core.trace.*
import cc.noharry.agent.runtime.reliability.FileBackedReliabilityStorage
import cc.noharry.agent.runtime.workspace.sessionCurrentJson
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMemoryHintsTest {
    @Test
    fun hintFollowingOnlyRunEmitsNoEfficientPathCandidate() {
        val candidates = extractMemoryCandidates(
            summary = NativeLoopSummary(
                attemptedTools = listOf(
                    "update_todo",
                    "mcp__default__command_ac_SetTemperature",
                    "finish",
                ),
                modelRoundCount = 3,
                successfulBackendTools = listOf("mcp__default__command_ac_SetTemperature"),
                memoryHintTools = listOf("mcp__default__command_ac_SetTemperature"),
            ),
            result = completedResult("run-hint-only"),
        )

        assertTrue(candidates.none { it.type == RuntimeCandidateType.MEMORY })
    }

    @Test
    fun runUsingToolOutsideMemoryHintsStillEmitsCandidate() {
        val candidates = extractMemoryCandidates(
            summary = NativeLoopSummary(
                attemptedTools = listOf(
                    "search_tools",
                    "mcp__default__command_ac_SetTemperature",
                    "mcp__default__command_ac_SetFanSpeed",
                    "finish",
                ),
                modelRoundCount = 4,
                successfulBackendTools = listOf(
                    "mcp__default__command_ac_SetTemperature",
                    "mcp__default__command_ac_SetFanSpeed",
                ),
                memoryHintTools = listOf("mcp__default__command_ac_SetTemperature"),
            ),
            result = completedResult("run-explored"),
        )

        val lesson = candidates.single { it.type == RuntimeCandidateType.MEMORY }
        assertTrue(lesson.summary.contains("ac set fan speed"))
        assertFalse(lesson.summary.contains("ac set temperature"))
    }

    @Test
    fun sessionInheritedOnlyRunEmitsNoCapabilityCandidate() {
        val inheritedTool = "mcp__default__command_ac_SetTemperature"
        val candidates = extractMemoryCandidates(
            summary = NativeLoopSummary(
                attemptedTools = listOf(inheritedTool, "finish"),
                modelRoundCount = 2,
                successfulBackendTools = listOf(inheritedTool),
                sessionInheritedTools = listOf(inheritedTool),
            ),
            result = completedResult("run-inherited-only"),
        )

        assertTrue(candidates.none { it.type == RuntimeCandidateType.MEMORY })
    }

    @Test
    fun currentRunDiscoveryExcludesSessionInheritedCapabilities() {
        val inheritedTool = "mcp__default__command_ac_SetTemperature"
        val discoveredTool = "mcp__default__command_ac_SetFanSpeed"
        val candidates = extractMemoryCandidates(
            summary = NativeLoopSummary(
                attemptedTools = listOf(inheritedTool, discoveredTool, "finish"),
                modelRoundCount = 3,
                successfulBackendTools = listOf(inheritedTool, discoveredTool),
                sessionInheritedTools = listOf(inheritedTool),
            ),
            result = completedResult("run-inherited-and-new"),
        )

        val lesson = candidates.single { it.type == RuntimeCandidateType.MEMORY }
        assertTrue(lesson.summary.contains("ac set fan speed"))
        assertFalse(lesson.summary.contains("ac set temperature"))
    }

    @Test
    fun efficientCapabilityLessonDropsNativeTools() {
        val lesson = efficientCapabilityLesson(
            listOf(
                "update_todo",
                "search_tools",
                "mcp__default__command_navi_KeyWordSearch",
                "mcp__default__command_music_PlaySearchMusic",
                "finish",
            ),
        ).orEmpty()

        assertEquals(
            "Observed capability set: music play search music; navi key word search",
            lesson,
        )
        assertTrue(lesson.contains("navi key word search"))
        assertTrue(lesson.contains("music play search music"))
        assertFalse(lesson.contains("search_tools"))
        assertFalse(lesson.contains("finish"))
    }

    @Test
    fun completedShortVerifiedRunExtractsMediumConfidenceCapabilityLesson() {
        val candidates = extractMemoryCandidates(
            summary = NativeLoopSummary(
                attemptedTools = listOf(
                    "update_todo",
                    "search_tools",
                    "read_file",
                    "mcp__default__command_navi_KeyWordSearch",
                    "mcp__default__command_navi_CalRoute",
                    "mcp__default__command_music_PlaySearchMusic",
                    "finish",
                ),
                modelRoundCount = 5,
                successfulBackendTools = listOf(
                    "mcp__default__command_navi_KeyWordSearch",
                    "mcp__default__command_navi_CalRoute",
                    "mcp__default__command_music_PlaySearchMusic",
                ),
            ),
            result = completedResult("run-1"),
        )

        val lesson = candidates.single { it.type == RuntimeCandidateType.MEMORY }
        assertEquals("medium", lesson.confidence)
        assertTrue(lesson.summary.contains("navi key word search"))
        assertTrue(lesson.summary.contains("music play search music"))
        assertFalse(lesson.summary.contains("mcp__default__command_navi_KeyWordSearch"))
    }

    @Test
    fun longOrFailedRunDoesNotEmitMemoryCandidate() {
        val longRun = NativeLoopSummary(
            attemptedTools = List(9) { "mcp__default__command_ac_SetTemperature$it" },
            modelRoundCount = 7,
            successfulBackendTools = listOf("mcp__default__command_ac_SetTemperature"),
        )
        val failedRun = NativeLoopSummary(
            attemptedTools = listOf("mcp__default__command_ac_SetTemperature"),
            modelRoundCount = 2,
            successfulBackendTools = listOf("mcp__default__command_ac_SetTemperature"),
            backendToolFailures = listOf(BackendToolFailureSummary("mcp__default__command_ac_SetFanSpeed", "failed")),
        )

        assertTrue(extractMemoryCandidates(longRun, completedResult("run-long")).none { it.type == RuntimeCandidateType.MEMORY })
        assertTrue(extractMemoryCandidates(failedRun, completedResult("run-failed")).none { it.type == RuntimeCandidateType.MEMORY })
    }

    @Test
    fun topLevelMcpErrorCannotBecomeCapabilityCandidate() {
        val tool = "mcp__default__command_ac_SetTemperature"
        val summary = NativeLoopSummaryBuilder().apply {
            recordModelRound()
            recordToolCall(tool, "{}")
            recordBackendToolResult(
                tool,
                """{"result":{"content":[],"isError":false},"isError":true,"id":1,"jsonrpc":"2.0"}""",
            )
        }.build()

        assertTrue(summary.successfulBackendTools.isEmpty())
        assertEquals(MCP_TOOL_IS_ERROR_CODE, summary.backendToolFailures.single().code)
        assertTrue(
            extractMemoryCandidates(summary, completedResult("run-top-level-error"))
                .none { it.type == RuntimeCandidateType.MEMORY },
        )
    }

    @Test
    fun capabilitySubsetDoesNotReinforceBroaderLesson() {
        val narrow = "Observed capability set: navi key word search"
        val broad = "Observed capability set: navi key word search; music play search music"

        assertFalse(runtimeMemoryLessonsMatch(narrow, broad))
        assertTrue(runtimeMemoryLessonsMatch(broad, "Observed capability set: music play search music; navi key word search"))
    }

    @Test
    fun onlyQualityGatedCapabilityCandidateReceivesPrewarmMode() {
        val capabilityCandidate = RuntimeCandidate(
            candidateId = "capability",
            runId = "run-1",
            type = RuntimeCandidateType.MEMORY,
            summary = "Observed capability set: ac set temperature",
            confidence = "medium",
            suggestedDestination = PREWARM_ELIGIBLE_MEMORY_DESTINATION,
        )
        val failureCandidate = capabilityCandidate.copy(
            candidateId = "failure",
            type = RuntimeCandidateType.TOOL_SCHEMA_FIX,
            suggestedDestination = "/context/memory/tool-failures/",
        )

        assertEquals(RuntimeLessonMode.PREWARM_ELIGIBLE, capabilityCandidate.lessonMode())
        assertEquals(RuntimeLessonMode.QUERY_ONLY, failureCandidate.lessonMode())
    }

    @Test
    fun completedNonSucceededOutcomesDoNotPromoteCapabilityLesson() {
        val summary = promotionQualitySummary()
        listOf(
            RuntimeCompletionOutcome.PARTIAL,
            RuntimeCompletionOutcome.BLOCKED,
            RuntimeCompletionOutcome.FAILED,
            RuntimeCompletionOutcome.UNKNOWN,
        ).forEach { outcome ->
            val candidates = extractMemoryCandidates(
                summary = summary,
                result = completedResult("run-${outcome.name.lowercase()}", outcome),
            )
            assertTrue(
                outcome.name,
                candidates.none { it.type == RuntimeCandidateType.MEMORY },
            )
        }
    }

    @Test
    fun completedSucceededPromotesCapabilityLesson() {
        val candidates = extractMemoryCandidates(
            summary = promotionQualitySummary(),
            result = completedResult("run-succeeded"),
        )
        assertTrue(candidates.any { it.type == RuntimeCandidateType.MEMORY })
    }

    @Test
    fun sessionCurrentMarksCompletedOnlyOnFirstPartyPass() {
        val storage = FileBackedReliabilityStorage(
            Files.createTempDirectory("hints-session-json-").toFile(),
        )
        val outcomes = listOf(
            "run-succeeded" to RuntimeCompletionOutcome.SUCCEEDED,
            "run-partial" to RuntimeCompletionOutcome.PARTIAL,
            "run-blocked" to RuntimeCompletionOutcome.BLOCKED,
            "run-failed" to RuntimeCompletionOutcome.FAILED,
            "run-unknown" to RuntimeCompletionOutcome.UNKNOWN,
        )
        outcomes.forEach { (runId, outcome) ->
            storage.saveResult(completedResult(runId, outcome))
        }
        val snapshots = outcomes.mapIndexed { index, (runId, _) ->
            RuntimeRunSnapshot(
                runId = runId,
                sessionId = "session-1",
                status = RuntimeRunStatus.COMPLETED,
                updatedAtEpochMillis = (index + 1).toLong(),
            )
        }
        val turns = Json.parseToJsonElement(sessionCurrentJson("session-1", snapshots, storage))
            .jsonObject["previousTurns"] as JsonArray
        val completedByRun = turns.associate { turn ->
            val obj = turn as JsonObject
            obj["runId"]!!.jsonPrimitive.content to obj["completed"]!!.jsonPrimitive.boolean
        }
        assertEquals(true, completedByRun["run-succeeded"])
        assertEquals(false, completedByRun["run-partial"])
        assertEquals(false, completedByRun["run-blocked"])
        assertEquals(false, completedByRun["run-failed"])
        assertEquals(false, completedByRun["run-unknown"])
    }

    private fun promotionQualitySummary(): NativeLoopSummary = NativeLoopSummary(
        attemptedTools = listOf(
            "search_tools",
            "mcp__default__command_ac_SetTemperature",
            "finish",
        ),
        modelRoundCount = 3,
        successfulBackendTools = listOf("mcp__default__command_ac_SetTemperature"),
    )

    private fun completedResult(
        runId: String,
        outcome: RuntimeCompletionOutcome = RuntimeCompletionOutcome.SUCCEEDED,
    ): RuntimeResult = RuntimeResult(
        runId = runId,
        status = RuntimeRunStatus.COMPLETED,
        response = "done",
        completion = RuntimeCompletionSummary(outcome = outcome),
    )
}

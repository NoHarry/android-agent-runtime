@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Collections

class SubagentsTest {
    private val parent = SubagentCall(sessionId = "session-1", runId = "run-1", modelRef = null)

    @Test
    fun theParentDelegatesWaitsAndGetsTheResult() = runBlocking {
        val launched = Collections.synchronizedList(mutableListOf<SubagentSpec>())
        val subagents = subagents { spec -> launched += spec; answerAfter(50, "苏州明天晴，22度") }
        val model = GoldenScriptedModel(
            listOf(
                round(tool("spawn_agent", """{"task":"查苏州明天的天气","label":"苏州天气"}""")),
                round(tool("wait_agent", """{"timeout_seconds":5}""")),
                round(finish("苏州明天晴")),
            ),
        )
        AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("subagents-").toFile())
            .modelClient(model)
            .subagents(subagents)
            .build()
            .run(goldenRequest())
            .toList()

        assertTrue(model.seenRequests.first().tools.any { it.name == "spawn_agent" })
        val spec = launched.single()
        assertEquals("查苏州明天的天气", spec.task)
        assertEquals(1, spec.depth)
        assertEquals("session-1", spec.parentSessionId)
        val waited = model.seenRequests[2].messages.last { it.role == "tool" }.content
        assertTrue(waited, waited.contains("\"status\":\"completed\"") && waited.contains("苏州明天晴，22度"))
        // Handed over through wait_agent: nothing left for the host.
        assertFalse(subagents.hasUnfinished("session-1"))
    }

    @Test
    fun aNamedAgentTypeReachesTheLauncherAndTheToolListsTheTypes() = runBlocking {
        val launched = Collections.synchronizedList(mutableListOf<SubagentSpec>())
        val types = listOf(SubagentType("trip-transport", "查去程回程的航班、车次或自驾路线"), SubagentType("trip-days", "排每天去哪并查天气"))
        val subagents = subagents(SubagentConfig(types = types)) { spec -> launched += spec; answerAfter(10, "G225 09:00 → 09:45") }
        val model = GoldenScriptedModel(
            listOf(
                round(tool("spawn_agent", """{"task":"上海→杭州 10/24 去 10/25 回，高铁","label":"高铁","agent":"trip-transport"}""")),
                round(tool("wait_agent", """{"timeout_seconds":5}""")),
                round(finish("好了")),
            ),
        )
        AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("subagents-").toFile())
            .modelClient(model)
            .subagents(subagents)
            .build()
            .run(goldenRequest())
            .toList()

        assertEquals("trip-transport", launched.single().agentType)
        val spawn = model.seenRequests.first().tools.single { it.name == "spawn_agent" }.schemaJson
        assertTrue(spawn, spawn.contains("\"enum\":[\"trip-transport\",\"trip-days\"]") && spawn.contains("trip-days: 排每天去哪并查天气"))
        // An unknown type is refused before anything starts; none at all keeps the plain tool.
        val unknown = subagents.execute("spawn_agent", """{"task":"x","agent":"trip-hotel"}""", parent)
        assertTrue(unknown, unknown.contains("SUBAGENT_TYPE_UNKNOWN") && unknown.contains("trip-transport, trip-days"))
        assertEquals(1, launched.size)
        assertFalse(cc.noharry.agent.kernel.core.nativeSubagentTools(emptyList()).single { it.name == "spawn_agent" }.schemaJson.contains("\"agent\""))
    }

    @Test
    fun aTypeNameIsAWordTheModelCanWrite() {
        assertTrue(runCatching { SubagentType("Trip Transport", "x") }.isFailure)
        assertTrue(runCatching { SubagentConfig(types = listOf(SubagentType("a", "x"), SubagentType("a", "y"))) }.isFailure)
    }

    @Test
    fun aChildCannotSpawnAndAConversationHasALimit() = runBlocking {
        val subagents = subagents(SubagentConfig(maxActivePerSession = 1)) { neverEnds() }
        val first = subagents.execute("spawn_agent", """{"task":"a"}""", parent)
        assertTrue(first, first.contains("\"ok\":true"))
        val childSession = subagents.statuses("session-1").single().id.let { "session-1-$it" }

        val nested = subagents.execute("spawn_agent", """{"task":"b"}""", SubagentCall(childSession, "child-run", null))
        val second = subagents.execute("spawn_agent", """{"task":"c"}""", parent)

        assertTrue(nested, nested.contains("SUBAGENT_DEPTH_LIMIT"))
        assertTrue(second, second.contains("SUBAGENT_LIMIT"))
        subagents.cancelSession("session-1")
    }

    @Test
    fun aChildWithoutProgressIsReportedStalledAndAnOverdueOneIsStopped() = runBlocking {
        val subagents = subagents(SubagentConfig(stallAfterMillis = 150, maxDurationMillis = 600)) { neverEnds() }
        subagents.execute("spawn_agent", """{"task":"慢任务"}""", parent)

        val stalled = subagents.execute("wait_agent", """{"timeout_seconds":5}""", parent)
        val stopped = subagents.execute("wait_agent", """{"timeout_seconds":5}""", parent)

        assertTrue(stalled, stalled.contains("\"status\":\"stalled\""))
        assertTrue(stopped, stopped.contains("\"status\":\"timed_out\""))
    }

    @Test
    fun aResultAfterTheRunEndedGoesToTheHostOnce() = runBlocking {
        val reports = Collections.synchronizedList(mutableListOf<SubagentReport>())
        val subagents = subagents { answerAfter(100, "三个景点") }
        subagents.onReport { reports += it }
        subagents.execute("spawn_agent", """{"task":"找景点","label":"景点"}""", parent)
        assertTrue(subagents.hasUnfinished("session-1"))

        subagents.runEnded("run-1")
        withTimeout(5_000) { while (reports.isEmpty()) delay(10) }

        val report = reports.single()
        assertEquals("session-1", report.parentSessionId)
        assertEquals(SubagentState.COMPLETED, report.statuses.single().state)
        assertEquals("三个景点", report.statuses.single().result)
        assertFalse(subagents.hasUnfinished("session-1"))
        delay(100)
        assertEquals(1, reports.size)
    }

    @Test
    fun lateResultsOfOneRunComeBackTogether() = runBlocking {
        val reports = Collections.synchronizedList(mutableListOf<SubagentReport>())
        val subagents = subagents { spec -> answerAfter(if (spec.label == "快") 20 else 300, spec.label + "的结果") }
        subagents.onReport { reports += it }
        subagents.execute("spawn_agent", """{"task":"a","label":"快"}""", parent)
        subagents.execute("spawn_agent", """{"task":"b","label":"慢"}""", parent)
        subagents.runEnded("run-1")

        withTimeout(5_000) { while (reports.isEmpty()) delay(10) }
        delay(100)

        // One turn for both, not one per child.
        assertEquals(1, reports.size)
        assertEquals(listOf("快的结果", "慢的结果"), reports.single().statuses.map { it.result })
    }

    @Test
    fun aResultDoesNotWaitForeverForASlowSibling() = runBlocking {
        val reports = Collections.synchronizedList(mutableListOf<SubagentReport>())
        val config = SubagentConfig(stallAfterMillis = 60_000, reportHoldMillis = 100)
        val subagents = subagents(config) { spec -> if (spec.label == "快") answerAfter(10, "好了") else neverEnds() }
        subagents.onReport { reports += it }
        subagents.execute("spawn_agent", """{"task":"a","label":"快"}""", parent)
        subagents.execute("spawn_agent", """{"task":"b","label":"慢"}""", parent)
        subagents.runEnded("run-1")

        withTimeout(10_000) { while (reports.isEmpty()) delay(10) }

        assertEquals(listOf("快"), reports.single().statuses.map { it.label })
        assertTrue(subagents.hasUnfinished("session-1"))
        subagents.cancelSession("session-1")
    }

    @Test
    fun aChildThatOnlySaysWhatItWillDoIsAskedForItsResult() = runBlocking {
        lateinit var subagents: Subagents
        subagents = subagents { spec ->
            AgentRuntimeBuilder.create()
                .workspaceRoot(Files.createTempDirectory("subagent-child-").toFile())
                .modelClient(
                    GoldenScriptedModel(
                        listOf(
                            listOf(cc.noharry.agent.kernel.api.model.ModelEvent.TextDelta("我将检索天津本周末的演出。"), cc.noharry.agent.kernel.api.model.ModelEvent.Completed),
                            round(finish("天津周日有一场交响音乐会")),
                        ),
                    ),
                )
                .subagents(subagents)
                .build()
                .run(goldenRequest(requestId = "${spec.sessionId}-run", sessionId = spec.sessionId))
        }
        subagents.execute("spawn_agent", """{"task":"查天津演出"}""", parent)

        val waited = subagents.execute("wait_agent", """{"timeout_seconds":5}""", parent)

        assertTrue(waited, waited.contains("天津周日有一场交响音乐会") && !waited.contains("我将检索"))
    }

    @Test
    fun theHostSeesEveryStepOfAChild() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<SubagentStatus>())
        val subagents = subagents { answerAfter(50, "好了") }
        subagents.onStatus { seen += it }
        subagents.execute("spawn_agent", """{"task":"查一下","label":"查询"}""", parent)
        withTimeout(5_000) { while (seen.none { it.state.finished }) delay(10) }

        assertEquals(SubagentState.RUNNING, seen.first().state)
        assertEquals("session-1-sub-1", seen.first().sessionId)
        assertTrue(seen.any { it.toolCalls == 1 && it.latest.startsWith("web_fetch") })
        val done = seen.last()
        assertEquals(SubagentState.COMPLETED, done.state)
        // The closing finish is no step of the work.
        assertEquals(1, done.toolCalls)
        assertTrue(done.latest.startsWith("web_fetch"))
        assertEquals(1, done.modelCalls)
        assertEquals(1200L, done.inputTokens)
        assertEquals(1000L, done.cachedTokens)
        assertEquals("查一下", done.task)
    }

    @Test
    fun aChildRuntimeHandsItsAnswerBackUnredacted() = runBlocking {
        lateinit var subagents: Subagents
        subagents = subagents { spec ->
            AgentRuntimeBuilder.create()
                .workspaceRoot(Files.createTempDirectory("subagent-child-").toFile())
                .modelClient(GoldenScriptedModel(listOf(round(finish("周日有市集，咨询电话 020-87654321")))))
                .subagents(subagents)
                .build()
                .run(goldenRequest(requestId = "${spec.sessionId}-run", sessionId = spec.sessionId))
        }
        subagents.execute("spawn_agent", """{"task":"查市集"}""", parent)

        val waited = subagents.execute("wait_agent", """{"timeout_seconds":5}""", parent)

        // The host's copy of the child's result reads "[REDACTED_PHONE]" for the number; the parent's model must not.
        assertTrue(waited, waited.contains("020-87654321"))
    }

    @Test
    fun aCancelledChildIsNotReported() = runBlocking {
        val reports = Collections.synchronizedList(mutableListOf<SubagentReport>())
        val subagents = subagents { neverEnds() }
        subagents.onReport { reports += it }
        subagents.execute("spawn_agent", """{"task":"x"}""", parent)
        subagents.runEnded("run-1")

        val later = SubagentCall("session-1", "run-2", null)
        val cancelled = subagents.execute("cancel_agent", """{"id":"sub-1"}""", later)
        subagents.runEnded("run-2")
        delay(100)

        assertTrue(cancelled, cancelled.contains("\"status\":\"cancelled\""))
        assertTrue(reports.isEmpty())
        assertFalse(subagents.hasUnfinished("session-1"))
    }

    private fun subagents(config: SubagentConfig = SubagentConfig(), launch: (SubagentSpec) -> kotlinx.coroutines.flow.Flow<AgentEvent>) =
        Subagents(config, SubagentLauncher(launch), CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private fun answerAfter(millis: Long, answer: String) = flow {
        emit(AgentEvent.NativeToolCallStarted("child", "web_fetch", "{}"))
        emit(AgentEvent.TokenUsageDelta("child", inputTokens = 1200, outputTokens = 40, cacheReadTokens = 1000))
        delay(millis)
        emit(AgentEvent.NativeToolCallStarted("child", "finish", "{}"))
        emit(AgentEvent.ResultAvailable(RuntimeResult(runId = "child", status = RuntimeRunStatus.COMPLETED, response = answer)))
    }

    private fun neverEnds() = flow<AgentEvent> { awaitCancellation() }
}

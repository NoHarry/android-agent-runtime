@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.appSystemPrompt
import cc.noharry.agent.appserver.skills.EVENT_SKILL
import cc.noharry.agent.appserver.skills.EVENT_TEXT
import cc.noharry.agent.appserver.skills.FACT_CHECK_KIND
import cc.noharry.agent.appserver.skills.FACT_CHECK_RULE
import cc.noharry.agent.appserver.skills.SCREEN_SKILL
import cc.noharry.agent.appserver.skills.SEARCH_RULE
import cc.noharry.agent.appserver.skills.TestHostRuns
import cc.noharry.agent.appserver.skills.loadRuntimeSkills
import cc.noharry.agent.appserver.skills.preloadedSkillBodies
import cc.noharry.agent.appserver.skills.saveRuntimeSkill
import cc.noharry.agent.appserver.skills.systemPromptIds
import cc.noharry.agent.appserver.skills.testHostSkills
import cc.noharry.agent.appserver.session.SessionStore
import cc.noharry.agent.appserver.session.modelContext
import cc.noharry.agent.runtime.RuntimeInspection.MAX_HOST_SYSTEM_PROMPT_BYTES
import cc.noharry.agent.runtime.RuntimeInspection.validateHostSystemPrompt
import cc.noharry.agent.vfs.VfsReadResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a run's prompt carries of a host's skills and rules, within the runtime's cap: a workflow's skill in full when
 * its words match, a skill too large deferred whole, the system part the same every run. On a host's skills in this
 * module's test assets ([testHostSkills]), for a Chinese persona.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RuntimePromptTest {
    private lateinit var context: Context

    @Before
    fun installHost() {
        AppHost.install(runs = TestHostRuns, skills = ::testHostSkills, persona = Persona("助手", "", language = "zh-CN"))
        context = ApplicationProvider.getApplicationContext()
        listOf("app_server_runtime_skills", "app_server_basic_tools").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After
    fun bareHost() {
        AppHost.install(runs = RunAddOns.NONE, skills = { emptyList() }, persona = Persona.GENERIC)
    }

    @Test fun aWorkflowRequestPreloadsItsSkillWithCompactHostInstructions() = runBlocking {
        val skills = loadRuntimeSkills(context)
        val bodies = preloadedSkillBodies(context, skills, EVENT_TEXT)
        assertEquals(listOf("progress-notes", EVENT_SKILL), bodies.map { it.first })
        val tools = loadBasicTools(context)
        // Even a new session has nonempty continuity metadata in the production runner.
        val session = SessionStore(File(context.cacheDir, "prompt-context")).modelContext("broadcast-test")
        assertTrue("Keep shared instructions under 4.5 KiB", appSystemPrompt("").toByteArray().size <= 4608)
        val prompt = runtimePrompt(session, tools, bodies).text
        validateHostSystemPrompt(prompt)
        assertTrue(prompt.contains(bodies.first().second))
        assertTrue(prompt.contains(bodies.last().second))
        assertFalse(prompt.contains("Deferred runtime skill"))
        val vfs = workspaceVfs(context, File(context.cacheDir, "prompt-skills"), skills)
        for ((id, body) in bodies) {
            val read = vfs.read("/.agents/skills/$id/SKILL.md", 0, 65536) as VfsReadResult.Found
            assertEquals(body, read.text)
        }
    }

    @Test fun everySkillCombinationAndLongMultibyteHistoryFitTheSameHostCap() {
        val all = loadRuntimeSkills(context)
        val tools = loadBasicTools(context)
        val input = "$EVENT_TEXT，$MIXED_BROADCAST_TEXT"
        val history = "lastSuccessfulAction=previous\n" + (1..30).joinToString("\n") { "turn=$it " + "广州😀".repeat(70) }
        for (mask in 0 until (1 shl all.size)) {
            val skills = all.mapIndexed { index, skill -> skill.copy(enabled = mask and (1 shl index) != 0) }
            val bodies = preloadedSkillBodies(context, skills, input)
            for (session in listOf("", "最近一轮：去广州塔。", history)) {
                val prompt = runtimePrompt(session, tools, bodies, systemSkills = skills.systemPromptIds()).text
                validateHostSystemPrompt(prompt)
                assertFalse(prompt.contains('�'))
                bodies.forEach { (id, body) ->
                    assertTrue("mask=$mask id=$id", prompt.contains(body) || prompt.contains("Deferred runtime skill: $id."))
                }
                if (session == history) {
                    assertTrue(prompt.contains("lastSuccessfulAction=previous"))
                    assertTrue(prompt.contains("Read /context/session/current.json"))
                }
            }
        }
    }

    @Test fun aWorkflowStillPreloadsWithNormalContinuity() {
        val skills = loadRuntimeSkills(context).map { it.copy(enabled = true) }.filter { it.id != "task-orchestration" }
        val bodies = preloadedSkillBodies(context, skills, EVENT_TEXT)
        assertEquals(listOf("progress-notes", EVENT_SKILL), bodies.map { it.first })
        val prompt = runtimePrompt("lastSuccessfulAction=none", loadBasicTools(context), bodies).text
        assertTrue(bodies.all { prompt.contains(it.second) })
        assertTrue(prompt.contains("lastSuccessfulAction=none"))
        assertFalse(prompt.contains("Deferred runtime skill"))
    }

    @Test fun aSystemSkillIsReservedUnderHistoryPressureAndRemovedWhenDisabled() {
        val enabled = loadRuntimeSkills(context)
        val bodies = preloadedSkillBodies(context, enabled, "查一下附近的停车场")
        val notes = bodies.single { it.first == "progress-notes" }.second
        val tools = loadBasicTools(context)
        val prompt = runtimePrompt("历史内容\n".repeat(4000), tools, bodies, systemSkills = enabled.systemPromptIds())
        validateHostSystemPrompt(prompt.text)
        assertTrue(prompt.system.contains(notes))
        assertTrue(prompt.text.contains("channel=speech, phase=progress"))
        assertFalse(prompt.text.contains("Deferred runtime skill: progress-notes"))
        saveRuntimeSkill(context, "progress-notes", false)
        val disabled = runtimePrompt("", tools, preloadedSkillBodies(context, loadRuntimeSkills(context), "")).text
        assertFalse(disabled.contains("进度提示"))
        assertFalse(disabled.contains("Active runtime skill: progress-notes"))
        assertEquals(bodies, preloadedSkillBodies(context, enabled, "查一下附近的停车场"))
    }

    @Test fun oversizedUnicodeSkillIsDeferredWholeRatherThanTruncated() {
        val body = "完整约束😀".repeat(5000)
        val prompt = runtimePrompt("", loadBasicTools(context), listOf("large" to body)).text
        validateHostSystemPrompt(prompt)
        assertTrue(prompt.contains("/.agents/skills/large/SKILL.md"))
        assertFalse(prompt.contains("完整约束"))
    }

    @Test fun exactUtf8BoundaryFitsAndOneExtraByteDefersTheWholeSkill() {
        val tools = loadBasicTools(context)
        val fixed = runtimePrompt("", tools, emptyList()).text
        val remaining = MAX_HOST_SYSTEM_PROMPT_BYTES - (fixed + "\n\nActive runtime skill: boundary\n").toByteArray().size
        val body = "中".repeat(remaining / 3) + "a".repeat(remaining % 3)
        val exact = runtimePrompt("", tools, listOf("boundary" to body)).text
        assertEquals(MAX_HOST_SYSTEM_PROMPT_BYTES, exact.toByteArray().size)
        assertTrue(exact.beforeThinkingLine().endsWith(body))
        val overflow = runtimePrompt("", tools, listOf("boundary" to body + "a")).text
        assertTrue(overflow.contains("Deferred runtime skill: boundary"))
        assertFalse(overflow.contains(body))
    }

    @Test fun systemPartStaysTheSameAcrossRunsWhilePerRunPartsGoToRunContext() {
        val tools = loadBasicTools(context)
        val skills = loadRuntimeSkills(context).map { it.copy(enabled = true) }.filter { it.id != "task-orchestration" }
        val first = runtimePrompt("", tools, preloadedSkillBodies(context, skills, EVENT_TEXT))
        val previous = PreviousExchange("s", "t", "导航去广州塔", "已开始导航", "COMPLETED", "SUCCEEDED")
        val later = runtimePrompt("最近一轮：去广州塔。", tools, preloadedSkillBodies(context, skills, "查天气"), previous)
        assertEquals(first.system, later.system)
        assertTrue(first.runContext.contains("Active runtime skill: $EVENT_SKILL"))
        assertFalse(first.system.contains(EVENT_SKILL))
        assertTrue(later.runContext.contains("最近一轮：去广州塔。"))
        assertFalse(later.runContext.contains(previous.assistantText))
        assertFalse(later.system.contains("Current debug session context"))
    }

    @Test fun aSkillCountsAsCarriedOnlyWhenAnEarlierRunSentItInFull() {
        val tools = loadBasicTools(context)
        val skills = loadRuntimeSkills(context).map { it.copy(enabled = true) }.filter { it.id != "task-orchestration" }
        val sent = runtimePrompt("", tools, preloadedSkillBodies(context, skills, EVENT_TEXT)).runContext
        val carried = listOf(cc.noharry.agent.spi.model.ModelMessage(role = "user", content = sent))
        assertTrue(carried.carriesSkill(EVENT_SKILL))
        assertFalse(carried.carriesSkill(SCREEN_SKILL))
        val deferredOnly = listOf(cc.noharry.agent.spi.model.ModelMessage(role = "user", content = "Deferred runtime skill: $SCREEN_SKILL. Before using its workflow, read it."))
        assertFalse(deferredOnly.carriesSkill(SCREEN_SKILL))
    }

    @Test fun unavailableReadToolCannotSilentlyDiscardSkillInstructions() {
        val tools = loadBasicTools(context).map { if (it.name == "read_file") it.copy(enabled = false) else it }
        val error = assertThrows(IllegalArgumentException::class.java) {
            runtimePrompt("", tools, listOf("large" to "中文".repeat(10000))).text
        }
        assertTrue(error.message!!.contains("Enable read_file"))
        assertTrue(runtimePrompt("", tools, listOf("small" to "完整内容")).text.contains("完整内容"))
    }

    @Test
    fun theClockGoesWithTheRunContextNotTheSystemPrompt() {
        val noon = java.time.ZonedDateTime.of(2026, 10, 19, 11, 35, 0, 0, java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val clock = clockLine(noon, java.time.ZoneId.of("Asia/Shanghai"))

        val prompt = runtimePrompt("", emptyList(), emptyList(), clock = clock)

        assertEquals("现在：2026-10-19 周一 11:35", clock)
        assertTrue(prompt.runContext.startsWith(clock))
        assertFalse(prompt.system.contains("现在："))
        assertEquals(runtimePrompt("", emptyList(), emptyList()).system, prompt.system)
        assertTrue("the thinking is asked for in Chinese with the run, not in the cached system prompt", prompt.runContext.contains(THINK_IN_CHINESE))
        assertFalse(prompt.system.contains(THINK_IN_CHINESE))
    }

    @Test
    fun theHostsRulesAreInTheCachedSystemPromptInItsOrderOnlyWhileTheyHold() {
        val both = runtimePrompt("", emptyList(), emptyList(), hostRules = TestHostRuns.systemRules(facts(check = true, search = true)))
        val check = runtimePrompt("", emptyList(), emptyList(), hostRules = TestHostRuns.systemRules(facts(check = true)))
        val without = runtimePrompt("", emptyList(), emptyList(), hostRules = TestHostRuns.systemRules(facts()))

        assertTrue(both.system.indexOf(SEARCH_RULE) in 0 until both.system.indexOf(FACT_CHECK_RULE))
        assertTrue(check.system.contains(FACT_CHECK_RULE) && !check.system.contains(SEARCH_RULE))
        assertFalse(without.system.contains(FACT_CHECK_KIND))
        assertEquals(check.runContext, without.runContext)
        assertFalse(both.runContext.contains(FACT_CHECK_RULE))
    }

    private fun facts(check: Boolean = false, search: Boolean = false) = RunFacts(
        context = ApplicationProvider.getApplicationContext(),
        userInput = "",
        child = false,
        webSearch = search,
        subagentTypes = if (check) setOf(FACT_CHECK_KIND) else emptySet(),
    )
}

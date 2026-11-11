@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.skills

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.BuiltInFeatures
import cc.noharry.agent.appserver.runtime.Persona
import cc.noharry.agent.appserver.runtime.RunAddOns
import cc.noharry.agent.runtime.SubagentSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A host's subagent types ([TEST_SUBAGENT_KINDS], from this module's test assets): their tools and share of lookups
 * until reset, what a typed child is told, which are offered as they are switched and as lookups are at hand, and the
 * workflow skill's sections that follow them. In a Chinese persona's words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SubagentKindsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun installHost() {
        AppHost.install(
            runs = TestHostRuns, skills = ::testHostSkills, persona = Persona("助手", "", language = "zh-CN"),
            builtIns = BuiltInFeatures(subagents = true),
        )
    }

    @After
    fun switchEveryTypeBackOn() {
        loadSubagentKinds(context).forEach {
            saveSubagentKind(context, it.id, true)
            resetSubagentKindTools(context, it.id)
        }
        AppHost.install(runs = RunAddOns.NONE, skills = { emptyList() }, persona = Persona.GENERIC, builtIns = BuiltInFeatures.NONE)
    }

    @Test
    fun aTypeKeepsItsToolsAndShareUntilReset() {
        val venues = appSubagentKind(context, "event-venues")!!
        val guests = appSubagentKind(context, "event-guests")!!
        assertEquals("联网搜索、打开网页、读文件、待办、查设备状态、其他服务", venues.toolLabels())
        assertFalse(SubagentToolGroup.DEVICE_QUERY in guests.tools)
        assertTrue(SubagentToolGroup.entries.none { it in guests.tools && it.changes && it != SubagentToolGroup.SERVICES })

        saveSubagentKindTools(context, "event-guests", setOf(SubagentToolGroup.FETCH, SubagentToolGroup.WRITE), 99)

        val changed = appSubagentKind(context, "event-guests")!!
        assertEquals(setOf(SubagentToolGroup.FETCH, SubagentToolGroup.WRITE), changed.tools)
        assertEquals(MAX_LOOKUPS, changed.maxLookups)
        assertFalse(changed.searches)
        resetSubagentKindTools(context, "event-guests")
        assertEquals(guests, appSubagentKind(context, "event-guests"))
    }

    @Test
    fun aTypedChildIsToldItsToolsAndAChildThatCannotSearchIsNotToldToSearch() {
        val spec = SubagentSpec("sub-1", "s-sub-1", "s", "r", "来宾", "12 位来宾，周六晚上", 1, null, true, agentType = "event-guests")
        val guests = appSubagentKind(context, "event-guests")!!

        val input = subagentInput(spec, guests)
        assertTrue(input, input.contains("能用的工具：联网搜索、打开网页、读文件、待办、其他服务；别的工具会被拒绝。") && input.contains("查资料先联网搜索"))
        // Device control is the type's to give too, whatever the subagent setting allows.
        assertTrue(input.contains("不能操作设备"))

        val offline = subagentInput(spec, guests.copy(tools = setOf(SubagentToolGroup.READ)))
        assertTrue(offline, !offline.contains("联网搜索") && !offline.contains("一共最多") && offline.contains("能用的工具：读文件"))
    }

    @Test
    fun theTypesAreOnByDefaultAndASwitchReachesTheNextSpawns() {
        AppSubagents.apply(context)
        AppSubagents.refreshTypes(context, webLookup = false)
        assertEquals(listOf("event-venues", "event-guests"), AppSubagents.instance.config.types.map { it.name })

        saveSubagentKind(context, "event-guests", false)

        // The switch reads whether lookups are at hand again: the check joins in where a profile has them.
        assertEquals(listOf("event-venues"), AppSubagents.instance.config.types.map { it.name }.filter { it != FACT_CHECK_KIND })
        assertNull(appSubagentKind(context, "event-guests"))
    }

    @Test
    fun aCheckIsOfferedOnlyWhileLookupsAreAtHand() {
        val check = appSubagentKind(context, FACT_CHECK_KIND)!!
        assertEquals(setOf(SubagentToolGroup.SEARCH, SubagentToolGroup.FETCH), check.tools)
        assertEquals(6, check.maxLookups)
        assertEquals(true, check.searchApi)
        assertTrue(check.description, check.description.contains("finish without waiting"))

        AppSubagents.refreshTypes(context, webLookup = true)
        assertEquals(listOf("event-venues", "event-guests", FACT_CHECK_KIND), AppSubagents.instance.config.types.map { it.name })
        AppSubagents.refreshTypes(context, webLookup = false)
        assertEquals(listOf("event-venues", "event-guests"), AppSubagents.instance.config.types.map { it.name })

        // Its own fixed addresses (the status page) are not the guessed URLs the general rule forbids.
        val spec = SubagentSpec("sub-1", "s-sub-1", "s", "r", "核实", "CA3636，2026-10-19", 1, null, false, agentType = FACT_CHECK_KIND)
        val input = subagentInput(spec, check, subagentKindText(context, check))
        assertTrue(input, input.contains("或你的分工里写明的网址") && input.contains("status.example.com/flights/"))
    }

    @Test
    fun aTypedChildGetsItsInstructionsAndItsShareOfLookupsInsteadOfThePictureRule() {
        loadSubagentKinds(context).forEach { kind ->
            val text = subagentKindText(context, kind)
            assertTrue(kind.id, text.startsWith("# ") && text.contains("交回的格式") && !text.contains("---"))
        }
        val kind = appSubagentKind(context, "event-venues")!!
        val spec = SubagentSpec("sub-1", "s-sub-1", "s", "r", "场地", "10/24 晚上，30 人，人均 200 元", 1, null, false, agentType = "event-venues")

        val input = subagentInput(spec, kind.copy(maxLookups = 5), subagentKindText(context, kind))

        assertTrue(input, input.contains("你的分工（event-venues）：\n# 活动场地") && input.contains("一共最多 5 次") && input.endsWith("人均 200 元"))
        assertFalse(input.contains("图片：https"))
    }

    @Test
    fun theWorkflowSkillNamesATypeWhileItIsOnAndSpellsTheTaskOutWhileItIsOff() {
        val skills = loadRuntimeSkills(context)
        val on = preloadedSkillPrompt(context, skills, EVENT_TEXT)

        assertTrue(on.contains("agent 写 event-venues") && !on.contains("场地页常列出") && !on.contains("<!--"))
        saveSubagentKind(context, "event-venues", false)
        val off = preloadedSkillPrompt(context, skills, EVENT_TEXT)
        assertTrue(off.contains("场地页常列出") && !off.contains("agent 写 event-venues") && off.contains("agent 写 event-guests"))
    }

    @Test
    fun aSectionIsKeptByItsKeyAndItsOppositeOtherwise() {
        val text = "a\n<!-- agent:x -->\nX\n<!-- /agent:x -->\n<!-- no-agent:x -->\nnot X\n<!-- /no-agent:x -->\nb"

        assertEquals("a\nX\nb", text.withSections(setOf("agent:x")))
        assertEquals("a\nnot X\nb", text.withSections(emptySet()))
    }
}

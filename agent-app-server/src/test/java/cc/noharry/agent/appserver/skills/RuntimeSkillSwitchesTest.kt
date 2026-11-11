package cc.noharry.agent.appserver.skills

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.RunAddOns
import cc.noharry.agent.appserver.runtime.workspaceVfs
import cc.noharry.agent.appserver.server.PluginAdditions
import cc.noharry.agent.vfs.VfsReadResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A host's skills as the person switches them and as a run carries them: the host's defaults until a choice is saved,
 * a switched-off skill gone from the workspace while a run already started keeps its snapshot, and a skill that needs
 * a plugin's section or tool server only while a running plugin gives it. On [testHostSkills].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RuntimeSkillSwitchesTest {
    private lateinit var context: Context

    @Before
    fun installHost() {
        AppHost.install(runs = TestHostRuns, skills = ::testHostSkills)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_server_runtime_skills", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun bareHost() {
        AppHost.install(runs = RunAddOns.NONE, skills = { emptyList() }, additions = PluginAdditions.NONE)
    }

    @Test
    fun theHostsDefaultsHoldUntilAChoiceIsSaved() {
        assertEquals(TEST_DEFAULT_SKILLS, loadRuntimeSkills(context).filter { it.enabled }.map { it.id })
        saveRuntimeSkill(context, EVENT_SKILL, false)
        assertFalse("a saved choice overrides the default", loadRuntimeSkills(context).single { it.id == EVENT_SKILL }.enabled)
        assertTrue(preloadedSkillPrompt(context, loadRuntimeSkills(context)).contains("Active runtime skill: progress-notes"))
    }

    @Test
    fun disabledSkillsAreAbsentFromVfsAndExistingRuntimeSnapshotStaysFixed() = runBlocking {
        saveRuntimeSkill(context, "task-orchestration", true)
        val snapshot = loadRuntimeSkills(context)
        val active = workspaceVfs(context, File(context.cacheDir, "skills-active"), snapshot)
        saveRuntimeSkill(context, "task-orchestration", false)
        saveRuntimeSkill(context, "progress-notes", false)
        val disabled = workspaceVfs(context, File(context.cacheDir, "skills-disabled"))
        val path = "/.agents/skills/task-orchestration/SKILL.md"
        assertTrue(active.read(path, 0, 20_000) is VfsReadResult.Found)
        assertFalse(disabled.read(path, 0, 20_000) is VfsReadResult.Found)
        assertFalse(disabled.read("/.agents/skills/progress-notes/SKILL.md", 0, 20_000) is VfsReadResult.Found)
        assertTrue(preloadedSkillPrompt(context, snapshot).contains("多服务任务编排"))
        assertEquals("", preloadedSkillPrompt(context, loadRuntimeSkills(context)))
    }

    @Test
    fun aSkillThatNeedsASectionWaitsForAPluginToSayItHolds() {
        assertFalse(preloadedSkillPrompt(context, loadRuntimeSkills(context)).contains("agent-delegation"))
        // As the A2A plugin says while the person's own agent is connected.
        AppHost.install(additions = PluginAdditions(skillSections = listOf { setOf("a2a") }))
        assertTrue(preloadedSkillPrompt(context, loadRuntimeSkills(context)).contains("Active runtime skill: agent-delegation"))
    }

    @Test
    fun aSkillThatDescribesAToolServerWaitsForARunThatHasIt() {
        val skills = loadRuntimeSkills(context)
        assertFalse(preloadedSkillBodies(context, skills, "").any { it.first == SCREEN_SKILL })
        assertTrue(preloadedSkillBodies(context, skills, "", runToolServers = setOf(SCREEN_TOOL_SERVER)).any { it.first == SCREEN_SKILL })
    }

    @Test
    fun anObsoleteSkillPreferenceCannotLoadOldInstructions() = runBlocking {
        context.getSharedPreferences("app_server_runtime_skills", Context.MODE_PRIVATE).edit()
            .putBoolean("retired-trip-planning", true).commit()
        assertEquals(TEST_DEFAULT_SKILLS, loadRuntimeSkills(context).filter { it.enabled }.map { it.id })
        val workspace = workspaceVfs(context, File(context.cacheDir, "skills-obsolete"))
        assertFalse(workspace.read("/.agents/skills/retired-trip-planning/SKILL.md", 0, 20_000) is VfsReadResult.Found)
    }

    @Test
    fun aSkillsSwitchPersistsEitherWay() {
        for (on in listOf(false, true, false)) {
            saveRuntimeSkill(context, "task-orchestration", on)
            val skills = loadRuntimeSkills(context)
            assertEquals(on, skills.single { it.id == "task-orchestration" }.enabled)
            assertEquals(on, preloadedSkillPrompt(context, skills).contains("Active runtime skill: task-orchestration"))
            assertTrue(preloadedSkillPrompt(context, skills).contains("Active runtime skill: progress-notes"))
        }
    }
}

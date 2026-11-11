@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.skills.EVENT_SKILL
import cc.noharry.agent.appserver.skills.saveRuntimeSkill
import cc.noharry.agent.appserver.skills.testHostSkills
import cc.noharry.agent.runtime.RuntimeInspection
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The basic tools page: the runtime's native tools, exec_js off at first, finish always on, choices kept apart from skills. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BasicToolConfigTest {
    @After
    fun bareHost() {
        AppHost.install(skills = { emptyList() }, builtIns = BuiltInFeatures.NONE)
    }

    private lateinit var context: Context

    @Before
    fun resetPreferences() {
        // A host that turns on every feature that has tools.
        AppHost.install(skills = ::testHostSkills, builtIns = BuiltInFeatures(fileWriting = true, web = true, scripts = true, subagents = true))
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_server_basic_tools", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("app_server_runtime_skills", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun pageCoversActualNativeToolsWithExecOffByDefault() {
        val tools = loadBasicTools(context)
        assertEquals(RuntimeInspection.nativeToolNames(), tools.map { it.name }.toSet())
        assertEquals(setOf("exec_js"), disabledBasicTools(tools))
        assertEquals(listOf("finish"), tools.filter { it.required }.map { it.name })
    }

    @Test
    fun persistenceAndSkillsDoNotChangeAnExistingRequestSnapshot() {
        val snapshot = loadBasicTools(context)
        saveRuntimeSkill(context, EVENT_SKILL, true)
        assertFalse(loadBasicTools(context).single { it.name == "exec_js" }.enabled)
        saveBasicTool(context, "exec_js", true)
        saveBasicTool(context, "search_tools", false)
        assertEquals(setOf("search_tools"), disabledBasicTools(loadBasicTools(context)))
        assertEquals(setOf("exec_js"), disabledBasicTools(snapshot))
        saveRuntimeSkill(context, EVENT_SKILL, false)
        assertTrue(loadBasicTools(context).single { it.name == "exec_js" }.enabled)
        assertEquals("", basicToolPrompt(snapshot))
        val noDiscovery = basicToolPrompt(loadBasicTools(context))
        assertTrue(noDiscovery.contains("disabled native tools: search_tools"))
        assertFalse(noDiscovery.contains("exec_js"))
    }

    @Test
    fun finishCannotBeDisabledEvenWithStalePreferences() {
        assertThrows(IllegalArgumentException::class.java) { saveBasicTool(context, "finish", false) }
        context.getSharedPreferences("app_server_basic_tools", Context.MODE_PRIVATE).edit().putBoolean("finish", false).commit()
        assertTrue(loadBasicTools(context).single { it.name == "finish" }.enabled)
        assertThrows(IllegalStateException::class.java) { saveBasicTool(context, "missing", false) }
    }
}

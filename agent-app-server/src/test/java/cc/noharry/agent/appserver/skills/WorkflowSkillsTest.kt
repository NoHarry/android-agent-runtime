@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.skills
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.outputContractSummary
import cc.noharry.agent.spi.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A host's workflow skills ([testHostSkills]): picked by their manifest's triggers, binding the tools they name. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkflowSkillsTest {
    @After
    fun bareHost() {
        AppHost.install(skills = { emptyList() })
    }

    @Before
    fun installHost() {
        AppHost.install(skills = ::testHostSkills)
    }

    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_server_runtime_skills", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun aWorkflowIsCarriedWhenItsTriggersMatchAndItsSectionsFollowTheConnectedAgent() {
        val skills = loadRuntimeSkills(context)
        listOf(EVENT_TEXT, "周末想办个派对", "帮我策划一场年会活动", "给女儿办生日会").forEach { input ->
            assertTrue(input, selectedWorkflowSkills(context, skills, input).any { it.skill.id == EVENT_SKILL })
        }
        // A single question about a place or an errand needs no plan.
        listOf("这个活动几点开始", "导航去会展中心", "去公司", "推荐一下附近的餐厅").forEach { input ->
            assertFalse(input, selectedWorkflowSkills(context, skills, input).any { it.skill.id == EVENT_SKILL })
        }
        assertTrue(preloadedSkillPrompt(context, skills, EVENT_TEXT).contains("# 活动筹备"))
        // The backup to a personal assistant is offered only while one is connected (a plugin's `a2a` section).
        assertFalse(preloadedSkillPrompt(context, skills, EVENT_TEXT).contains("个人助手"))
        AppHost.install(additions = cc.noharry.agent.appserver.server.PluginAdditions(skillSections = listOf { setOf("a2a") }))
        try {
            val connected = preloadedSkillPrompt(context, skills, EVENT_TEXT)
            assertTrue(connected.contains("或者把流程发给你的个人助手备份？」") && !connected.contains("<!--"))
            assertEquals(1, Regex("(?m)^5\\. ").findAll(connected).count())
        } finally {
            AppHost.install(additions = cc.noharry.agent.appserver.server.PluginAdditions.NONE)
        }
    }

    @Test fun aManifestMatchesItsTriggersAndBindsOnlyToolsThatStillHaveItsFields() = runBlocking {
        val skill = RuntimeSkill("volume-workflow", "Volume", "", "", enabled = true, preload = true)
        assertNull(parseWorkflowSkill(skill, VOLUME_MANIFEST, "解释量子力学"))
        val workflow = parseWorkflowSkill(skill, VOLUME_MANIFEST, "查询音量")!!
        val valid = ToolDescriptor("system_volume_get", "read volume", """{"type":"object","properties":{"stream":{"type":"string"}}}""",
            outputSchemaJson = """{"type":"object","properties":{"data":{"type":"object","properties":{"volume":{"type":"integer"}}}}}""")
        val drifted = ToolDescriptor("system_volume_set", "changed", """{"type":"object","properties":{"value":{"type":"number"}}}""")
        val delegate = object : ToolProvider {
            override val providerId = "device"
            override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
            override suspend fun listTools() = listOf(valid, drifted)
            override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext) = error("prefetch must never execute")
        }
        val provider = SkillToolProvider(delegate, listOf(workflow))
        val tools = provider.listTools()
        val request = ToolPrefetchRequest("查询音量", tools, ToolCallContext("s", "r", "q"), 24)
        assertEquals(listOf("system_volume_get"), provider.localToolNames(request))
        assertFalse(provider.remotePrefetchEnabled)
        assertTrue(tools.first().description.contains("data.volume?"))
        assertEquals(drifted.description, tools.last().description)
        assertEquals(valid.outputSchemaJson, tools.first().outputSchemaJson)
    }

    @Test fun optionalNestedOutputFieldsAreHintsAndDoNotBecomeDefaults() {
        val schema = """{"type":"object","properties":{"data":{"anyOf":[{"type":"object","properties":{"songs":{"type":"array","items":{"type":"object","properties":{"id":{"type":"string"},"name":{"type":"string"}}}}}},{"type":"object"}]}}}"""
        val summary = outputContractSummary(schema)
        assertTrue(summary.contains("data.songs[].id?"))
        assertTrue(summary.contains("omission is unknown"))
        assertTrue(summary.contains("full outputSchema"))
        assertEquals("", outputContractSummary("not json"))
        assertEquals("", outputContractSummary(null))
    }
}

private const val VOLUME_MANIFEST =
    """{"schemaVersion":1,"triggers":["音量"],"bindings":{"system_volume_get":["stream"],"system_volume_set":["stream","volume"]}}"""

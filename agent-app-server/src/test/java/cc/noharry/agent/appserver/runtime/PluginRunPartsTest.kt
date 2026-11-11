@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.ApprovalPolicy
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.server.OwnToolServer
import cc.noharry.agent.appserver.server.PluginAdditions
import cc.noharry.agent.appserver.server.PluginMcpServer
import cc.noharry.agent.appserver.server.PluginSkill
import cc.noharry.agent.appserver.server.PluginTools
import cc.noharry.agent.appserver.skills.loadRuntimeSkills
import cc.noharry.agent.appserver.skills.saveRuntimeSkill
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.HookDecision
import cc.noharry.agent.spi.RuntimeEffectKind
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.spi.ToolSource
import cc.noharry.agent.spi.ToolUse
import cc.noharry.agent.vfs.VfsListResult
import cc.noharry.agent.vfs.VfsReadResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the running plugins add reaches a run: their skills, their rules after a guard, their MCP servers and the gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PluginRunPartsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun noPlugins() = AppHost.install(additions = PluginAdditions.NONE)

    @Test
    fun aPluginsSkillsAreRunSkillsSwitchedLikeAnyOther() {
        val text = "---\nname: find\ndescription: Find a note.\n---\n\nRead them.\n"
        AppHost.install(additions = PluginAdditions(skills = listOf(PluginSkill("com.example.notes:find", "Find a note.", text, preload = true))))

        val skill = loadRuntimeSkills(app).single { it.id == "com.example.notes:find" }
        assertEquals(text, skill.text)
        assertTrue(skill.enabled && skill.preload && skill.file == null)
        saveRuntimeSkill(app, "com.example.notes:find", false)
        assertFalse(loadRuntimeSkills(app).single { it.id == "com.example.notes:find" }.enabled)
    }

    @Test
    fun aPackagesScopedSkillIsListedAndReadWhereTheRuntimeLooks() = runBlocking {
        val text = "---\nname: com.example.notes:find\ndescription: Find a note.\n---\n\nRead them.\n"
        AppHost.install(additions = PluginAdditions(skills = listOf(PluginSkill("com.example.notes:find", "Find a note.", text))))
        val vfs = workspaceVfs(app, File(app.cacheDir, "plugin-skills"), loadRuntimeSkills(app))

        val listed = vfs.list("/.agents/skills") as VfsListResult.Entries
        assertTrue(listed.entries.any { it.name == "com.example.notes:find" && it.type == "dir" })
        assertEquals(text, (vfs.read("/.agents/skills/com.example.notes:find/SKILL.md", 0, 65536) as VfsReadResult.Found).text)
    }

    @Test
    fun pluginRulesFollowAGuardAndStayInTheirBudget() {
        assertTrue(pluginRuleLines(PluginAdditions.NONE).isEmpty())
        val rules = listOf("a".repeat(30), "b".repeat(30), "c".repeat(30))
        val lines = pluginRuleLines(PluginAdditions(rules = rules), budgetBytes = 70)
        assertEquals(3, lines.size)
        assertTrue("cannot widen what the user allowed" in lines.first())
        assertEquals(rules.take(2), lines.drop(1))
        // Skills alone bring the guard too.
        assertEquals(1, pluginRuleLines(PluginAdditions(skills = listOf(PluginSkill("x:y", "d", "t")))).size)
    }

    @Test
    fun aPluginServerTakesAFreeNameOfOneGroup() {
        val servers = listOf("trains_trains", "trains_trains", "app_ui", "Weird__Name-", "experimental_a2a_x", "")
            .map { PluginMcpServer(it, "label", "https://example.com/mcp") }
        assertEquals(
            listOf("trains_trains", "trains_trains3", "app_ui", "weird_name", "experimental_a2a_x", "plugin"),
            namedPluginServers(servers, taken = setOf("trains_trains2")).map { it.first },
        )
        // While a running plugin names a server as its own (the cards'), or every server starting so (A2A's hand-offs),
        // no plugin server takes that name or reads as one.
        val own = mapOf("app_ui" to OwnToolServer(mainAgentOnly = true), "experimental_a2a*" to OwnToolServer(mainAgentOnly = true))
        AppHost.install(additions = PluginAdditions(ownToolServers = own))
        try {
            val names = namedPluginServers(servers, taken = setOf("trains_trains2")).map { it.first }
            assertEquals(listOf("trains_trains", "trains_trains3", "app_ui2", "weird_name", "p_experimental_a2a_x", "plugin"), names)
        } finally {
            AppHost.install(additions = PluginAdditions.NONE)
        }
    }

    @Test
    fun aPluginToolThatActsWaitsForTheUser() = runBlocking {
        val gate = PluginToolGate(setOf("trains_trains")) { _, tool -> tool == "search" }
        val context = HookContext("s", "r", "q")
        suspend fun decide(tool: String, approved: Boolean = false) =
            gate.onPreToolUse(ToolUse(tool, "{}", ToolSource.BACKEND), context.copy(approvedConfirmation = approved))

        // Every time, whatever the mode: the runtime's alwaysAsk.
        assertTrue((decide("mcp__trains_trains__book") as HookDecision.RequireConfirmation).alwaysAsk)
        assertTrue(decide("mcp__trains_trains__book", approved = true) is HookDecision.Allow)
        assertTrue(decide("mcp__trains_trains__search") is HookDecision.Allow)
        assertTrue(decide("mcp__other__book") is HookDecision.Allow)
        assertTrue(decide("read_file") is HookDecision.Allow)
    }

    @Test
    fun theModeIsTheRuntimesPolicyWhateverThePluginsAsk() {
        assertEquals(ApprovalPolicy.ASK, ConfirmationMode.ASK_EVERY_TIME.approvalPolicy())
        assertEquals(ApprovalPolicy.REMEMBER, ConfirmationMode.ALLOW_CONFIRMED_DOMAINS.approvalPolicy())
        assertEquals(ApprovalPolicy.APPROVE_ALL, ConfirmationMode.ALLOW_ALL_SESSION.approvalPolicy())
        assertEquals(ApprovalPolicy.DENY_ALL, ConfirmationMode.DENY_ALL.approvalPolicy())
    }

    @Test
    fun aPluginsToolsThatAskAreNamedApartAndOthersKeepTheirIds() {
        val registrations = listOf(
            PluginTools(askBeforeActions = false) { _, _ -> listOf(Tools("cards")) },
            PluginTools(askBeforeActions = true) { _, mounted -> listOf(Tools("trains"), Tools("cards"), Tools("weather"), Tools("A2A_x")).also { assertEquals(1, mounted.size) } },
            PluginTools(askBeforeActions = true) { _, _ -> listOf(Tools("trains")) },
        )
        val gated = linkedMapOf<String, ToolProvider>()
        val providers = pluginToolProviders(registrations, app, listOf(Tools("weather")), setOf("weather"), gated)
        assertEquals(listOf("cards", "trains", "cards2", "weather2", "a2a_x", "trains2"), providers.map { it.providerId })
        assertEquals(providers.drop(1), gated.values.toList())
        assertEquals(listOf("trains", "cards2", "weather2", "a2a_x", "trains2"), gated.keys.toList())
    }

    @Test
    fun aGatedPluginToolRunsUnaskedOnlyWhenItsProviderSaysItOnlyReads() = runBlocking {
        val gate = checkNotNull(pluginToolGate(emptyList(), mapOf("trains" to Tools("trains"))))
        val context = HookContext("s", "r", "q")
        suspend fun decide(tool: String, approved: Boolean = false) =
            gate.onPreToolUse(ToolUse(tool, "{}", ToolSource.BACKEND), context.copy(approvedConfirmation = approved))

        assertTrue(decide("mcp__trains__fare") is HookDecision.Allow)
        assertTrue(decide("mcp__trains__book") is HookDecision.RequireConfirmation)
        assertTrue(decide("mcp__trains__book", approved = true) is HookDecision.Allow)
        assertTrue(decide("mcp__trains__unknown") is HookDecision.RequireConfirmation)
        assertTrue(decide("mcp__other__book") is HookDecision.Allow)
        assertEquals(null, pluginToolGate(emptyList(), emptyMap()))
    }

    /** A provider whose fare only reads and whose book acts. */
    private class Tools(override val providerId: String) : ToolProvider {
        override suspend fun listTools() = listOf(
            ToolDescriptor("fare", inputSchemaJson = "{}", effectKind = RuntimeEffectKind.READ_ONLY.name),
            ToolDescriptor("book", inputSchemaJson = "{}"),
        )

        override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext) = ToolCallResult(true, "{}")
    }
}

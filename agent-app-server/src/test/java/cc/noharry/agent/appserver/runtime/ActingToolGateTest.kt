@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.HookDecision
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.spi.ToolSource
import cc.noharry.agent.spi.ToolUse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's own rule on asking, call by call: a tool that does not say it only reads asks (an ordinary question,
 * which the approval mode answers), whoever brought it; the rest does not ask here.
 */
class ActingToolGateTest {
    private val drawer = Tools(
        "drawer",
        "open" to "MUTATING", "shut" to "UNKNOWN", "lock" to "CONTROL", "peek" to "READ_ONLY", "dust" to "MUTATING",
    )
    // As the runtime's MCP provider describes a configured server's tools: readOnlyHint is READ_ONLY, the rest UNKNOWN.
    private val home = Tools("home", "status" to "READ_ONLY", "set_light" to "UNKNOWN")
    private val own = listOf(Tools("app_ui", "show_card" to "UNKNOWN"), Tools("pages", "show_html" to "UNKNOWN"), Tools("notes", "note_manage" to "UNKNOWN"))
    private val broken = object : ToolProvider by Tools("broken") {
        override suspend fun listTools(): List<ToolDescriptor> = error("unreachable")
    }
    // Cards and drawn pages are plugins' tool servers, which they name as their own with what decides their calls: they never ask. A
    // plugin may decide by the call's arguments, as plans do (Bothy's agent-app-plans pins theirs).
    private val gate = ActingToolGate(
        listOf(drawer, home, broken) + own, unasked = setOf("mcp__drawer__dust", "edit_file"),
        pluginActs = { server, _, arguments ->
            when (server) {
                "app_ui", "pages" -> false
                "notes" -> "list" !in arguments
                else -> null
            }
        },
    )

    @Test
    fun aToolThatDoesNotSayItOnlyReadsAsksAnOrdinaryQuestion() = runBlocking {
        listOf("mcp__drawer__open", "mcp__drawer__shut", "mcp__drawer__lock", "mcp__home__set_light").forEach { name ->
            val asked = decide(name) as HookDecision.RequireConfirmation
            assertFalse(name, asked.alwaysAsk)
            assertEquals(ACTING_TOOL_REASON, asked.reason)
        }
        assertTrue(decide("mcp__drawer__peek") is HookDecision.Allow)
        assertTrue(decide("mcp__home__status") is HookDecision.Allow)
        // After the yes, the same call runs.
        assertTrue(decide("mcp__drawer__open", approved = true) is HookDecision.Allow)
        // A tool the run cannot describe counts as one that acts.
        assertTrue(decide("mcp__broken__anything") is HookDecision.RequireConfirmation)
        assertTrue(decide("mcp__nowhere__anything") is HookDecision.RequireConfirmation)
    }

    @Test
    fun writingTheWorkspaceAndScriptsAskReadingTheWebAndTheProtocolsToolsDoNot() = runBlocking {
        assertTrue(decide("write_file", source = ToolSource.NATIVE_WRITE) is HookDecision.RequireConfirmation)
        assertTrue(decide("exec_js", source = ToolSource.EXEC) is HookDecision.RequireConfirmation)
        listOf(
            "read_file" to ToolSource.NATIVE_READ, "spawn_agent" to ToolSource.NATIVE_READ, "web_fetch" to ToolSource.NETWORK,
            "web_search" to ToolSource.NETWORK, "update_todo" to ToolSource.NATIVE_TODO, "finish" to ToolSource.TERMINAL,
            "ask" to ToolSource.TERMINAL, "ask_async" to ToolSource.INTERACTION,
        ).forEach { (name, source) -> assertTrue(name, decide(name, source = source) is HookDecision.Allow) }
    }

    @Test
    fun theHostNamesToolsThatActButNeedNoAsking() = runBlocking {
        assertTrue(decide("mcp__drawer__dust") is HookDecision.Allow)
        assertTrue(decide("edit_file", source = ToolSource.NATIVE_WRITE) is HookDecision.Allow)
    }

    @Test
    fun theServersOwnToolsAreDecidedByWhatTheyDo() = runBlocking {
        assertTrue(decide("mcp__app_ui__show_card") is HookDecision.Allow)
        assertTrue(decide("mcp__pages__show_html") is HookDecision.Allow)
        assertTrue(decide("mcp__notes__note_manage", """{"action":"list"}""") is HookDecision.Allow)
        assertTrue(decide("mcp__notes__note_manage", """{"action":"delete"}""") is HookDecision.RequireConfirmation)
    }

    @Test
    fun aPluginsToolServersThatAskEveryTimeAreLeftToTheirGate() = runBlocking {
        val leaving = ActingToolGate(listOf(drawer), unasked = emptySet(), decidedElsewhere = setOf("drawer"))
        assertTrue(leaving.onPreToolUse(ToolUse("mcp__drawer__open", "{}", ToolSource.BACKEND), CONTEXT) is HookDecision.Allow)
    }

    @Test
    fun inTheAppsOwnTurnsAllowAllLetsACallRunWithoutThePauseOfAnApproval() = runBlocking {
        var mode = ConfirmationMode.ALLOW_ALL_SESSION
        val own = ActingToolGate(listOf(drawer), unasked = emptySet(), mode = { mode })
        val call = ToolUse("mcp__drawer__open", "{}", ToolSource.BACKEND)
        assertTrue(own.onPreToolUse(call, CONTEXT) is HookDecision.Allow)
        // The person changes the mode while the turn runs: the next call asks, and the turn's policy answers.
        mode = ConfirmationMode.ASK_EVERY_TIME
        assertTrue(own.onPreToolUse(call, CONTEXT) is HookDecision.RequireConfirmation)
        mode = ConfirmationMode.DENY_ALL
        assertTrue(own.onPreToolUse(call, CONTEXT) is HookDecision.RequireConfirmation)
    }

    @Test
    fun inAnotherAgentsTaskThePersonsModeAnswers() = runBlocking {
        fun outside(mode: ConfirmationMode) = ActingToolGate(listOf(drawer), unasked = emptySet(), outsideAnswer = mode)
        val call = ToolUse("mcp__drawer__open", "{}", ToolSource.BACKEND)
        assertTrue(outside(ConfirmationMode.ALLOW_ALL_SESSION).onPreToolUse(call, CONTEXT) is HookDecision.Allow)
        assertTrue(outside(ConfirmationMode.DENY_ALL).onPreToolUse(call, CONTEXT) is HookDecision.Deny)
        assertTrue(outside(ConfirmationMode.ASK_EVERY_TIME).onPreToolUse(call, CONTEXT) is HookDecision.RequireConfirmation)
        assertTrue(outside(ConfirmationMode.DENY_ALL).onPreToolUse(call.copy(toolName = "mcp__drawer__peek"), CONTEXT) is HookDecision.Allow)
    }

    private suspend fun decide(name: String, arguments: String = "{}", source: ToolSource = ToolSource.BACKEND, approved: Boolean = false) =
        gate.onPreToolUse(ToolUse(name, arguments, source), CONTEXT.copy(approvedConfirmation = approved))

    private class Tools(override val providerId: String, vararg val tools: Pair<String, String>) : ToolProvider {
        override suspend fun listTools() = tools.map { (name, effect) -> ToolDescriptor(name, name, "{}", effectKind = effect) }

        override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext) = ToolCallResult(true, "{}")
    }

    private companion object {
        val CONTEXT = HookContext("s", "r", "q")
    }
}

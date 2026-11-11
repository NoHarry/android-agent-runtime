package cc.noharry.agent.appserver.server

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.PackageProblem
import cc.noharry.agent.appserver.protocol.PackageRefusal
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plugins start, stop and are switched while the server runs, and what one added goes with it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PluginHostTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val switches = MemorySwitches()
    private val log = mutableListOf<String>()

    @Test
    fun aPluginRunsAfterWhatItNeedsAndStopsBeforeIt() {
        val host = host(plugin("search", needs = setOf("account")), plugin("account"))
        host.startAll()
        assertEquals(listOf("start account", "start search"), log)

        host.setEnabled("account", false)
        assertEquals(listOf("stop search", "stop account"), log.drop(2))
        assertEquals(listOf(false, false), host.views().map { it.running })
        // Still on, it waits for what it needs and comes back with it.
        assertTrue(host.views().single { it.id == "search" }.enabled)
        host.setEnabled("account", true)
        assertEquals(listOf("start account", "start search"), log.drop(4))
    }

    @Test
    fun whatAPluginAddedGoesWhenItStops() {
        val feed = MutableStateFlow("")
        val state = RecordingState()
        lateinit var context: PluginContext
        val cards = object : AppServerPlugin {
            override val id = "cards"
            override fun apply(ctx: PluginContext) {
                context = ctx
                ctx.offer("cards")
                ctx.tools { _, _ -> emptyList() }
                ctx.wrapTools { it }
                ctx.routes { on(PING) { Empty } }
                ctx.publish {
                    follow(feed) { copy(runtimeStatus = it) }
                    ctx.onStop { update { copy(runtimeStatus = "gone") } }
                }
                ctx.provide(String::class.java, "a service")
            }
        }
        val reader = object : AppServerPlugin {
            override val id = "reader"
            override val needs = setOf("cards")
            override fun apply(ctx: PluginContext) { log += "sees ${ctx.service(String::class.java)}" }
        }
        val host = host(cards, reader)
        val changes = mutableListOf<Set<String>>()
        host.onChange = { changes += it.offers }
        host.startAll()
        host.publishInto(state)

        assertEquals(setOf("cards"), host.additions.offers)
        assertEquals(listOf(1, 1), host.additions.run { listOf(tools.size, toolWrappers.size) })
        assertTrue(PING.name in host.additions.routes)
        assertEquals(listOf("sees a service"), log)
        feed.value = "shown"
        assertEquals("shown", state.current.runtimeStatus)

        host.setEnabled("cards", false)
        assertEquals(PluginAdditions.NONE.offers, host.additions.offers)
        assertTrue(host.additions.run { tools.isEmpty() && toolWrappers.isEmpty() && routes.isEmpty() })
        assertFalse(context.scope.isActive)
        assertEquals("gone", state.current.runtimeStatus)
        // What it followed no longer writes.
        feed.value = "late"
        assertEquals("gone", state.current.runtimeStatus)
        assertEquals(listOf(setOf("cards"), emptySet()), changes)
    }

    @Test
    fun theChoiceIsKeptAndARequiredPluginStaysOn() {
        val plugins = arrayOf(plugin("voice", required = true), plugin("checks"))
        host(*plugins).apply { startAll() }.setEnabled("checks", false)
        log.clear()

        // The next process reads what was chosen.
        val next = host(*plugins)
        next.startAll()
        assertEquals(listOf("start voice"), log)
        assertEquals(listOf(true, false), next.views().map { it.enabled })
        assertThrows(IllegalArgumentException::class.java) { next.setEnabled("voice", false) }
        assertThrows(IllegalArgumentException::class.java) { next.setEnabled("nobody", true) }
        assertThrows(IllegalArgumentException::class.java) { host(plugin("a"), plugin("a")) }
        assertThrows(IllegalArgumentException::class.java) { host(plugin("a", needs = setOf("missing"))) }
    }

    @Test
    fun aPluginThatFailsToStartLeavesNothingBehind() {
        val broken = object : AppServerPlugin {
            override val id = "broken"
            override fun apply(ctx: PluginContext) {
                ctx.offer("half")
                ctx.onStop { log += "cleaned" }
                error("no microphone")
            }
        }
        val host = host(broken)
        assertThrows(IllegalStateException::class.java) { host.startAll() }
        assertEquals(listOf("cleaned"), log)
        assertFalse(host.views().single().running)
        assertNull(host.additions.offers.firstOrNull())
    }

    @Test
    fun aPluginAddedWhileTheServerRunsStartsAndRemovedGoes() {
        val host = host(plugin("account"))
        host.startAll()
        host.add(plugin("search", needs = setOf("account")))
        assertEquals(listOf("start account", "start search"), log)
        assertEquals(listOf("account", "search"), host.views().map { it.id })

        // What another plugin needs is removed after it; a required one never.
        assertThrows(IllegalArgumentException::class.java) { host.remove("account") }
        assertThrows(IllegalArgumentException::class.java) { host.add(plugin("search")) }
        assertThrows(IllegalArgumentException::class.java) { host.add(plugin("orphan", needs = setOf("missing"))) }
        host.setEnabled("search", false)
        host.remove("search")
        assertEquals(listOf("account"), host.views().map { it.id })
        // Its switch went with it: added again, it starts on.
        host.add(plugin("search", needs = setOf("account")))
        assertTrue(host.views().single { it.id == "search" }.running)
        val required = host(plugin("voice", required = true)).apply { startAll() }
        assertThrows(IllegalArgumentException::class.java) { required.remove("voice") }
    }

    @Test
    fun aPluginThatFailsToStartIsNotTriedAgainUntilTurnedOn() {
        var attempts = 0
        val flaky = object : AppServerPlugin {
            override val id = "flaky"
            override fun apply(ctx: PluginContext) {
                attempts++
                if (attempts == 1) error("damaged")
                ctx.rules(listOf("ready"))
            }
        }
        val host = host(plugin("other"))
        host.startAll()
        assertThrows(IllegalStateException::class.java) { host.add(flaky) }
        assertFalse(host.views().single { it.id == "flaky" }.running)
        // Another plugin's switch does not try it again.
        host.setEnabled("other", false)
        assertEquals(1, attempts)
        host.setEnabled("flaky", true)
        assertTrue(host.views().single { it.id == "flaky" }.running)
        assertEquals(listOf("ready"), host.additions.rules)
    }

    @Test
    fun skillsRulesAndMcpServersComeAndGoWithThePlugin() {
        val skill = PluginSkill("notes:find", "Find a note.", "---\nname: find\ndescription: Find a note.\n---\n\nRead them.\n")
        val server = PluginMcpServer("notes", "Notes", "https://notes.example.com/mcp", askBeforeActions = true)
        val notes = object : AppServerPlugin {
            override val id = "notes"
            override fun apply(ctx: PluginContext) {
                ctx.skills(listOf(skill))
                ctx.rules(listOf("Read the notes first."))
                ctx.mcpServers(listOf(server))
            }
        }
        val twin = object : AppServerPlugin {
            override val id = "twin"
            override fun apply(ctx: PluginContext) = ctx.skills(listOf(PluginSkill("notes:find", "Another.", "x")))
        }
        val host = host(notes, twin)
        host.startAll()

        assertEquals(listOf(skill), host.additions.skills)
        assertEquals(listOf("Read the notes first."), host.additions.rules)
        assertEquals(listOf(server), host.additions.mcpServers)
        assertTrue(host.additions.mcpServers.single().askBeforeActions)
        host.setEnabled("notes", false)
        assertEquals(listOf("Another."), host.additions.skills.map { it.description })
        assertTrue(host.additions.rules.isEmpty() && host.additions.mcpServers.isEmpty())
    }

    @Test
    fun toolsAndToolServersComeBackWhenThePluginRunsAgain() {
        val server = PluginToolServer("shelf", "Shelf", { "Shelf" }, onAtFirst = true) { _, _ -> error("not built here") }
        val mcp = PluginMcpServer("notes", "Notes", "https://notes.example.com/mcp")
        val shelf = object : AppServerPlugin {
            override val id = "shelf"
            override fun apply(ctx: PluginContext) {
                ctx.tools { _, _ -> emptyList() }
                ctx.toolServers(listOf(server))
                ctx.mcpServers(listOf(mcp))
            }
        }
        val host = host(shelf)
        host.startAll()
        host.setEnabled("shelf", false)
        assertTrue(host.additions.tools.isEmpty() && host.additions.toolServers.isEmpty() && host.additions.mcpServers.isEmpty())
        // Turned on again, it starts afresh and every run mounts what it gives as before.
        host.setEnabled("shelf", true)
        assertEquals(1, host.additions.tools.size)
        assertEquals(listOf(server), host.additions.toolServers)
        assertEquals(listOf(mcp), host.additions.mcpServers)
    }

    @Test
    fun aLibrarysPluginThatNeedsWhatTheAppLacksIsLeftOutAndShownWhileTheHostsOwnFailsFast() {
        val host = PluginHost(
            app, listOf(plugin("account")), switches, Dispatchers.Unconfined,
            discovered = listOf(plugin("search", needs = setOf("index")), plugin("cards", needs = setOf("search")), plugin("maps", needs = setOf("account"))),
        )
        host.startAll()
        assertEquals(listOf("start account", "start maps"), log)
        val leftOut = host.views().filter { it.problem != null }.associate { it.id to it.problem }
        assertEquals(PackageRefusal(PackageProblem.NEEDS_MISSING, "index"), leftOut["search"])
        assertEquals(PackageRefusal(PackageProblem.NEEDS_MISSING, "search"), leftOut["cards"])
        assertFalse(host.views().single { it.id == "search" }.running)
        assertThrows(IllegalArgumentException::class.java) { host.setEnabled("search", true) }
        assertThrows(IllegalArgumentException::class.java) { host(plugin("search", needs = setOf("index"))) }
    }

    @Test
    fun aLibrarysPluginThatFailsToStartStaysOffWithWhyWhileTheHostsOwnThrows() {
        val broken = object : AppServerPlugin {
            override val id = "broken"
            override fun apply(ctx: PluginContext) = error("its data file is missing")
        }
        val host = PluginHost(app, listOf(plugin("account")), switches, Dispatchers.Unconfined, discovered = listOf(broken))
        host.startAll()
        val view = host.views().single { it.id == "broken" }
        assertFalse(view.running)
        assertEquals(PackageRefusal(PackageProblem.START_FAILED, "its data file is missing"), view.problem)
        assertEquals(listOf("start account"), log)
        assertThrows(IllegalStateException::class.java) { host(broken).startAll() }
    }

    private fun host(vararg plugins: AppServerPlugin) = PluginHost(app, plugins.toList(), switches, Dispatchers.Unconfined)

    private fun plugin(name: String, needs: Set<String> = emptySet(), required: Boolean = false) = object : AppServerPlugin {
        override val id = name
        override val needs = needs
        override val required = required
        override fun apply(ctx: PluginContext) {
            log += "start $name"
            ctx.onStop { log += "stop $name" }
        }
    }

    private class MemorySwitches : PluginSwitches {
        private val off = mutableSetOf<String>()
        override fun isOn(id: String) = id !in off
        override fun set(id: String, on: Boolean) { if (on) off -= id else off += id }
    }

    private class RecordingState : PublishedState {
        override var current = ServerState()
        override fun update(change: ServerState.() -> ServerState) { current = current.change() }
        override fun <T> follow(flow: Flow<T>, into: ServerState.(T) -> ServerState) = error("a plugin follows through its own scope")
        override fun broadcast(notification: JsonRpcNotification) = Unit
    }

    private companion object {
        val PING = RequestMethod("test/ping", Empty.serializer(), Empty.serializer())
    }
}

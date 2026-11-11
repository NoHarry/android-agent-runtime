package cc.noharry.agent.appserver.sample.timer

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.PluginInstallParams
import java.time.Duration
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

// guide:start test
/**
 * Pip's turns, offline: the app's own [TimerApplication] installs the server with its script, and each turn goes in
 * and comes out through the protocol, as the broadcast's do on a device; a plugin package it installs the same way,
 * whose tool is a script. The methods share one server (one per process), each with what no other uses.
 */
@RunWith(RobolectricTestRunner::class)
class TimerAgentTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun convertsCupsWithThePluginPackageItInstalls() {
        // Before the package, the script's search does not find its tool.
        assertTrue(ask("how many ml in 2 cups").contains("mcp__measures__cups_to_ml is not among the tools search_tools found"))
        // guide:start install-test
        val bytes = checkNotNull(javaClass.getResourceAsStream("/com.example.kitchen.measures-1.0.0.agentplugin")).use { it.readBytes() }
        val source = PluginInstallParams(data = Base64.getEncoder().encodeToString(bytes))
        assertEquals("installed com.example.kitchen.measures 1.0.0", onMain { TimerAgent.install(app, source) })
        // Its script runs in QuickJS: 2 × 236.588 ml, read right under the numeric locale the build file gives the test.
        assertEquals("That is 473 ml.", ask("how many ml in 2 cups"))
        // guide:end install-test
        onMain { TimerAgent.remove(app, "com.example.kitchen.measures") }
    }

    @Test
    fun startsListsAndCancelsTimers() {
        assertEquals("Started the egg timer for 300 seconds.", ask("start a 5 minute egg timer"))
        assertTrue(ask("list my timers").startsWith("Running: egg ("))
        assertEquals("Cancelled the egg timer.", ask("cancel the egg timer"))
        assertEquals("No timers are running.", ask("list my timers"))
        assertEquals("That didn't work: timers longer than 4 hours are not allowed.", ask("start a 6 hour brisket timer"))
        assertEquals("I'm Pip, the kitchen timer. Ask me to start, list or cancel a timer.", ask("hello"))
    }

    /** One turn. */
    private fun ask(text: String): String = onMain { TimerAgent.ask(app, text) }

    /** [block] on the main thread, which the test is: it turns the main looper while the server works. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> onMain(block: suspend () -> T): T {
        val running = CoroutineScope(Dispatchers.Main).async { block() }
        val until = System.currentTimeMillis() + TURN_MS
        while (!running.isCompleted && System.currentTimeMillis() < until) {
            // Its clock moves only when told: a delay on the main thread waits for this.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(IDLE_MS))
            Thread.sleep(IDLE_MS)
        }
        return running.getCompleted()
    }

    private companion object {
        const val TURN_MS = 20_000L
        const val IDLE_MS = 5L
    }
}
// guide:end test

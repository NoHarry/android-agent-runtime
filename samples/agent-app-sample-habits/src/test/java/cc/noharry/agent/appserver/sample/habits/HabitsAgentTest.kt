package cc.noharry.agent.appserver.sample.habits

// guide:start test
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Tally's turns, offline: the app's own [HabitsApplication] installs the server with its script, and each turn goes
 * in and comes out through the protocol, as the service's do on a device.
 *
 * The methods share one server, started by the first to run: Robolectric gives each method a new Application, which
 * installs the config again, but the server is one per process and keeps working with the first Application, its
 * files and conversations (the current one too), and JUnit picks the order. So each method starts a conversation of
 * its own, turns the plugin on and uses habits no other method does.
 */
@RunWith(RobolectricTestRunner::class)
class HabitsAgentTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun aConversationOfItsOwn() = onMain {
        HabitsAgent.newConversation(app)
        HabitsAgent.setPlugin(app, HabitListPlugin.id, true)
    }

    @Test
    fun loggingTwiceOnOneDayCountsOneDay() {
        assertEquals("Logged running; streak 1.", ask("log running"))
        assertEquals("Logged running; streak 1.", ask("log running"))
        assertEquals("Streak for running: 1.", ask("streak for running"))
    }

    @Test
    fun aResetNobodyAllowedChangesNothing() {
        ask("log swimming")
        // HabitsAgent refuses the approval; the model is told CONFIRMATION_DENIED and the tool does not run.
        assertEquals("Not done: forgetting a habit was not allowed.", ask("reset swimming"))
        assertEquals("Streak for swimming: 1.", ask("streak for swimming"))
        assertEquals("Reset swimming.", ask("reset swimming", allowReset = true))
        assertEquals("Streak for swimming: 0.", ask("streak for swimming"))
    }

    @Test
    fun aPluginsToolWorksAgainInTheConversationThatCalledItWhileItWasOff() {
        ask("log reading")
        assertTrue(ask("list my habits").contains("reading"))
        onMain { HabitsAgent.setPlugin(app, HabitListPlugin.id, false) }
        // The conversation found the tool before: the call is refused as TOOL_UNAVAILABLE, its server being off.
        assertEquals("Not done: that is turned off right now.", ask("list my habits"))
        onMain { HabitsAgent.setPlugin(app, HabitListPlugin.id, true) }
        assertTrue(ask("list my habits").contains("reading"))
    }

    @Test
    fun aConversationThatNeverFoundThePluginsToolFindsItOnceThePluginIsOn() {
        // Off from the start: no search of this conversation shows the plugin's tool.
        onMain { HabitsAgent.setPlugin(app, HabitListPlugin.id, false) }
        ask("log cycling")
        // Its search does not find the tool: the script ends the turn saying so.
        assertTrue(ask("list my habits").contains("mcp__habit_list__list_habits is not among the tools search_tools found"))
        onMain { HabitsAgent.setPlugin(app, HabitListPlugin.id, true) }
        assertTrue(ask("list my habits").contains("cycling"))
    }

    /** One turn. */
    private fun ask(text: String, allowReset: Boolean = false): String = onMain { HabitsAgent.ask(app, text, allowReset) }

    /** [block] on the main thread, which the test is: the main looper is turned until it is done. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> onMain(block: suspend () -> T): T {
        val running = CoroutineScope(Dispatchers.Main).async { block() }
        val until = System.currentTimeMillis() + WAIT_MS
        while (!running.isCompleted && System.currentTimeMillis() < until) {
            // Its clock moves only when told: a delay on the main thread waits for this.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(IDLE_MS))
            Thread.sleep(IDLE_MS)
        }
        return running.getCompleted()
    }

    private companion object {
        const val WAIT_MS = 20_000L
        const val IDLE_MS = 5L
    }
}
// guide:end test

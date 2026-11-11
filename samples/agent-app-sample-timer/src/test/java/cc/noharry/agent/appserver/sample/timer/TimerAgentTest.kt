package cc.noharry.agent.appserver.sample.timer

import android.content.Context
import android.os.Looper
import java.time.Duration
import androidx.test.core.app.ApplicationProvider
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
 * and comes out through the protocol, as the broadcast's do on a device.
 */
@RunWith(RobolectricTestRunner::class)
class TimerAgentTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun startsListsAndCancelsTimers() {
        assertEquals("Started the egg timer for 300 seconds.", ask("start a 5 minute egg timer"))
        assertTrue(ask("list my timers").startsWith("Running: egg ("))
        assertEquals("Cancelled the egg timer.", ask("cancel the egg timer"))
        assertEquals("No timers are running.", ask("list my timers"))
        assertEquals("That didn't work: timers longer than 4 hours are not allowed.", ask("start a 6 hour brisket timer"))
        assertEquals("I'm Pip, the kitchen timer. Ask me to start, list or cancel a timer.", ask("hello"))
    }

    /** One turn; the test runs on the main thread, so it turns the main looper while the server works. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun ask(text: String): String {
        val answer = CoroutineScope(Dispatchers.Main).async { TimerAgent.ask(app, text) }
        val until = System.currentTimeMillis() + TURN_MS
        while (!answer.isCompleted && System.currentTimeMillis() < until) {
            // Its clock moves only when told: a delay on the main thread waits for this.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(IDLE_MS))
            Thread.sleep(IDLE_MS)
        }
        return answer.getCompleted()
    }

    private companion object {
        const val TURN_MS = 20_000L
        const val IDLE_MS = 5L
    }
}
// guide:end test

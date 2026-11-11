package cc.noharry.agent.appserver.console.ui
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiObserverDispatcherTest {
    @Test
    fun dispatchQueuesUiWorkWithoutBlockingCallerAndPreservesOrder() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondHandled = CountDownLatch(1)
        val handled = CopyOnWriteArrayList<String>()
        val dispatcher = UiObserverDispatcher(scope) {}

        try {
            dispatcher.dispatch {
                firstStarted.countDown()
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                handled += "first"
            }
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

            dispatcher.dispatch {
                handled += "second"
                secondHandled.countDown()
            }

            assertFalse(secondHandled.await(100, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()

            assertTrue(secondHandled.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("first", "second"), handled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun dispatchNotifyingCoalescesConsecutiveRefreshes() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val notifyCount = AtomicInteger(0)
        val notified = CountDownLatch(1)
        val handled = CopyOnWriteArrayList<String>()
        val dispatcher = UiObserverDispatcher(scope) {
            notifyCount.incrementAndGet()
            notified.countDown()
        }

        try {
            dispatcher.dispatchNotifying { handled += "a" }
            dispatcher.dispatchNotifying { handled += "b" }

            assertTrue(notified.await(2, TimeUnit.SECONDS))
            Thread.sleep(100)

            assertEquals(listOf("a", "b"), handled)
            assertEquals(1, notifyCount.get())
        } finally {
            scope.cancel()
        }
    }
}

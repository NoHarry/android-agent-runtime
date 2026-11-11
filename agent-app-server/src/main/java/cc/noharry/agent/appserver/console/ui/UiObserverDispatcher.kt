package cc.noharry.agent.appserver.console.ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class UiObserverDispatcher(
    private val scope: CoroutineScope,
    private val notifyChanged: () -> Unit,
) {
    private val actions = Channel<() -> Unit>(Channel.UNLIMITED)
    private val notifyPending = AtomicBoolean(false)
    private val pendingUiActions = AtomicInteger(0)

    init {
        scope.launch {
            for (action in actions) {
                yield()
                action()
            }
        }
    }

    fun dispatch(action: () -> Unit) {
        pendingUiActions.incrementAndGet()
        val result = actions.trySend {
            try {
                action()
            } finally {
                pendingUiActions.decrementAndGet()
            }
        }
        if (result.isFailure) pendingUiActions.decrementAndGet()
    }

    fun dispatchNotifying(action: () -> Unit) {
        dispatch {
            action()
            requestNotify()
        }
    }

    fun requestNotify() {
        if (!notifyPending.compareAndSet(false, true)) return
        scope.launch {
            delay(NOTIFY_DEBOUNCE_MILLIS)
            while (pendingUiActions.get() > 0) yield()
            notifyPending.set(false)
            notifyChanged()
        }
    }
}

private const val NOTIFY_DEBOUNCE_MILLIS = 16L

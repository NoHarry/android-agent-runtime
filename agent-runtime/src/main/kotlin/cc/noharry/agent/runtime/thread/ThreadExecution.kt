package cc.noharry.agent.runtime.thread

import cc.noharry.agent.TurnError
import cc.noharry.agent.ThreadItem
import cc.noharry.agent.ThreadOptions
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Shared by handles and runtime instances that use one durable storage root. */
internal class ThreadCoordination {
    val lock = Any()
    private val slots = mutableMapOf<String, ThreadExecutionSlot>()
    val admission = ThreadAdmission(this)
    val stores: MutableSet<ThreadStore> = Collections.newSetFromMap(WeakHashMap<ThreadStore, Boolean>())
    fun slot(id: String): ThreadExecutionSlot = synchronized(lock) { slots.getOrPut(id) { ThreadExecutionSlot() } }
}

internal class ThreadExecutionSlot {
    val deletionMutex = Mutex()
    @Volatile var generation = 0L
    @Volatile var deleting = false
    @Volatile var active: ActiveThreadTurn? = null
}

internal class ActiveThreadTurn(val id: String, val options: ThreadOptions, val owner: DefaultThreadRuntime, val generation: Long) {
    var job: Job? = null
    var projection: ThreadEventProjection? = null
    @Volatile var approval: ThreadApproval? = null
    @Volatile var items: List<ThreadItem> = emptyList()
    @Volatile var finalizing = false
    @Volatile var cancellation: TurnError? = null
        private set

    fun cancel(error: TurnError = TurnError("INTERRUPTED", "The turn was interrupted.")) {
        synchronized(this) {
            if (cancellation == null || error.code == "THREAD_DELETED") cancellation = error
        }
        approval?.cancel()
        job?.cancel()
    }
}

/** Preserved across hook dispatch and timeout scopes to reject deletion that would await its own admission ticket. */
internal class ThreadExecutionContext(
    private val coordination: ThreadCoordination,
    private val threadId: String,
    private val turn: ActiveThreadTurn,
) : AbstractCoroutineContextElement(Key) {
    fun ownsActiveTurn(target: ThreadCoordination, targetThreadId: String): Boolean =
        coordination === target && threadId == targetThreadId && target.slot(targetThreadId).active === turn

    companion object Key : CoroutineContext.Key<ThreadExecutionContext>
}

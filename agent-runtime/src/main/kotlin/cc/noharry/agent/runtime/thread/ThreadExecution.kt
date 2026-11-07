package cc.noharry.agent.runtime.thread

import cc.noharry.agent.TurnError
import cc.noharry.agent.ThreadItem
import cc.noharry.agent.ThreadOptions
import cc.noharry.agent.spi.PostUserInputRequest
import cc.noharry.agent.spi.PostUserInputResult
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val steeringMutex = Mutex()
    private val receivedInputs = linkedMapOf<String, PostUserInputRequest>()
    val executionId: String = java.util.UUID.randomUUID().toString()
    val approvalState = cc.noharry.agent.spi.ApprovalPolicyState(options.approvalPolicy ?: cc.noharry.agent.ApprovalPolicy.ASK)
    var job: Job? = null
    var projection: ThreadEventProjection? = null
    @Volatile var approval: ThreadApproval? = null
    /** Web hosts the person approved in this turn; see [automaticApproval]. */
    val approvedThisTurn: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile var items: List<ThreadItem> = emptyList()
    @Volatile var finalizing = false
    @Volatile var cancellation: TurnError? = null
        private set

    suspend fun postInput(request: PostUserInputRequest): PostUserInputResult = steeringMutex.withLock {
        if (finalizing) return@withLock PostUserInputResult.RunNotActive
        owner.engine.postUserInput(request).also { result ->
            if (result == PostUserInputResult.Accepted || result == PostUserInputResult.Duplicate) {
                receivedInputs.getOrPut(request.requestId) { request }
            }
        }
    }

    /** Finalization waits for accepted posts to record their receipts before publishing its terminal snapshot. */
    suspend fun acceptedInputs(): List<PostUserInputRequest> = steeringMutex.withLock { receivedInputs.values.toList() }

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

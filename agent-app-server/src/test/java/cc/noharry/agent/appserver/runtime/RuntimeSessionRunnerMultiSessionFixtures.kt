@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.ToolSource
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest
import cc.noharry.agent.spi.SessionEventLogDurability
import cc.noharry.agent.spi.SessionEventLogHealth
import cc.noharry.agent.spi.SessionEventLogHealthStatus
import cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.spi.reliability.RuntimeCompletionSummary
import cc.noharry.agent.spi.reliability.RuntimeResult
import cc.noharry.agent.spi.reliability.RuntimeRunSnapshot
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

internal class BarrierRuntimeSignals(
    val release: CompletableDeferred<Unit> = CompletableDeferred(),
    val entered: CountDownLatch = CountDownLatch(1),
    val finished: CountDownLatch = CountDownLatch(1),
    val postedSteering: CopyOnWriteArrayList<PostedSteering> = CopyOnWriteArrayList(),
    val postedConfirmations: CopyOnWriteArrayList<PostedConfirmation> = CopyOnWriteArrayList(),
    val steeringPosted: CountDownLatch = CountDownLatch(1),
    val confirmationPosted: CountDownLatch = CountDownLatch(1),
    val emitToken: Boolean = false,
    val emitTodo: Boolean = false,
    val waitForConfirmation: Boolean = false,
    val ignoreCancel: Boolean = false,
    /** Invoked from inside the runner's finalization work; see the barrier runtime. */
    val onFinalizing: (() -> Unit)? = null,
)

internal class RuntimeMultiSessionFixture(
    val sessionId: String,
    val runId: String,
    emitToken: Boolean = false,
    emitTodo: Boolean = false,
    waitForConfirmation: Boolean = false,
    ignoreCancel: Boolean = false,
    onFinalizing: (() -> Unit)? = null,
) {
    private val signals = BarrierRuntimeSignals(
        emitToken = emitToken,
        emitTodo = emitTodo,
        waitForConfirmation = waitForConfirmation,
        ignoreCancel = ignoreCancel,
        onFinalizing = onFinalizing,
    )
    val release = signals.release
    val entered = signals.entered
    val finished = signals.finished
    val steeringPosted = signals.steeringPosted
    val confirmationPosted = signals.confirmationPosted
    val runtimeCloses = AtomicInteger(0)
    val memoryCloses = AtomicInteger(0)
    val postedSteering = signals.postedSteering
    val postedConfirmations = signals.postedConfirmations
    val request = RuntimeTurnRequest(
        requestId = runId,
        sessionId = sessionId,
        input = "hello $sessionId",
        scene = "debug-console",
    )
    val runtime = RuntimeMultiSessionBarrierRuntime(request, signals)

    fun resources(): ActiveRunResources =
        ActiveRunResources(
            { runtimeCloses.incrementAndGet() },
            { memoryCloses.incrementAndGet() },
        )
}

internal fun completedRuntimeResult(runId: String): RuntimeResult =
    RuntimeResult(
        runId = runId,
        status = RuntimeRunStatus.COMPLETED,
        response = "done",
        completion = RuntimeCompletionSummary(outcome = RuntimeCompletionOutcome.SUCCEEDED),
    )

internal const val DEBUG_TEST_TODO_RESULT_JSON =
    """{"ok":true,"todoCount":2,"todos":[{"content":"导航到加油站","status":"in_progress"},{"content":"播报到达","status":"pending"}]}"""

internal data class PostedSteering(val runId: String, val text: String)
internal data class PostedConfirmation(val pendingId: String, val approved: Boolean)

internal fun multiSessionStartEvents(request: RuntimeTurnRequest, signals: BarrierRuntimeSignals, approval: Approval): List<ThreadEvent> {
    val events = mutableListOf<ThreadEvent>(ThreadEvent.TurnStarted(request.sessionId, request.requestId))
    if (signals.emitToken) events += ThreadEvent.UsageUpdated(request.requestId, Usage(inputTokens = 11, outputTokens = 7))
    if (signals.emitTodo) {
        val item = ThreadItem.ToolCall("todo-1", request.requestId, DEBUG_UPDATE_TODO_TOOL_NAME, ToolSource.NATIVE_TODO)
        events += ThreadEvent.ItemStarted(item)
        events += ThreadEvent.ItemCompleted(item.copy(output = DEBUG_TEST_TODO_RESULT_JSON, status = ItemStatus.COMPLETED))
    }
    if (signals.waitForConfirmation) events += ThreadEvent.ItemStarted(approval)
    return events
}

internal fun multiSessionBarrierFlow(
    request: RuntimeTurnRequest,
    signals: BarrierRuntimeSignals,
    waiting: AtomicBoolean,
    terminal: AtomicReference<RuntimeResult?>,
    approval: Approval,
): Flow<ThreadEvent> = flow {
    signals.entered.countDown()
    multiSessionStartEvents(request, signals, approval).forEach { emit(it) }
    if (signals.ignoreCancel) withContext(NonCancellable) { signals.release.await() } else signals.release.await()
    waiting.set(false)
    if (signals.waitForConfirmation) emit(ThreadEvent.ItemCompleted(approval))
    val result = terminal.get() ?: completedRuntimeResult(request.requestId)
    emit(ThreadEvent.TurnCompleted(TurnResult(result.runId, TurnStatus.COMPLETED, result.response, emptyList())))
    signals.finished.countDown()
}

internal fun barrierRunState(
    runId: String,
    request: RuntimeTurnRequest,
    terminal: RuntimeResult?,
    released: Boolean,
    waiting: Boolean,
): RuntimeRunSnapshot =
    RuntimeRunSnapshot(
        runId = runId,
        sessionId = request.sessionId,
        status = when {
            terminal != null || released -> RuntimeRunStatus.COMPLETED
            waiting -> RuntimeRunStatus.WAITING_CONFIRMATION
            else -> RuntimeRunStatus.RUNNING
        },
        updatedAtEpochMillis = 1L,
        pendingConfirmationId = "pending-confirm".takeIf { waiting },
    )

internal fun completeSessionEventLogHealth(): SessionEventLogHealth =
    SessionEventLogHealth(
        status = SessionEventLogHealthStatus.COMPLETE,
        attemptedCount = 1,
        appendedCount = 1,
        backendDurability = SessionEventLogDurability.DURABLE,
    )

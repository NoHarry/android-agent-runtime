package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.evidence.DiagnosticWrites
import cc.noharry.agent.appserver.evidence.RunEvidenceRecorder
import cc.noharry.agent.appserver.evidence.RuntimeEventDispatchEvidence
import cc.noharry.agent.appserver.mcp.McpProgressiveEvidenceCollector
import cc.noharry.agent.appserver.mcp.McpProgressiveEvidenceRecorder
import cc.noharry.agent.appserver.session.SessionTurnRecorder
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.TurnInput
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest
import cc.noharry.agent.AgentRuntime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal fun CoroutineScope.launchRuntime(
    runtime: AgentRuntime,
    request: RuntimeTurnRequest,
    evidenceRecorder: McpProgressiveEvidenceRecorder?,
    runEvidenceRecorder: RunEvidenceRecorder,
    turnRecorder: SessionTurnRecorder,
    onEvent: (ThreadEvent) -> Unit,
    onStopped: () -> Unit,
    onFailure: (String?) -> Unit,
    onFinally: suspend (RunEvidenceRecorder, SessionTurnRecorder) -> Unit,
    onCompleted: (Job) -> Unit,
): Job {
    val job = launch {
        collectRuntime(
            runtime, request, evidenceRecorder, runEvidenceRecorder, turnRecorder,
            onEvent, onStopped, onFailure, onFinally,
        )
    }
    job.invokeOnCompletion { launch { onCompleted(job) } }
    return job
}

internal suspend fun collectRuntime(
    runtime: AgentRuntime,
    request: RuntimeTurnRequest,
    evidenceRecorder: McpProgressiveEvidenceRecorder?,
    runEvidenceRecorder: RunEvidenceRecorder,
    turnRecorder: SessionTurnRecorder,
    onEvent: (ThreadEvent) -> Unit,
    onStopped: () -> Unit,
    onFailure: (String?) -> Unit,
    onFinally: suspend (RunEvidenceRecorder, SessionTurnRecorder) -> Unit,
) = coroutineScope {
    val eventProbe = RuntimeEventDispatchProbe()
    val eventDispatcher = RuntimeEventDispatcher(this, onEvent, eventProbe)
    val observers = ExecutionObservers(this, request.requestId, eventDispatcher::dispatch) {
        recordExecutionMessage(it, evidenceRecorder, runEvidenceRecorder, turnRecorder)
    }
    var drainUiEvents = true
    try {
        withContext(Dispatchers.IO) {
            val speech = RuntimeVoice.port.textAnnouncer(this) { text, final ->
                ExecutionEvents.publish(request.requestId, ExecutionMessage.Speech(text, final))
            }
            request.thread(runtime).runStreamed(TurnInput.text(request.input), request.options()).collect { sdkEvent ->
                val event = runtime.hostEvent(sdkEvent, request.sessionId)
                ExecutionEvents.publish(request.requestId, ExecutionMessage.Runtime(event))
                speech?.observe(RuntimeRunScope(request.requestId, request.sessionId), event) { true }
            }
        }
    } catch (error: CancellationException) {
        drainUiEvents = false
        ExecutionEvents.publish(request.requestId, ExecutionMessage.Stopped)
        onStopped()
    } catch (error: Exception) {
        ExecutionEvents.publish(request.requestId, ExecutionMessage.Failure(error.message ?: "Runtime failed"))
        onFailure(error.message)
    } finally {
        withContext(NonCancellable) {
            val observerFailure = observers.closeAndJoin(drainUiEvents)
            try { DiagnosticWrites.flush(request.requestId + ".log") } catch (error: Exception) {
                ExecutionEvents.publish(request.requestId, ExecutionMessage.Failure("Diagnostic log write failed"))
                onFailure(error.message)
            }
            val dispatchFailure = eventDispatcher.closeAndJoin(drainUiEvents)
            if (dispatchFailure != null) ExecutionEvents.publish(request.requestId, ExecutionMessage.ObserverIssue("ui"))
            runEvidenceRecorder.recordEventDispatch(eventProbe.snapshot())
            observerFailure?.let { error ->
                runEvidenceRecorder.recordRuntimeFailure()
                turnRecorder.recordRuntimeFailure(error.message)
                ExecutionEvents.publish(request.requestId, ExecutionMessage.Failure("Execution observer failed"))
                onFailure(error.message)
            }
            try { onFinally(runEvidenceRecorder, turnRecorder) } finally {
                ExecutionEvents.publish(request.requestId, ExecutionMessage.Ended)
                ExecutionEvents.closeRun(request.requestId)
            }
        }
    }
}

private class RuntimeEventDispatcher(
    scope: CoroutineScope,
    private val onEvent: (ThreadEvent) -> Unit,
    private val probe: RuntimeEventDispatchProbe,
) {
    private val events = Channel<ThreadEvent>(512)
    private val pending = AtomicInteger(0)
    @Volatile private var failure: Throwable? = null

    private val job = scope.launch {
        for (event in events) {
            yield()
            dispatchOne(event)
        }
    }

    fun dispatch(event: ThreadEvent) {
        failure?.let { throw RuntimeException("Debug event dispatch failed.", it) }
        val startedAt = probe.mark()
        val queueDepth = pending.incrementAndGet()
        val result = events.trySend(event)
        probe.recordEnqueue(startedAt, queueDepth)
        if (result.isFailure) {
            pending.decrementAndGet()
            throw RuntimeException("Debug event dispatch queue closed.", result.exceptionOrNull())
        }
    }

    suspend fun closeAndJoin(drain: Boolean): Throwable? {
        if (drain) {
            events.close()
            job.join()
        } else {
            events.cancel()
            job.join()
        }
        return failure
    }

    private fun dispatchOne(event: ThreadEvent) {
        val startedAt = probe.mark()
        try {
            onEvent(event)
        } catch (error: Throwable) {
            failure = error
        } finally {
            pending.decrementAndGet()
            probe.recordHandle(startedAt)
        }
    }
}

private class RuntimeEventDispatchProbe(
    private val nowNanos: () -> Long = { System.nanoTime() },
) {
    private val lock = Any()
    private var eventCount = 0
    private var maxQueueDepth = 0
    private var enqueueDurationNanos = 0L
    private var handleDurationNanos = 0L
    private var maxHandleDurationNanos = 0L

    fun mark(): Long = nowNanos()

    fun recordEnqueue(startedAt: Long, queueDepth: Int) {
        synchronized(lock) {
            eventCount += 1
            maxQueueDepth = maxOf(maxQueueDepth, queueDepth)
            enqueueDurationNanos += nowNanos() - startedAt
        }
    }

    fun recordHandle(startedAt: Long) {
        val elapsed = nowNanos() - startedAt
        synchronized(lock) {
            handleDurationNanos += elapsed
            maxHandleDurationNanos = maxOf(maxHandleDurationNanos, elapsed)
        }
    }

    fun snapshot(): RuntimeEventDispatchEvidence =
        synchronized(lock) {
            RuntimeEventDispatchEvidence(
                eventCount = eventCount,
                maxQueueDepth = maxQueueDepth,
                enqueueDurationMs = enqueueDurationNanos.toMillis(),
                handleDurationMs = handleDurationNanos.toMillis(),
                maxHandleDurationMs = maxHandleDurationNanos.toMillis(),
            )
        }
}

private fun Long.toMillis(): Long = this / 1_000_000L


private fun recordExecutionMessage(
    message: ExecutionMessage,
    evidence: McpProgressiveEvidenceRecorder?,
    run: RunEvidenceRecorder,
    turn: SessionTurnRecorder,
) {
    when (message) {
        is ExecutionMessage.Runtime -> {
            run.recordEvent(message.event)
            turn.recordEvent(message.event)
            McpProgressiveEvidenceCollector.record(message.event, evidence)
        }
        ExecutionMessage.Stopped -> { run.recordCancelled(); turn.recordCancelled() }
        is ExecutionMessage.Failure -> { run.recordRuntimeFailure(); turn.recordRuntimeFailure(message.message) }
        else -> Unit
    }
}

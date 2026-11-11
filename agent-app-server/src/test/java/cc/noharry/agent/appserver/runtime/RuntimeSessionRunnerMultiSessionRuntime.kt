@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.*
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest
import cc.noharry.agent.spi.*
import cc.noharry.agent.spi.reliability.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal abstract class RuntimeMultiSessionRuntimeStub : AgentRuntime {
    open fun run(request: RuntimeTurnRequest): Flow<ThreadEvent> = emptyFlow()
    open suspend fun postSteering(threadId: String, text: String): SteerResult = SteerResult.NOT_RUNNING
    open suspend fun pendingRequests(threadId: String): List<PendingRequest> = emptyList()
    open suspend fun getRunState(runId: String): RuntimeRunSnapshot? = null
    open suspend fun getRuntimeResult(runId: String): RuntimeResult? = null
    open suspend fun getRuntimeHandoff(runId: String): RuntimeHandoff? = null
    open suspend fun getRuntimeEvidence(evidenceId: String): RuntimeEvidence? = null
    open suspend fun getRuntimeTraceEvents(runId: String): List<RuntimeTraceEvent> = emptyList()
    open suspend fun querySessionEvents(query: SessionEventQuery): List<SessionEvent> = emptyList()
    open suspend fun replaySessionEvents(sessionId: String, runId: String): List<SessionEvent> = emptyList()
    open suspend fun getSessionEventLogHealth(sessionId: String, runId: String): SessionEventLogHealth = SessionEventLogHealth.unknown()
    open fun subscribeSessionEvents(sessionId: String, listener: (SessionEvent) -> Unit): AutoCloseable = AutoCloseable {}
    override fun close() = Unit
    override suspend fun listThreads(): List<ThreadInfo> = emptyList()
    override suspend fun deleteThread(threadId: String) = Unit
    override fun startThread(options: ThreadOptions): AgentThread = resumeThread(options.threadId ?: "session-1", options)
    override fun resumeThread(threadId: String, options: ThreadOptions): AgentThread = object : AgentThread {
        override val id = threadId
        override suspend fun run(input: TurnInput, options: TurnOptions): TurnResult =
            runStreamed(input, options).toList().filterIsInstance<ThreadEvent.TurnCompleted>().last().result
        override fun runStreamed(input: TurnInput, options: TurnOptions): Flow<ThreadEvent> = this@RuntimeMultiSessionRuntimeStub.run(
            RuntimeTurnRequest(options.turnId ?: "run-1", threadId, input.parts.filterIsInstance<InputPart.Text>().joinToString("") { it.text }),
        )
        override suspend fun steer(text: String, options: SteerOptions) = postSteering(threadId, text)
        override suspend fun interrupt() = Unit
        override suspend fun status() = ThreadStatus.IDLE
        override suspend fun pendingRequests() = pendingRequests(threadId)
        override suspend fun items(afterTurnId: String?): List<ThreadItem> = emptyList()
    }
    override val diagnostics: RuntimeDiagnostics = StubDiagnostics(this)
}

private class StubDiagnostics(private val owner: RuntimeMultiSessionRuntimeStub) : RuntimeDiagnostics {
    override suspend fun getRunState(threadId: String, turnId: String) = owner.getRunState(turnId)
    override suspend fun getRuntimeResult(threadId: String, turnId: String) = owner.getRuntimeResult(turnId)
    override suspend fun getRuntimeHandoff(threadId: String, turnId: String) = owner.getRuntimeHandoff(turnId)
    override suspend fun getRuntimeEvidence(evidenceId: String) = owner.getRuntimeEvidence(evidenceId)
    override suspend fun getRuntimeTraceEvents(threadId: String, turnId: String) = owner.getRuntimeTraceEvents(turnId)
    override suspend fun querySessionEvents(query: SessionEventQuery) = owner.querySessionEvents(query)
    override suspend fun replaySessionEvents(threadId: String, turnId: String) = owner.replaySessionEvents(threadId, turnId)
    override suspend fun getSessionEventLogHealth(threadId: String, turnId: String) = owner.getSessionEventLogHealth(threadId, turnId)
    override fun subscribeSessionEvents(threadId: String, listener: (SessionEvent) -> Unit) = owner.subscribeSessionEvents(threadId, listener)
}

internal class RuntimeMultiSessionBarrierRuntime(
    private val request: RuntimeTurnRequest,
    private val signals: BarrierRuntimeSignals,
) : RuntimeMultiSessionRuntimeStub() {
    private val waiting = AtomicBoolean(signals.waitForConfirmation)
    private val terminal = AtomicReference<RuntimeResult?>(null)
    private val approval = BarrierApproval(request.requestId, waiting, signals, terminal)
    override fun run(request: RuntimeTurnRequest): Flow<ThreadEvent> = multiSessionBarrierFlow(request, signals, waiting, terminal, approval)
    override suspend fun pendingRequests(threadId: String): List<PendingRequest> = if (waiting.get()) listOf(approval) else emptyList()
    override suspend fun getRunState(runId: String): RuntimeRunSnapshot =
        barrierRunState(runId, request, terminal.get(), signals.release.isCompleted, waiting.get())
    override suspend fun getRuntimeResult(runId: String): RuntimeResult? =
        terminal.get() ?: completedRuntimeResult(runId).takeIf { signals.release.isCompleted }
    override suspend fun postSteering(threadId: String, text: String): SteerResult {
        signals.postedSteering += PostedSteering(request.requestId, text)
        signals.steeringPosted.countDown()
        return SteerResult.ACCEPTED
    }
    override suspend fun getSessionEventLogHealth(sessionId: String, runId: String): SessionEventLogHealth {
        signals.onFinalizing?.invoke()
        return completeSessionEventLogHealth()
    }
}

private class BarrierApproval(
    override val turnId: String,
    private val waiting: AtomicBoolean,
    private val signals: BarrierRuntimeSignals,
    private val terminal: AtomicReference<RuntimeResult?>,
) : Approval() {
    override val id = "pending-confirm"
    override val tool = "test_tool"
    override val arguments = "{}"
    override val domain = "test"
    override val reason = "Approval required"
    override val expiresAt = Long.MAX_VALUE
    override val status get() = if (waiting.get()) ItemStatus.IN_PROGRESS else ItemStatus.COMPLETED
    override suspend fun approve(scope: ApprovalScope) = respond(true)
    override suspend fun deny(reason: String?) = respond(false)
    private fun respond(approved: Boolean): RespondResult {
        if (!waiting.compareAndSet(true, false)) return RespondResult.ALREADY_ANSWERED
        signals.postedConfirmations += PostedConfirmation(id, approved)
        signals.confirmationPosted.countDown()
        terminal.set(completedRuntimeResult(turnId))
        signals.release.complete(Unit)
        return RespondResult.ACCEPTED
    }
}

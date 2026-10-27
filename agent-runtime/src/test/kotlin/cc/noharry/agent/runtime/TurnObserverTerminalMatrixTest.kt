package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.CancelPendingConfirmationRequest
import cc.noharry.agent.kernel.api.ConfirmationChoice
import cc.noharry.agent.kernel.api.ConfirmationDecision
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProtocolRequestCancellation
import cc.noharry.agent.kernel.api.ProtocolRequestRejection
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.RuntimeCompletedTurn
import cc.noharry.agent.kernel.api.RuntimeTurnInput
import cc.noharry.agent.kernel.api.RuntimeTurnObserver
import cc.noharry.agent.kernel.api.TurnInputObservation
import cc.noharry.agent.kernel.api.TurnObservationReceipt
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.core.loop.completed
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Terminal coordinator matrix: post-persist exactly-once observer notification
 * across normal, confirmation, and protocol terminal paths.
 */
@OptIn(ExperimentalAgentRuntimeApi::class)
class TurnObserverTerminalMatrixTest {
    @Test
    fun normal_run_notifies_exactly_once_after_finish() =
        runBlocking {
            val observer = CountingObserver()
            val runtime =
                AgentRuntimeBuilder.create()
                    .modelClient(finishClient())
                    .turnObserver(observer)
                    .build()
            try {
                runtime.run(AgentRequest("n1", "s", "hello")).toList()
                assertEquals(1, observer.finishedCount.get())
                assertEquals("COMPLETED", observer.statuses.single())
                // Replaying close does not re-notify.
                runtime.close()
                assertEquals(1, observer.finishedCount.get())
            } finally {
                runtime.close()
            }
        }

    @Test
    fun confirmation_reject_and_cancel_notify_exactly_once() =
        runBlocking {
            val rejectObs = CountingObserver()
            val rejectRuntime =
                AgentRuntimeBuilder.create()
                    .confirmationOnRun()
                    .turnObserver(rejectObs)
                    .build()
            try {
                rejectRuntime.run(AgentRequest("c-rej", "s", "confirm")).toList()
                val pending = rejectRuntime.getPendingConfirmation("c-rej:confirmation")!!
                rejectRuntime.resumeConfirmation(
                    ConfirmationDecision(pending.pendingId, "resume-rej", ConfirmationChoice.DENY),
                )
                assertEquals(1, rejectObs.finishedCount.get())
                // Second deny / consume must not re-notify.
                rejectRuntime.resumeConfirmation(
                    ConfirmationDecision(pending.pendingId, "resume-rej-2", ConfirmationChoice.DENY),
                )
                assertEquals(1, rejectObs.finishedCount.get())
            } finally {
                rejectRuntime.close()
            }

            val cancelObs = CountingObserver()
            val cancelRuntime =
                AgentRuntimeBuilder.create()
                    .confirmationOnRun()
                    .turnObserver(cancelObs)
                    .build()
            try {
                cancelRuntime.run(AgentRequest("c-can", "s", "confirm")).toList()
                val pending = cancelRuntime.getPendingConfirmation("c-can:confirmation")!!
                cancelRuntime.cancelPendingConfirmation(
                    CancelPendingConfirmationRequest(pending.pendingId, "cancel-1"),
                )
                assertEquals(1, cancelObs.finishedCount.get())
            } finally {
                cancelRuntime.close()
            }
        }

    @Test
    fun protocol_reject_and_cancel_notify_exactly_once() =
        runBlocking {
            val rejectObs = CountingObserver()
            val rejectRuntime =
                AgentRuntimeBuilder.create()
                    .protocolRequestOnRun(kind = "demo", payloadJson = """{"k":"v"}""")
                    .turnObserver(rejectObs)
                    .build()
            try {
                val events = rejectRuntime.run(AgentRequest("p-rej", "s", "proto")).toList()
                val created =
                    events.filterIsInstance<AgentEvent.RuntimeProtocolRequestCreated>().single()
                rejectRuntime.rejectRuntimeProtocolRequest(
                    ProtocolRequestRejection(
                        protocolRequestId = created.protocolRequest.protocolRequestId,
                        requestId = "rej-1",
                        reason = "test reject",
                    ),
                )
                assertEquals(1, rejectObs.finishedCount.get())
                assertEquals("CANCELLED", rejectObs.statuses.single())
            } finally {
                rejectRuntime.close()
            }

            val cancelObs = CountingObserver()
            val cancelRuntime =
                AgentRuntimeBuilder.create()
                    .protocolRequestOnRun(kind = "demo", payloadJson = """{"k":"v"}""")
                    .turnObserver(cancelObs)
                    .build()
            try {
                val events = cancelRuntime.run(AgentRequest("p-can", "s", "proto")).toList()
                val created =
                    events.filterIsInstance<AgentEvent.RuntimeProtocolRequestCreated>().single()
                cancelRuntime.cancelRuntimeProtocolRequest(
                    ProtocolRequestCancellation(
                        protocolRequestId = created.protocolRequest.protocolRequestId,
                        requestId = "can-1",
                    ),
                )
                assertEquals(1, cancelObs.finishedCount.get())
            } finally {
                cancelRuntime.close()
            }
        }

    @Test
    fun observer_exception_fail_open_does_not_block_terminal() =
        runBlocking {
            val boom =
                object : RuntimeTurnObserver {
                    override val id: String = "boom"

                    override suspend fun onTurnInput(input: RuntimeTurnInput): TurnInputObservation =
                        TurnInputObservation.noop(id)

                    override suspend fun onTurnFinished(turn: RuntimeCompletedTurn): TurnObservationReceipt {
                        error("observer boom")
                    }
                }
            val runtime =
                AgentRuntimeBuilder.create()
                    .modelClient(finishClient())
                    .turnObserver(boom)
                    .build()
            try {
                val events = runtime.run(AgentRequest("ex1", "s", "ok")).toList()
                assertTrue(events.any { it is AgentEvent.Finished || it is AgentEvent.ResultAvailable })
            } finally {
                runtime.close()
            }
        }

    @Test
    fun cancellation_mid_delivery_auto_completes_remaining_in_coordinator() =
        runBlocking {
            val first = CountingObserver("first")
            val second = CancelOnceObserver("second")
            val runtime =
                AgentRuntimeBuilder.create()
                    .modelClient(finishClient())
                    .turnObserver(first)
                    .turnObserver(second)
                    .build()
            try {
                val failure =
                    runCatching {
                        runtime.run(AgentRequest("cancel-mid", "s", "hello")).toList()
                    }.exceptionOrNull()
                assertTrue(failure is kotlinx.coroutines.CancellationException)
                assertEquals(1, first.finishedCount.get())
                // Bounded retry inside NonCancellable completed the second observer.
                assertEquals(2, second.attempts.get())
                assertEquals(1, second.successCount.get())
            } finally {
                runtime.close()
            }
        }

    private fun finishClient(): ModelClient =
        object : ModelClient {
            override val providerConcurrency: ProviderConcurrencyDeclaration =
                ProviderConcurrencyDeclaration.serialized()
            override fun complete(request: ModelRequest): Flow<ModelEvent> =
                flowOf(
                    ModelEvent.ToolCall("f", "finish", """{"response":"ok"}"""),
                    ModelEvent.Completed,
                )
        }

    private class CountingObserver(
        override val id: String = "count",
    ) : RuntimeTurnObserver {
        val finishedCount = AtomicInteger(0)
        val statuses = CopyOnWriteArrayList<String>()

        override suspend fun onTurnInput(input: RuntimeTurnInput): TurnInputObservation =
            TurnInputObservation.noop(id)

        override suspend fun onTurnFinished(turn: RuntimeCompletedTurn): TurnObservationReceipt {
            finishedCount.incrementAndGet()
            statuses += turn.terminalStatus
            return TurnObservationReceipt(id, enqueued = true)
        }
    }

    private class CancelOnceObserver(
        override val id: String,
    ) : RuntimeTurnObserver {
        val attempts = AtomicInteger(0)
        val successCount = AtomicInteger(0)

        override suspend fun onTurnInput(input: RuntimeTurnInput): TurnInputObservation =
            TurnInputObservation.noop(id)

        override suspend fun onTurnFinished(turn: RuntimeCompletedTurn): TurnObservationReceipt {
            val n = attempts.incrementAndGet()
            if (n == 1) {
                throw kotlinx.coroutines.CancellationException("observer cancelled once")
            }
            successCount.incrementAndGet()
            return TurnObservationReceipt(id, enqueued = true)
        }
    }
}

@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.*
import cc.noharry.agent.appserver.evidence.RunEvidence
import cc.noharry.agent.appserver.evidence.RunEvidenceRecorder
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.session.SessionTurnRecorder
import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.spi.reliability.RuntimeCompletionSummary
import cc.noharry.agent.spi.reliability.RuntimeEvidence
import cc.noharry.agent.spi.reliability.RuntimeHandoff
import cc.noharry.agent.spi.reliability.RuntimeResult
import cc.noharry.agent.spi.reliability.RuntimeRunSnapshot
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import cc.noharry.agent.spi.reliability.RuntimeTraceEvent
import cc.noharry.agent.spi.ToolSource
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RuntimeRunCollectionTest {
    /** An A2A run's spoken text comes from the app's voice ([NotesVoice] here), audio set up or not. */
    @Before
    fun speakWithAVoice() = RuntimeVoice.install(NotesVoice)

    @After
    fun noVoice() = RuntimeVoice.install(VoicePort.NONE)

    @Test fun markedProgressPublishesBeforeRuntimeCompletesWithoutAudioConfiguration() = runBlocking {
        val release = CountDownLatch(1)
        val subscription = ExecutionEvents.subscribe(REQUEST.requestId)
        val events = listOf(
            notedToolStarted(RUN_ID, "正在核对目的地信息。"),
            testTurnCompleted(firstPartyResult(RuntimeRunStatus.COMPLETED, "地点信息已查到。")),
        )
        val job = launch(Dispatchers.Default) {
            collectRuntime(
                // Held before the turn ends, so the progress must go out before the run completes.
                FlowAgentRuntime(events, { index -> if (index == events.lastIndex) check(release.await(10, TimeUnit.SECONDS)) }, diagnosticResult = firstPartyResult(RuntimeRunStatus.COMPLETED, "地点信息已查到。")),
                REQUEST, null, evidenceRecorder(), SessionTurnRecorder(REQUEST, REQUEST.input),
                {}, {}, { error("unexpected failure: $it") }, { _, _ -> },
            )
        }
        try {
            val progress = withTimeout(3_000) {
                var message = subscription.events.receive()
                while (message !is ExecutionMessage.Speech) message = subscription.events.receive()
                message
            }
            assertEquals(ExecutionMessage.Speech("正在核对目的地信息。", false), progress)
            assertTrue(job.isActive)
            release.countDown()
            job.join()
            val remaining = mutableListOf<ExecutionMessage>()
            for (message in subscription.events) remaining += message
            assertEquals(listOf(ExecutionMessage.Speech("地点信息已查到。", true)), remaining.filterIsInstance<ExecutionMessage.Speech>())
        } finally {
            release.countDown()
            job.cancel()
            job.join()
            subscription.close()
        }
    }

    @Test
    fun blockedLogProducerLeavesUiResponsiveAndEventsOnUi() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "test-ui") }.asCoroutineDispatcher().use { ui ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val producerThread = AtomicReference<String>()
            val handledThreads = CopyOnWriteArrayList<String>()
            val job = launch(ui) {
                collectRuntime(
                    runtime = FlowAgentRuntime(listOf(testTurnStarted(RUN_ID)), {
                        producerThread.set(Thread.currentThread().name.substringBefore(" @"))
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }),
                    request = REQUEST, evidenceRecorder = null, runEvidenceRecorder = evidenceRecorder(),
                    turnRecorder = SessionTurnRecorder(REQUEST, REQUEST.input),
                    onEvent = { handledThreads += Thread.currentThread().name.substringBefore(" @") },
                    onStopped = {}, onFailure = { error("unexpected failure: $it") }, onFinally = { _, _ -> },
                )
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { withContext(ui) { assertEquals("test-ui", Thread.currentThread().name.substringBefore(" @")) } }
                assertFalse(producerThread.get() == "test-ui")
                release.countDown()
                job.join()
                assertEquals(listOf("test-ui"), handledThreads)
            } finally {
                release.countDown()
                job.cancel()
                job.join()
            }
        }
    }

    @Test
    fun cancellationFlushStaysOffUiAndFinalizesOnce() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "test-ui") }.asCoroutineDispatcher().use { ui ->
            val entered = CountDownLatch(1)
            val releaseProducer = CountDownLatch(1)
            val flushing = CountDownLatch(1)
            val releaseFlush = CountDownLatch(1)
            val finalized = CopyOnWriteArrayList<String>()
            val runtime = FlowAgentRuntime(listOf(testTurnStarted(RUN_ID)), {
                entered.countDown()
                check(releaseProducer.await(10, TimeUnit.SECONDS))
            }, onFlowFinally = {
                assertFalse(Thread.currentThread().name.substringBefore(" @") == "test-ui")
                flushing.countDown()
                check(releaseFlush.await(10, TimeUnit.SECONDS))
            })
            val job = launch(ui) {
                collectRuntime(runtime, REQUEST, null, evidenceRecorder(),
                    SessionTurnRecorder(REQUEST, REQUEST.input), {}, {},
                    { error("unexpected failure: $it") }, { _, _ -> finalized += Thread.currentThread().name.substringBefore(" @") })
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                job.cancel()
                releaseProducer.countDown()
                assertTrue(flushing.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { withContext(ui) { assertEquals("test-ui", Thread.currentThread().name.substringBefore(" @")) } }
                releaseFlush.countDown()
                job.join()
                assertEquals(listOf("test-ui"), finalized)
            } finally {
                releaseProducer.countDown()
                releaseFlush.countDown()
                job.cancel()
                job.join()
            }
        }
    }

    @Test
    fun slowUiHandlerDoesNotBlockRuntimeEventCollection() = runBlocking {
        val uiStarted = CountDownLatch(1)
        val releaseUi = CountDownLatch(1)
        val secondEventReached = CountDownLatch(1)
        val handled = CopyOnWriteArrayList<String>()
        val finalEvidence = AtomicReference<RunEvidence>()

        val job = launch(Dispatchers.Default) {
            collectRuntime(
                runtime = runtime(secondEventReached),
                request = REQUEST,
                evidenceRecorder = null,
                runEvidenceRecorder = evidenceRecorder(),
                turnRecorder = SessionTurnRecorder(REQUEST, REQUEST.input),
                onEvent = slowOrderedHandler(uiStarted, releaseUi, handled),
                onStopped = {},
                onFailure = { error("unexpected failure: $it") },
                onFinally = { runRecorder, _ -> finalEvidence.set(runRecorder.snapshot()) },
            )
        }

        assertTrue(uiStarted.await(2, TimeUnit.SECONDS))
        assertTrue(secondEventReached.await(2, TimeUnit.SECONDS))
        releaseUi.countDown()
        job.join()

        assertEquals(listOf("TurnStarted", "ItemCompleted", "TurnCompleted"), handled)
        assertEquals(3, finalEvidence.get().eventDispatch.eventCount)
        assertTrue(finalEvidence.get().eventDispatch.maxQueueDepth >= 1)
    }

    @Test
    fun dispatcherDoesNotRunUiHandlerInlineOnCollectorThread() = runBlocking {
        val secondEventReached = CountDownLatch(1)
        val handled = CopyOnWriteArrayList<String>()
        val reachedWhileHandling = AtomicReference<Boolean>()

        val job = launch {
            collectRuntime(
                runtime = runtime(secondEventReached),
                request = REQUEST,
                evidenceRecorder = null,
                runEvidenceRecorder = evidenceRecorder(),
                turnRecorder = SessionTurnRecorder(REQUEST, REQUEST.input),
                onEvent = { event ->
                    handled += event::class.java.simpleName
                    // Still handling the first event, the collector must reach the next one: run inline on the
                    // collector thread, this handler would hold it and wait out the two seconds. (A 200 ms timeout on
                    // this same thread against a 700 ms sleep here failed under a loaded full run.)
                    if (event is ThreadEvent.TurnStarted) reachedWhileHandling.set(secondEventReached.await(2, TimeUnit.SECONDS))
                },
                onStopped = {},
                onFailure = { error("unexpected failure: $it") },
                onFinally = { _, _ -> },
            )
        }
        job.join()

        assertEquals(true, reachedWhileHandling.get())
        assertEquals(listOf("TurnStarted", "ItemCompleted", "TurnCompleted"), handled)
    }

    @Test
    fun steeringPostAcceptedMapsToRuntimeTranscript() = runBlocking {
        val posted = AtomicReference<String>()
        val thread = FlowAgentRuntime(emptyList(), {}, postResult = SteerResult.ACCEPTED, onPost = posted::set).resumeThread("s")
        val result = postRuntimeSteering(thread, "keep going")
        assertEquals("keep going", posted.get())
        assertEquals("Steering sent", result.status)
        assertEquals(SteeringTranscript.Runtime("Runtime steering input", "keep going"), result.transcript)
    }

    @Test
    fun steeringPostNotFoundMapsToVisibleStatus() = runBlocking {
        val thread = FlowAgentRuntime(emptyList(), {}).resumeThread("s")
        val result = postRuntimeSteering(thread, "late input")
        assertEquals("Steering inactive", result.status)
        assertTrue(result.runEnded)
        assertEquals(SteeringTranscript.Status("The turn is no longer active."), result.transcript)
    }

    @Test
    fun confirmationApproveAcceptedMapsToVisibleStatus() = runBlocking {
        val posted = AtomicReference<Boolean>()
        val runtime = FlowAgentRuntime(emptyList(), {}, confirmationResult = RespondResult.ACCEPTED, onConfirmation = posted::set)
        val result = postRuntimeConfirmation(runtime.resumeThread("s"), "pending-1", true)
        assertEquals(true, posted.get())
        assertEquals("Confirmation sent", result.status)
        assertEquals(SteeringTranscript.Status("Confirmation decision accepted."), result.transcript)
    }

    @Test
    fun denialCallsThePendingApproval() = runBlocking {
        val posted = AtomicReference<Boolean>()
        val runtime = FlowAgentRuntime(emptyList(), {}, confirmationResult = RespondResult.ACCEPTED, onConfirmation = posted::set)
        postRuntimeConfirmation(runtime.resumeThread("s"), "pending-1", false)
        assertEquals(false, posted.get())
    }

    @Test
    fun resolvedAndExpiredApprovalsRemainVisible() = runBlocking {
        for ((response, status) in listOf(RespondResult.ALREADY_ANSWERED to "Confirmation consumed", RespondResult.EXPIRED to "Confirmation expired")) {
            val runtime = FlowAgentRuntime(emptyList(), {}, confirmationResult = response)
            val result = postRuntimeConfirmation(runtime.resumeThread("s"), "pending-1", true)
            assertEquals(status, result.status)
            assertTrue(result.transcript is SteeringTranscript.Status)
        }
    }

    @Test
    fun approvalResponseDoesNotFetchAnOutOfStreamTerminalResult() = runBlocking {
        val runtime = FlowAgentRuntime(emptyList(), {}, confirmationResult = RespondResult.ACCEPTED)
        postRuntimeConfirmation(runtime.resumeThread("s"), "pending-1", true)
        assertEquals(0, runtime.resultReads)
    }

    @Test
    fun livePendingRequestsCanBeAnsweredFromAnotherThreadHandle() = runBlocking {
        val runtime = FlowAgentRuntime(emptyList(), {}, confirmationResult = RespondResult.ACCEPTED)
        val first = runtime.resumeThread("s").pendingRequests().single()
        val second = runtime.resumeThread("s").pendingRequests().single()
        assertEquals(first.id, second.id)
        assertEquals(RespondResult.ACCEPTED, (second as Approval).approve())
    }

    @Test
    fun confirmationNotFoundMapsToVisibleStatus() = runBlocking {
        val result = postRuntimeConfirmation(FlowAgentRuntime(emptyList(), {}).resumeThread("s"), "pending-missing", false)
        assertEquals("Confirmation missing", result.status)
        assertEquals(SteeringTranscript.Status("Confirmation was not found."), result.transcript)
    }

    private fun runtime(secondEventReached: CountDownLatch): AgentRuntime =
        runtime { secondEventReached.countDown() }

    private fun runtime(beforeSecondEvent: () -> Unit): AgentRuntime =
        FlowAgentRuntime(
            events = listOf(
                testTurnStarted(RUN_ID),
                ThreadEvent.ItemCompleted(ThreadItem.AgentMessage("$RUN_ID-message", RUN_ID, "", MessagePhase.COMMENTARY, ItemStatus.COMPLETED)),
                testTurnCompleted(RuntimeResult(RUN_ID, RuntimeRunStatus.COMPLETED, "done")),
            ),
            beforeEvent = { index -> if (index == 1) beforeSecondEvent() },
        )

    private fun slowOrderedHandler(
        uiStarted: CountDownLatch,
        releaseUi: CountDownLatch,
        handled: MutableList<String>,
    ): (ThreadEvent) -> Unit = { event ->
        handled += event::class.java.simpleName
        if (event is ThreadEvent.TurnStarted) {
            uiStarted.countDown()
            assertTrue(releaseUi.await(2, TimeUnit.SECONDS))
        }
    }

    private fun evidenceRecorder(): RunEvidenceRecorder =
        RunEvidenceRecorder(
            evidenceDir = Files.createTempDirectory("debug-runtime-run-collection-").toFile(),
            request = REQUEST,
            providerLabel = "OpenAI",
            streamLabel = "non-stream",
            backendLabel = "live",
        )

    private class FlowAgentRuntime(
        private val events: List<ThreadEvent>,
        private val beforeEvent: (Int) -> Unit,
        private val postResult: SteerResult = SteerResult.NOT_RUNNING,
        private val onPost: (String) -> Unit = {},
        private val confirmationResult: RespondResult = RespondResult.NOT_FOUND,
        private val onConfirmation: (Boolean) -> Unit = {},
        private val onFlowFinally: () -> Unit = {},
        private val diagnosticResult: RuntimeResult? = null,
    ) : RuntimeMultiSessionRuntimeStub() {
        var resultReads = 0
        override fun run(request: RuntimeTurnRequest): Flow<ThreadEvent> = flow {
            try {
                events.forEachIndexed { index, event -> beforeEvent(index); emit(event) }
            } finally { onFlowFinally() }
        }
        override suspend fun postSteering(threadId: String, text: String): SteerResult {
            onPost(text)
            return postResult
        }
        override suspend fun getRuntimeResult(runId: String): RuntimeResult? {
            resultReads++
            return diagnosticResult
        }
        override suspend fun pendingRequests(threadId: String): List<PendingRequest> =
            if (confirmationResult == RespondResult.NOT_FOUND) emptyList() else listOf(object : Approval() {
                override val id = "pending-1"
                override val turnId = RUN_ID
                override val tool = "web_fetch"
                override val arguments = "{}"
                override val domain = "example.com"
                override val reason = "Approval required"
                override val expiresAt = Long.MAX_VALUE
                override val status = ItemStatus.IN_PROGRESS
                override suspend fun approve(scope: ApprovalScope): RespondResult { onConfirmation(true); return confirmationResult }
                override suspend fun deny(reason: String?): RespondResult { onConfirmation(false); return confirmationResult }
            })
    }

    /** A voice that says the model's note on each call as the call starts and the reply when the turn ends. */
    private object NotesVoice : VoicePort {
        override fun textAnnouncer(scope: CoroutineScope, output: (text: String, final: Boolean) -> Unit): RunAnnouncer = object : RunAnnouncer {
            override val busy = false
            override fun observe(runScope: RuntimeRunScope, event: ThreadEvent, ownsUi: (RuntimeRunScope) -> Boolean) {
                when (event) {
                    is ThreadEvent.ItemStarted -> (event.item as? ThreadItem.ToolCall)?.let { ToolNotes.of(it.tool, it.arguments.orEmpty()) }?.let { output(it, false) }
                    is ThreadEvent.TurnCompleted -> output(event.result.finalResponse, true)
                    else -> Unit
                }
            }
            override fun announce(text: String) = false
            override fun announceFirst(text: String) = false
            override fun speakWireSpeech(taskId: String, text: String, final: Boolean) = Unit
            override fun stop() = Unit
        }
    }

    private companion object {
        const val RUN_ID = "run-1"
        val REQUEST = RuntimeTurnRequest(
            requestId = "debug-1",
            sessionId = "session-1",
            input = "hello",
            scene = "debug-console",
        )

        /** A call of a device's tool on which the model wrote [note] for the person. */
        fun notedToolStarted(runId: String, note: String): ThreadEvent {
            ToolNotes.put("mcp__device__navigation_poi_search", "{\"call\":1}", note)
            return ThreadEvent.ItemStarted(ThreadItem.ToolCall("call-1", runId, "mcp__device__navigation_poi_search", ToolSource.BACKEND, arguments = "{\"call\":1}"))
        }

        fun firstPartyResult(
            status: RuntimeRunStatus,
            response: String,
            outcome: RuntimeCompletionOutcome = RuntimeCompletionOutcome.SUCCEEDED,
        ): RuntimeResult = RuntimeResult(
            RUN_ID,
            status,
            response,
            completion = RuntimeCompletionSummary(outcome = outcome),
        )
    }
}

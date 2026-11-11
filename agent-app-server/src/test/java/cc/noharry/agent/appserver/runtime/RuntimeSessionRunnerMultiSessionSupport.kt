@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.evidence.RunEvidenceRecorder
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.SessionTurn
import cc.noharry.agent.appserver.session.SessionCoordinator
import cc.noharry.agent.appserver.session.SessionTurnRecorder
import cc.noharry.agent.appserver.session.TranscriptStore
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertTrue

/**
 * Generous bound for run-entry waits. These are liveness checks, not latency assertions, so a
 * loaded machine must not be able to turn them red.
 */
internal const val RUN_ENTRY_TIMEOUT_SEC = 30L

internal fun awaitUntil(timeoutSec: Long = 10, predicate: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
    while (System.nanoTime() < deadline) {
        if (predicate()) return true
        Thread.yield()
    }
    return predicate()
}

internal fun multiSessionRecorders(request: RuntimeTurnRequest): RuntimeRunRecorders =
    RuntimeRunRecorders(
        evidenceRecorder = null,
        runEvidenceRecorder = RunEvidenceRecorder(
            evidenceDir = Files.createTempDirectory("sample-multi-session-").toFile(),
            request = request,
            providerLabel = "fake",
            streamLabel = "non-stream",
            backendLabel = "fake",
        ),
        turnRecorder = SessionTurnRecorder(request, request.input),
    )


internal fun multiSessionTurn(
    sessionId: String,
    requestId: String,
    userText: String,
): SessionTurn =
    SessionTurn(
        turnId = requestId,
        requestId = requestId,
        runId = requestId,
        sessionId = sessionId,
        scene = "debug-console",
        userText = userText,
        startedAtEpochMillis = 1L,
        completedAtEpochMillis = 2L,
        finalStatus = RuntimeRunStatus.COMPLETED.name,
        finalResponse = userText,
        tools = emptyList(),
    )

internal fun multiSessionRunner(
    scope: CoroutineScope,
    transcript: TranscriptStore,
    coordinator: SessionCoordinator,
    statuses: MutableList<String>,
): RuntimeSessionRunner =
    RuntimeSessionRunner(
        scope = scope,
        transcript = transcript,
        sessionCoordinator = coordinator,
        currentConfig = { null },
        loadConfig = { error("unused") },
        setProvider = {},
        setStatus = { statuses += it },
        streamEnabled = { false },
        useSimulatedModel = { true },
        mcpProviders = { emptyList() },
        streamLabel = { "non-stream" },
        backendLabel = { "fake" },
        confirmationMode = { ConfirmationMode.ASK_EVERY_TIME },
        notifyChanged = {},
    )

internal class RuntimeMultiSessionHarness : AutoCloseable {
    val transcript = TranscriptStore()
    val coordinator = SessionCoordinator(transcript)
    val statuses = CopyOnWriteArrayList<String>()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    val runner = multiSessionRunner(scope, transcript, coordinator, statuses)

    suspend fun startHeld(fixture: RuntimeMultiSessionFixture): StartRunAdmission {
        coordinator.adoptActiveSessionId(fixture.sessionId)
        return startPrepared(fixture)
    }

    suspend fun startPrepared(fixture: RuntimeMultiSessionFixture): StartRunAdmission {
        val admission = runner.startPreparedRun(
            sessionId = fixture.sessionId,
            request = fixture.request,
            runtime = fixture.runtime,
            resources = fixture.resources(),
            recorders = multiSessionRecorders(fixture.request),
            text = fixture.request.input,
        )
        if (admission != StartRunAdmission.BUSY_MAX_ACTIVE_SESSIONS) {
            // Admission is deterministic; run entry is not, because the flow starts on a shared
            // dispatcher. A tight bound here turned scheduler pressure into red publish gates, so
            // the wait is generous: it only fails when the run genuinely never started.
            assertTrue(
                "run ${fixture.runId} never entered its flow",
                fixture.entered.await(RUN_ENTRY_TIMEOUT_SEC, TimeUnit.SECONDS),
            )
        }
        return admission
    }

    /**
     * True once the run has left the registry and its finished snapshot is published.
     * Waiting on registry removal alone races with the snapshot publication that follows it.
     */
    fun awaitFinishedSnapshot(sessionId: String): Boolean =
        runner.registryForTest().get(sessionId) == null &&
            runner.activeRunSnapshots().any { it.sessionId == sessionId && !it.isActive }

    override fun close() {
        job.cancel()
    }
}

@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidence
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidenceType
import cc.noharry.agent.kernel.api.reliability.RuntimeRunEnvelope
import cc.noharry.agent.kernel.api.reliability.RuntimeRunPhase
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import cc.noharry.agent.kernel.core.ReliabilityRetentionPolicy

class ReliabilityStorageTest {
    @Test
    fun terminalRunArtifactsRestoreFromFileStorage() = runBlocking {
        val storageRoot = Files.createTempDirectory("agent-runtime-storage-").toFile()
        val runtime = storedRuntime(
            storageRoot,
            StorageModel(
                ModelEvent.ToolCall(
                    id = "tool-1",
                    name = "finish",
                    argumentsJson = """{"response":"done token=${FAKE_SECRET_KEY} phone=13800138000"}""",
                ),
                ModelEvent.Completed,
            ),
        )

        val eventResult = runtime.run(agentRequest()).toList()
            .filterIsInstance<AgentEvent.ResultAvailable>()
            .single()
            .result
        val restored = storedRuntime(storageRoot)
        val evidence = requireNotNull(restored.getRuntimeEvidence(eventResult.evidenceIds.single()))
        val traceNames = restored.getRuntimeTraceEvents("request-1").map { it.eventName }

        assertEquals(RuntimeRunStatus.COMPLETED, restored.getRunState("request-1")?.status)
        assertEquals(RuntimeRunPhase.TERMINAL, restored.getRunEnvelope("request-1")?.phase)
        assertEquals(eventResult, restored.getRuntimeResult("request-1"))
        assertEquals(RuntimeEvidenceType.RESULT_WRITTEN, evidence.type)
        assertTrue(traceNames.contains("RUN_CREATED"))
        assertTrue(traceNames.contains("RESULT_WRITTEN"))
        assertTrue(restored.getRuntimeWorkpad("request-1")?.completionBar?.single()?.done == true)
        assertRedacted(evidence.summary)
    }

    @Test
    fun waitingUserInputArtifactsRestoreFromFileStorage() = runBlocking {
        val storageRoot = Files.createTempDirectory("agent-runtime-storage-").toFile()
        val runtime = storedRuntime(
            storageRoot,
            FakeModelClient(schemaThenBackendRounds()),
        )

        assertTrue(runtime.run(agentRequest()).toList().any { it is AgentEvent.ConfirmationRequired })
        val restored = storedRuntime(storageRoot)
        val handoff = requireNotNull(restored.getRuntimeHandoff("request-1"))
        val handoffEvidence = requireNotNull(restored.getRuntimeEvidence(handoff.evidenceRefs.single()))

        assertEquals(RuntimeRunStatus.WAITING_CONFIRMATION, restored.getRunState("request-1")?.status)
        assertEquals(RuntimeRunPhase.WAITING_CONFIRMATION, restored.getRunEnvelope("request-1")?.phase)
        assertFalse(handoff.nextActions.joinToString().contains("sk-waitstorage123"))
        assertEquals(RuntimeEvidenceType.HANDOFF_WRITTEN, handoffEvidence.type)
        assertTrue(restored.getRuntimeTraceEvents("request-1").any { it.eventName == "HANDOFF_WRITTEN" })
        assertRedacted(handoffEvidence.summary)
    }

    @Test
    fun retentionDeletesOldTerminalRunsButKeepsWaitingRuns() {
        val storageRoot = Files.createTempDirectory("agent-runtime-retention-").toFile()
        val storage = FileBackedReliabilityStorage(
            storageRoot,
            ReliabilityRetentionPolicy(
                maxRunDirectories = 2,
                maxStartupScanRunDirectories = 10,
                maxStorageBytes = null,
            ),
        )

        storage.saveRun(storageSnapshot("waiting", RuntimeRunStatus.WAITING_USER_INPUT, 1L))
        storage.saveRun(storageSnapshot("old-finished", RuntimeRunStatus.COMPLETED, 2L))
        storage.saveEvidence(storageEvidence("old-evidence", "old-finished"))
        storage.saveRun(storageSnapshot("new-finished", RuntimeRunStatus.COMPLETED, 3L))

        assertEquals(RuntimeRunStatus.WAITING_USER_INPUT, storage.readSnapshot("waiting")?.status)
        assertEquals(RuntimeRunStatus.COMPLETED, storage.readSnapshot("new-finished")?.status)
        assertNull(storage.readSnapshot("old-finished"))
        assertNull(storage.readEvidence("old-evidence"))
    }

    @Test
    fun startupScanLimitsLatestSnapshotSearchToNewestRuns() {
        val storageRoot = Files.createTempDirectory("agent-runtime-scan-").toFile()
        val storage = FileBackedReliabilityStorage(
            storageRoot,
            ReliabilityRetentionPolicy(
                maxRunDirectories = 10,
                maxStartupScanRunDirectories = 2,
                maxStorageBytes = null,
            ),
        )

        storage.saveRun(storageSnapshot("old-waiting", RuntimeRunStatus.WAITING_USER_INPUT, 1L))
        storage.saveRun(storageSnapshot("new-finished-1", RuntimeRunStatus.COMPLETED, 2L))
        storage.saveRun(storageSnapshot("new-finished-2", RuntimeRunStatus.COMPLETED, 3L))

        assertNull(storage.latestSnapshot("session-1", RuntimeRunStatus.WAITING_USER_INPUT))
        assertEquals(
            "new-finished-2",
            storage.latestSnapshot("session-1", RuntimeRunStatus.COMPLETED)?.runId,
        )
    }

    @Test
    fun diskLimitDeletesOldTerminalRunSidecars() {
        val storageRoot = Files.createTempDirectory("agent-runtime-disk-").toFile()
        val storage = FileBackedReliabilityStorage(
            storageRoot,
            ReliabilityRetentionPolicy(
                maxRunDirectories = 10,
                maxStartupScanRunDirectories = 10,
                maxStorageBytes = 800L,
            ),
        )

        storage.saveRun(storageSnapshot("old-finished", RuntimeRunStatus.COMPLETED, 1L))
        storage.saveEvidence(storageEvidence("old-evidence", "old-finished", "x".repeat(2_000)))
        storage.saveRun(storageSnapshot("new-finished", RuntimeRunStatus.COMPLETED, 2L))

        assertNull(storage.readSnapshot("old-finished"))
        assertNull(storage.readEvidence("old-evidence"))
        assertEquals(RuntimeRunStatus.COMPLETED, storage.readSnapshot("new-finished")?.status)
    }

    @Test
    fun storageWritesCandidateHardeningPolicyMarker() {
        val storageRoot = Files.createTempDirectory("agent-runtime-policy-").toFile()

        FileBackedReliabilityStorage(storageRoot)

        val marker = File(storageRoot, RELIABILITY_STORAGE_POLICY_MARKER_FILE).readText()
        assertTrue(marker.contains("minSdk=$ANDROID_RELIABILITY_STORAGE_MIN_SDK"))
        assertTrue(marker.contains("rootKind=APP_PRIVATE_NO_BACKUP"))
        assertTrue(marker.contains("fileLockMode=PER_OPERATION_ADVISORY"))
        assertTrue(marker.contains("encryptionMode=HOST_APP_PRIVATE_STORAGE_REQUIRED"))
    }

    @Test
    fun storageRootMustBeDirectory() {
        val storageRoot = Files.createTempFile("agent-runtime-root-", ".tmp").toFile()
        val error = try {
            FileBackedReliabilityStorage(storageRoot)
            null
        } catch (error: IllegalArgumentException) {
            error
        }

        assertEquals(RELIABILITY_STORAGE_ROOT_FILE_ERROR, error?.message)
    }

    @Test
    fun advisoryFileLockRejectsConcurrentWrite() {
        val storageRoot = Files.createTempDirectory("agent-runtime-lock-").toFile()
        val lockFile = File(storageRoot, RELIABILITY_STORAGE_LOCK_FILE)

        RandomAccessFile(lockFile, "rw").use { lockOwner ->
            val lock = lockOwner.channel.lock()
            try {
                val storage = FileBackedReliabilityStorage(storageRoot)
                val error = try {
                    storage.saveRun(storageSnapshot("locked", RuntimeRunStatus.COMPLETED, 1L))
                    null
                } catch (error: IllegalStateException) {
                    error
                }

                assertEquals(RELIABILITY_STORAGE_LOCK_UNAVAILABLE, error?.message)
                assertNull(storage.readSnapshot("locked"))
            } finally {
                lock.release()
            }
        }
    }

    private fun storedRuntime(
        storageRoot: File,
        model: ModelClient? = null,
    ): DefaultAgentRuntime {
        val builder = AgentRuntimeBuilder.create()
            .reliabilityStorageRoot(storageRoot)
            .workspaceRoot(Files.createTempDirectory("storage-ws-").toFile())
            .toolProvider(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .hook(confirmWithPrompt("Need token=sk-waitstorage123"))
        model?.let { builder.modelClient(it) }
        return builder.build() as DefaultAgentRuntime
    }

    private fun agentRequest(): AgentRequest = AgentRequest(
        requestId = "request-1",
        sessionId = "session-1",
        input = "run",
    )

    private fun storageSnapshot(
        runId: String,
        status: RuntimeRunStatus,
        updatedAtEpochMillis: Long,
    ): Pair<RuntimeRunSnapshot, RuntimeRunEnvelope> {
        val phase = if (status == RuntimeRunStatus.COMPLETED) {
            RuntimeRunPhase.TERMINAL
        } else {
            RuntimeRunPhase.WAITING_USER_INPUT
        }
        return RuntimeRunSnapshot(
            runId = runId,
            sessionId = "session-1",
            status = status,
            updatedAtEpochMillis = updatedAtEpochMillis,
        ) to RuntimeRunEnvelope(
            runId = runId,
            requestId = "$runId-request",
            sessionId = "session-1",
            status = status,
            phase = phase,
            createdAtEpochMillis = updatedAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }

    private fun FileBackedReliabilityStorage.saveRun(
        runState: Pair<RuntimeRunSnapshot, RuntimeRunEnvelope>,
    ) {
        saveRun(runState.first, runState.second)
    }

    private fun storageEvidence(
        evidenceId: String,
        runId: String,
        summary: String = "summary",
    ): RuntimeEvidence = RuntimeEvidence(
        evidenceId = evidenceId,
        runId = runId,
        type = RuntimeEvidenceType.RESULT_WRITTEN,
        summary = summary,
        createdAtEpochMillis = 1L,
    )

    private fun assertRedacted(summary: String) {
        assertFalse(summary.contains(FAKE_SECRET_KEY))
        assertFalse(summary.contains("sk-waitstorage123"))
        assertFalse(summary.contains("13800138000"))
        assertTrue(summary.contains("[REDACTED_SECRET]") || summary.contains("[REDACTED_PHONE]"))
    }
}

private class StorageModel(
    vararg private val events: ModelEvent,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override fun complete(request: ModelRequest): Flow<ModelEvent> =
        flowOf(*events)
}

/** A made-up key the redaction must catch; built at run time so the source never holds a key-shaped literal. */
private val FAKE_SECRET_KEY = "sk-" + "storagesecret123"

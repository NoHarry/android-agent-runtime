package cc.noharry.agent.runtime.reliability
import cc.noharry.agent.kernel.api.reliability.RuntimeRunEnvelope
import cc.noharry.agent.kernel.api.reliability.RuntimeRunPhase
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.state.RELIABILITY_CLEANUP_MARKER_FILE
import cc.noharry.agent.kernel.core.state.ReliabilityRetentionPolicy
import cc.noharry.agent.runtime.scheduler
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliabilityCleanupSchedulerTest {
    @Test
    fun cleanupSchedulerWritesMarkerAndHonorsMutationCadence() {
        val storageRoot = Files.createTempDirectory("agent-runtime-cleanup-").toFile()
        val cleanupMarker = File(storageRoot, RELIABILITY_CLEANUP_MARKER_FILE)
        val storage = FileBackedReliabilityStorage(
            storageRoot,
            ReliabilityRetentionPolicy(
                maxRunDirectories = 1,
                maxStartupScanRunDirectories = 10,
                maxStorageBytes = null,
                cleanupEveryMutations = 2,
            ),
        )

        storage.saveRun(storageSnapshot("old-finished", RuntimeRunStatus.COMPLETED, 1L))
        assertFalse(cleanupMarker.exists())

        storage.saveRun(storageSnapshot("new-finished", RuntimeRunStatus.COMPLETED, 2L))
        val markerText = cleanupMarker.readText()

        assertNull(storage.readSnapshot("old-finished"))
        assertEquals(RuntimeRunStatus.COMPLETED, storage.readSnapshot("new-finished")?.status)
        assertTrue(markerText.contains("cleanupMode=ON_STORAGE_MUTATION"))
        assertTrue(markerText.contains("cleanupEveryMutations=2"))
        assertTrue(markerText.contains("cleanupSequence=1"))
        assertTrue(markerText.contains("lastDeletedRunDirectories=1"))
        assertTrue(markerText.contains("lastDeleteFailures=0"))
        assertTrue(markerText.contains("lastCleanupSuccess=true"))
        assertTrue(markerText.contains("storageBytesAfter="))
    }

    @Test
    fun cleanupMarkerRecordsFailClosedDeleteFailures() {
        val storageRoot = Files.createTempDirectory("agent-runtime-cleanup-fail-").toFile()
        val cleanupMarker = File(storageRoot, RELIABILITY_CLEANUP_MARKER_FILE)
        val scheduler = ReliabilityCleanupScheduler(
            root = storageRoot,
            policy = ReliabilityRetentionPolicy(
                maxRunDirectories = 1,
                maxStartupScanRunDirectories = 10,
                maxStorageBytes = null,
                cleanupEveryMutations = 1,
            ),
            cleanup = {
                ReliabilityCleanupResult(
                    deletedRunDirectories = 0,
                    storageBytesAfter = 0L,
                    deleteFailures = listOf(
                        ReliabilityDeleteFailure(
                            runId = "stuck-run",
                            reason = "run_directory_still_present_after_delete",
                        ),
                    ),
                )
            },
        )

        scheduler.onStorageMutation()
        val markerText = cleanupMarker.readText()
        assertTrue(markerText.contains("lastCleanupSuccess=false"))
        assertTrue(markerText.contains("lastDeleteFailures=1"))
        assertTrue(markerText.contains("lastDeleteFailedRunIds=stuck-run"))
        assertTrue(markerText.contains("lastDeleteFailureReasons=stuck-run:run_directory_still_present_after_delete"))
        // Fail-closed: never claim success when failures are present.
        assertFalse(markerText.contains("lastCleanupSuccess=true"))
    }

    private fun storageSnapshot(
        runId: String,
        status: RuntimeRunStatus,
        updatedAtEpochMillis: Long,
    ): Pair<RuntimeRunSnapshot, RuntimeRunEnvelope> =
        RuntimeRunSnapshot(
            runId = runId,
            sessionId = "session-1",
            status = status,
            updatedAtEpochMillis = updatedAtEpochMillis,
        ) to RuntimeRunEnvelope(
            runId = runId,
            requestId = "$runId-request",
            sessionId = "session-1",
            status = status,
            phase = RuntimeRunPhase.TERMINAL,
            createdAtEpochMillis = updatedAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )

    private fun FileBackedReliabilityStorage.saveRun(
        runState: Pair<RuntimeRunSnapshot, RuntimeRunEnvelope>,
    ) {
        saveRun(runState.first, runState.second)
    }
}

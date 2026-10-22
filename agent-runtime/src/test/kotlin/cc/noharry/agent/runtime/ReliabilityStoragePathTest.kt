package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.reliability.RuntimeRunEnvelope
import cc.noharry.agent.kernel.api.reliability.RuntimeRunPhase
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import cc.noharry.agent.kernel.core.RUNS_DIR
import cc.noharry.agent.kernel.core.RUN_FILE
import cc.noharry.agent.kernel.core.encodeFileName

class ReliabilityStoragePathTest {
    @Test
    fun runIdCannotEscapeStorageRunDirectory() {
        val storageRoot = Files.createTempDirectory("agent-runtime-path-").toFile()
        val escaped = File(storageRoot.parentFile, "${storageRoot.name}-escaped")
        val unsafeRunId = "../../${escaped.name}"
        val storage = FileBackedReliabilityStorage(storageRoot)

        storage.saveRun(storageSnapshot(unsafeRunId))

        val encodedRunDir = File(File(storageRoot, RUNS_DIR), encodeFileName(unsafeRunId))
        assertEquals(RuntimeRunStatus.COMPLETED, storage.readSnapshot(unsafeRunId)?.status)
        assertTrue(File(encodedRunDir, RUN_FILE).isFile)
        assertFalse(escaped.exists())
    }

    private fun storageSnapshot(
        runId: String,
    ): Pair<RuntimeRunSnapshot, RuntimeRunEnvelope> =
        RuntimeRunSnapshot(
            runId = runId,
            sessionId = "session-1",
            status = RuntimeRunStatus.COMPLETED,
            updatedAtEpochMillis = 1L,
        ) to RuntimeRunEnvelope(
            runId = runId,
            requestId = "$runId-request",
            sessionId = "session-1",
            status = RuntimeRunStatus.COMPLETED,
            phase = RuntimeRunPhase.TERMINAL,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )

    private fun FileBackedReliabilityStorage.saveRun(
        runState: Pair<RuntimeRunSnapshot, RuntimeRunEnvelope>,
    ) {
        saveRun(runState.first, runState.second)
    }
}

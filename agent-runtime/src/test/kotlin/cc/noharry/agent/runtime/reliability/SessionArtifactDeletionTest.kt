@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.reliability

import cc.noharry.agent.kernel.core.state.*
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.spi.reliability.*
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SessionArtifactDeletionTest {
    @Test fun sessionDeletionRemovesRunsEvidenceAndLoopFilesWithoutTouchingOtherSessions() {
        val root = Files.createTempDirectory("delete-artifacts-").toFile()
        val storage = FileBackedReliabilityStorage(root)
        val history = FileBackedLoopHistoryStore(root)
        saveRun(storage, "removed", "one")
        saveRun(storage, "removed", "two")
        saveRun(storage, "kept", "three")
        history.save("one", listOf(ModelMessage("user", "private history")))
        assertEquals(setOf("one", "two"), storage.deleteSession("removed"))
        assertTrue(storage.deleteSession("removed").isEmpty())
        assertNull(storage.readSnapshot("one"))
        assertNull(storage.readEvidence("evidence-one"))
        assertTrue(File(root, "runs").listFiles().orEmpty().none { it.name == encodeFileName("one") })
        assertNotNull(storage.readSnapshot("three"))
        assertNotNull(storage.readEvidence("evidence-three"))
        history.delete("one")
        assertNull(history.read("one"))
    }

    @Test fun aKnownRunCanBeDeletedWhenItsMetadataIsDamaged() {
        val root = Files.createTempDirectory("delete-damaged-run-").toFile()
        val storage = FileBackedReliabilityStorage(root)
        saveRun(storage, "removed", "one")
        File(root, "runs/${encodeFileName("one")}/run.json").writeText("{invalid")
        assertEquals(setOf("one"), storage.deleteSession("removed", setOf("one")))
        assertNull(storage.readEvidence("evidence-one"))
    }

    @Test fun failedDeletesAreReportedAndCanBeRetried() {
        val root = Files.createTempDirectory("delete-failing-run-").toFile()
        val storage = FileBackedReliabilityStorage(root)
        saveRun(storage, "removed", "one")
        val failing = FileBackedReliabilityStorage(root, fileDeleter = ReliabilityFileDeleter { false })
        assertThrows(IllegalStateException::class.java) { failing.deleteSession("removed") }
        assertNotNull(storage.readSnapshot("one"))
        assertEquals(setOf("one"), storage.deleteSession("removed"))
    }

    @Test fun loopHistoryDeletionClearsDiskAndMemoryAndNeverFollowsNestedLinks() {
        val root = Files.createTempDirectory("delete-loop-history-").toFile()
        val store = FileBackedLoopHistoryStore(root)
        store.save("one", listOf(ModelMessage("user", "private history")))
        store.delete("one")
        store.delete("one")
        assertNull(store.read("one"))
        assertNull(FileBackedLoopHistoryStore(root).read("one"))
        val outside = Files.createTempDirectory("retained-workspace-").toFile()
        val retained = File(outside, "keep.txt").apply { writeText("keep") }
        val directory = File(root, "with-link").apply { mkdirs() }
        Files.createSymbolicLink(File(directory, "workspace").toPath(), outside.toPath())
        assertTrue(directory.deleteTreeWith())
        assertEquals("keep", retained.readText())
    }

    private fun saveRun(storage: FileBackedReliabilityStorage, session: String, run: String) {
        storage.saveRun(RuntimeRunSnapshot(run, session, RuntimeRunStatus.COMPLETED, 1),
            RuntimeRunEnvelope(run, run, session, RuntimeRunStatus.COMPLETED, RuntimeRunPhase.TERMINAL, 1, 1))
        storage.saveEvidence(RuntimeEvidence("evidence-$run", run, RuntimeEvidenceType.RESULT_WRITTEN, "private evidence", 1))
    }
}

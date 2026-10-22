@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.RuntimeEvidenceConfig
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidence
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidenceType
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequestStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeRunEnvelope
import cc.noharry.agent.kernel.api.reliability.RuntimeRunPhase
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.runtime.probe.PROBE_COMPLETENESS_JSON
import cc.noharry.agent.runtime.probe.PROBES_JSONL
import cc.noharry.agent.runtime.probe.RUN_META_JSON
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import cc.noharry.agent.kernel.core.EVIDENCE_DIR
import cc.noharry.agent.kernel.core.PROTOCOL_REQUESTS_DIR
import cc.noharry.agent.kernel.core.PROTOCOL_RESOLUTIONS_DIR
import cc.noharry.agent.kernel.core.RELIABILITY_CLEANUP_MARKER_FILE
import cc.noharry.agent.kernel.core.RUNS_DIR
import cc.noharry.agent.kernel.core.RUN_FILE
import cc.noharry.agent.kernel.core.ReliabilityRetentionPolicy
import cc.noharry.agent.kernel.core.encodeFileName
import cc.noharry.agent.kernel.core.saturatingAdd
import cc.noharry.agent.kernel.core.DefaultReliabilityFileDeleter
import cc.noharry.agent.kernel.core.ReliabilityDirectoryLister
import cc.noharry.agent.kernel.core.ReliabilityFileDeleter
import cc.noharry.agent.kernel.core.deleteTreeWith
import cc.noharry.agent.kernel.core.isTerminalRunStatus
import cc.noharry.agent.kernel.core.measureStorageBytes

/**
 * Production retention automation independent of clear-data.
 *
 * Call chain (production):
 * [AgentRuntimeBuilder.runtimeEvidence] + [AgentRuntimeBuilder.reliabilityRetentionPolicy]
 * → [FileBackedReliabilityStorage] save* mutations
 * → [ReliabilityCleanupScheduler.onStorageMutation]
 * → enforceRetentionNow → deleteRunRecord
 *   (shared evidence/protocol first, then run dir only if shared cleanup succeeds)
 *
 * Explicitly independent of host clear-data wipe paths (do not invoke wipe helpers).
 */
class ReliabilityProbeRetentionProductionTest {

    @Test
    fun productionRetentionDeletesOldTerminalProbeSidecarsSharedRecordsKeepsNewestAndWaiting() =
        runBlocking {
            val root = Files.createTempDirectory("ret-prod-probe-").toFile()
            val unrelated = File(root.parentFile, "host-unrelated-credentials.json")
            try {
                unrelated.writeText("""{"apiKey":"do-not-delete"}""")
                val createPolicy = tightPolicy(maxRuns = 32)

                buildEvidenceRuntime(root, createPolicy)
                    .run(req("term-old", "finish terminal old", sessionId = "sess-term"))
                    .toList()
                val oldDir = requireRunDir(root, "term-old")
                assertProbeSidecarSetPresent(oldDir)

                // Shared evidence + protocol for the old terminal (must leave no orphans).
                val seed = FileBackedReliabilityStorage(root, createPolicy)
                seed.saveEvidence(storageEvidence("ev-old", "term-old"))
                seed.saveProtocolRequest(storageProtocol("pr-old", "term-old"))
                seed.saveProtocolResolution(
                    "pr-old",
                    "res-1",
                    storageProtocol("pr-old", "term-old", status = RuntimeProtocolRequestStatus.RESOLVED),
                )
                assertTrue(evidenceFile(root, "ev-old").isFile)
                assertTrue(protocolRequestFile(root, "pr-old").isFile)
                assertTrue(protocolResolutionFile(root, "pr-old", "res-1").isFile)

                buildEvidenceRuntime(root, createPolicy) { confirmationOnRun() }
                    .run(req("wait-conf", "needs confirm", sessionId = "sess-wait"))
                    .toList()
                val waitDir = requireRunDir(root, "wait-conf")
                assertEquals(
                    RuntimeRunStatus.WAITING_CONFIRMATION,
                    reloadStorage(root, createPolicy).readSnapshot("wait-conf")?.status,
                )

                buildEvidenceRuntime(root, createPolicy)
                    .run(req("term-new", "finish terminal new", sessionId = "sess-term"))
                    .toList()
                val newDir = requireRunDir(root, "term-new")
                assertProbeSidecarSetPresent(newDir)

                val retainPolicy = tightPolicy(maxRuns = 2)
                val retentionStorage = FileBackedReliabilityStorage(root, retainPolicy)
                val newest = requireNotNull(retentionStorage.readSnapshot("term-new"))
                val newestEnv = requireNotNull(retentionStorage.readEnvelope("term-new"))
                retentionStorage.saveRun(newest, newestEnv)

                assertNull(retentionStorage.readSnapshot("term-old"))
                assertFalse(oldDir.exists())
                assertNull(retentionStorage.readEvidence("ev-old"))
                assertFalse(evidenceFile(root, "ev-old").exists())
                assertFalse(protocolRequestFile(root, "pr-old").exists())
                assertFalse(protocolResolutionFile(root, "pr-old", "res-1").exists())
                assertEquals(RuntimeRunStatus.COMPLETED, retentionStorage.readSnapshot("term-new")?.status)
                assertEquals(
                    RuntimeRunStatus.WAITING_CONFIRMATION,
                    retentionStorage.readSnapshot("wait-conf")?.status,
                )
                assertTrue(waitDir.isDirectory)
                assertProbeSidecarSetPresent(newDir)

                val markerText = File(root, RELIABILITY_CLEANUP_MARKER_FILE).readText()
                assertTrue(markerText.contains("lastCleanupSuccess=true"))
                assertTrue(markerText.contains("lastDeleteFailures=0"))
                assertTrue(unrelated.isFile)
                assertEquals("""{"apiKey":"do-not-delete"}""", unrelated.readText())
            } finally {
                root.deleteRecursively()
                unrelated.delete()
            }
        }

    @Test
    fun productionRetentionSharedEvidenceDeleteFailureIsDeterministicFailClosedAndRetriable() {
        val root = Files.createTempDirectory("ret-prod-shared-fail-").toFile()
        try {
            val policy = tightPolicy(maxRuns = 1)
            val blockEvidence = AtomicBoolean(true)
            val targetEvidence = evidenceFile(root, "ev-old")
            val failingDeleter = blockingDeleter(blockEvidence, targetEvidence)
            val storage = FileBackedReliabilityStorage(root, policy, fileDeleter = failingDeleter)
            seedOldTerminalWithSharedRecords(root, storage, targetEvidence)
            val oldDir = requireRunDir(root, "old-finished")

            storage.saveRun(snap("new-finished", RuntimeRunStatus.COMPLETED, 2L))
            assertSharedDeleteFailureMarker(
                File(root, RELIABILITY_CLEANUP_MARKER_FILE).readText(),
                oldDir,
                targetEvidence,
                storage,
            )

            blockEvidence.set(false)
            assertRetryCompletesSharedAndRunDirDelete(root, policy, failingDeleter, oldDir, targetEvidence)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun blockingDeleter(block: AtomicBoolean, blocked: File): ReliabilityFileDeleter =
        ReliabilityFileDeleter { file ->
            if (block.get() && file.canonicalPath == blocked.canonicalPath) false else file.delete()
        }

    private fun seedOldTerminalWithSharedRecords(
        root: File,
        storage: FileBackedReliabilityStorage,
        targetEvidence: File,
    ) {
        storage.saveRun(snap("old-finished", RuntimeRunStatus.COMPLETED, 1L))
        storage.saveEvidence(storageEvidence("ev-old", "old-finished"))
        storage.saveProtocolRequest(storageProtocol("pr-old", "old-finished"))
        assertTrue(targetEvidence.isFile)
        assertTrue(protocolRequestFile(root, "pr-old").isFile)
    }

    private fun assertSharedDeleteFailureMarker(
        marker: String,
        oldDir: File,
        targetEvidence: File,
        storage: FileBackedReliabilityStorage,
    ) {
        assertTrue(marker.contains("cleanupMode=ON_STORAGE_MUTATION"))
        assertTrue(oldDir.isDirectory)
        assertTrue(targetEvidence.isFile)
        assertTrue(marker.contains("lastCleanupSuccess=false"))
        assertTrue(Regex("lastDeleteFailures=[1-9]").containsMatchIn(marker))
        assertTrue(marker.contains("lastDeleteFailedRunIds=") && marker.contains("old-finished"))
        assertTrue(
            marker.contains("shared_json_delete_returned_false") ||
                (marker.contains("lastDeleteFailureReasons=") && marker.contains("shared_json")),
        )
        assertEquals(RuntimeRunStatus.COMPLETED, storage.readSnapshot("new-finished")?.status)
        assertEquals(RuntimeRunStatus.COMPLETED, storage.readSnapshot("old-finished")?.status)
    }

    private fun assertRetryCompletesSharedAndRunDirDelete(
        root: File,
        policy: ReliabilityRetentionPolicy,
        failingDeleter: ReliabilityFileDeleter,
        oldDir: File,
        targetEvidence: File,
    ) {
        val retryStorage = FileBackedReliabilityStorage(root, policy, fileDeleter = failingDeleter)
        val newest = requireNotNull(retryStorage.readSnapshot("new-finished"))
        val newestEnv = requireNotNull(retryStorage.readEnvelope("new-finished"))
        retryStorage.saveRun(newest, newestEnv)
        assertNull(retryStorage.readSnapshot("old-finished"))
        assertFalse(oldDir.exists())
        assertFalse(targetEvidence.exists())
        assertFalse(protocolRequestFile(root, "pr-old").exists())
        val successMarker = File(root, RELIABILITY_CLEANUP_MARKER_FILE).readText()
        assertTrue(successMarker.contains("lastCleanupSuccess=true"))
        assertTrue(successMarker.contains("lastDeleteFailures=0"))
        assertEquals(RuntimeRunStatus.COMPLETED, retryStorage.readSnapshot("new-finished")?.status)
    }

    @Test
    fun productionRetentionReloadAfterSecondProcessBuildStillHonorsPolicy() = runBlocking {
        val root = Files.createTempDirectory("ret-prod-reload-").toFile()
        try {
            val createPolicy = tightPolicy(maxRuns = 32)
            buildEvidenceRuntime(root, createPolicy).run(req("a", "first")).toList()
            assertProbeSidecarSetPresent(requireRunDir(root, "a"))
            buildEvidenceRuntime(root, createPolicy).run(req("b", "second")).toList()
            assertProbeSidecarSetPresent(requireRunDir(root, "b"))

            val retainPolicy = tightPolicy(maxRuns = 1)
            val retentionStorage = FileBackedReliabilityStorage(root, retainPolicy)
            val newest = requireNotNull(retentionStorage.readSnapshot("b"))
            val newestEnv = requireNotNull(retentionStorage.readEnvelope("b"))
            retentionStorage.saveRun(newest, newestEnv)

            val reloaded = reloadStorage(root, retainPolicy)
            assertNull(reloaded.readSnapshot("a"))
            assertFalse(File(File(root, RUNS_DIR), encodeFileName("a")).exists())
            assertEquals(RuntimeRunStatus.COMPLETED, reloaded.readSnapshot("b")?.status)
            assertProbeSidecarSetPresent(requireRunDir(root, "b"))
            assertTrue(
                File(root, RELIABILITY_CLEANUP_MARKER_FILE).readText()
                    .contains("cleanupMode=ON_STORAGE_MUTATION"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun productionRetentionSharedPathNotDirectoryIsFailClosedAndRetriable() {
        val root = Files.createTempDirectory("ret-prod-shared-not-dir-").toFile()
        try {
            val policy = tightPolicy(maxRuns = 1)
            val storage = FileBackedReliabilityStorage(root, policy)
            storage.saveRun(snap("old-finished", RuntimeRunStatus.COMPLETED, 1L))
            val oldDir = requireRunDir(root, "old-finished")

            // Corrupt shared evidence root: ordinary file instead of directory.
            val evidenceRoot = File(root, EVIDENCE_DIR)
            if (evidenceRoot.exists()) {
                evidenceRoot.deleteRecursively()
            }
            evidenceRoot.writeText("not-a-directory")
            assertTrue(evidenceRoot.isFile)

            storage.saveRun(snap("new-finished", RuntimeRunStatus.COMPLETED, 2L))

            val marker = File(root, RELIABILITY_CLEANUP_MARKER_FILE).readText()
            assertTrue(oldDir.isDirectory)
            assertTrue(marker.contains("lastCleanupSuccess=false"))
            assertTrue(Regex("lastDeleteFailures=[1-9]").containsMatchIn(marker))
            assertTrue(marker.contains("lastDeleteFailedRunIds=") && marker.contains("old-finished"))
            assertTrue(
                "must diagnose not-directory: $marker",
                marker.contains("shared_path_not_directory"),
            )
            assertEquals(RuntimeRunStatus.COMPLETED, storage.readSnapshot("new-finished")?.status)
            assertEquals(RuntimeRunStatus.COMPLETED, storage.readSnapshot("old-finished")?.status)

            // Restore a real evidence directory and retry retention.
            assertTrue(evidenceRoot.delete() || !evidenceRoot.exists())
            evidenceRoot.mkdirs()
            val retry = FileBackedReliabilityStorage(root, policy)
            val newest = requireNotNull(retry.readSnapshot("new-finished"))
            val newestEnv = requireNotNull(retry.readEnvelope("new-finished"))
            retry.saveRun(newest, newestEnv)
            assertNull(retry.readSnapshot("old-finished"))
            assertFalse(oldDir.exists())
            val ok = File(root, RELIABILITY_CLEANUP_MARKER_FILE).readText()
            assertTrue(ok.contains("lastCleanupSuccess=true"))
            assertTrue(ok.contains("lastDeleteFailures=0"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteTreeWithListFilesNullDoesNotTreatDirectoryAsEmpty() {
        val root = Files.createTempDirectory("ret-delete-tree-null-").toFile()
        try {
            val dir = File(root, "run-dir")
            assertTrue(dir.mkdirs())
            val child = File(dir, "keep-me.txt")
            child.writeText("x")
            val nullLister = ReliabilityDirectoryLister { null }
            assertFalse(
                "listFiles==null must fail closed",
                dir.deleteTreeWith(DefaultReliabilityFileDeleter, nullLister),
            )
            assertTrue(dir.isDirectory)
            assertTrue(child.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun measureStorageBytesListFailureIsIncompleteNotHugeSentinel() {
        val root = Files.createTempDirectory("ret-storage-bytes-").toFile()
        try {
            assertTrue(root.isDirectory)
            File(root, "a.bin").writeBytes(ByteArray(8))
            val nullLister = ReliabilityDirectoryLister { null }
            val m = root.measureStorageBytes(nullLister)
            assertFalse(m.complete)
            assertTrue(m.failures.any { it.startsWith("storage_bytes_list_failed:") })
            // Incomplete must not encode as a huge comparable capacity.
            assertTrue(m.bytes < (1L shl 40))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun productionRetentionIncompleteStorageBytesDoesNotDeleteRuns() {
        val root = Files.createTempDirectory("ret-disk-incomplete-").toFile()
        try {
            val policy = diskPolicy(maxRuns = 32, maxBytes = 50L * 1024L * 1024L)
            val poison = File(root, "misc-cache").also {
                assertTrue(it.mkdirs())
                File(it, "blob.bin").writeBytes(ByteArray(16))
            }
            val storage = FileBackedReliabilityStorage(
                root = root,
                retentionPolicy = policy,
                directoryLister = poisonNullLister(poison),
            )
            storage.saveRun(snap("old-finished", RuntimeRunStatus.COMPLETED, 1L))
            val oldDir = requireRunDir(root, "old-finished")
            storage.saveRun(snap("new-finished", RuntimeRunStatus.COMPLETED, 2L))
            assertIncompleteDiskMeasureMarker(
                File(root, RELIABILITY_CLEANUP_MARKER_FILE).readText(),
                oldDir,
                storage,
            )
            assertBothRunsKeptAfterCompleteRemeasure(root, policy, oldDir)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun diskPolicy(maxRuns: Int, maxBytes: Long?): ReliabilityRetentionPolicy =
        ReliabilityRetentionPolicy(
            maxRunDirectories = maxRuns,
            maxStartupScanRunDirectories = 40,
            maxStorageBytes = maxBytes,
            cleanupEveryMutations = 1,
        )

    private fun poisonNullLister(poison: File): ReliabilityDirectoryLister =
        ReliabilityDirectoryLister { dir ->
            if (dir.canonicalPath == poison.canonicalPath) null else dir.listFiles()
        }

    private fun assertIncompleteDiskMeasureMarker(
        marker: String,
        oldDir: File,
        storage: FileBackedReliabilityStorage,
    ) {
        assertTrue(oldDir.isDirectory)
        assertEquals(RuntimeRunStatus.COMPLETED, storage.readSnapshot("old-finished")?.status)
        assertEquals(RuntimeRunStatus.COMPLETED, storage.readSnapshot("new-finished")?.status)
        assertTrue(marker.contains("lastCleanupSuccess=false"))
        assertTrue(Regex("lastDeleteFailures=[1-9]").containsMatchIn(marker))
        assertTrue(marker.contains("storage_bytes_list_failed"))
        assertFalse(
            marker.contains("lastCleanupSuccess=true") && marker.contains("lastDeleteFailures=0"),
        )
    }

    private fun assertBothRunsKeptAfterCompleteRemeasure(
        root: File,
        policy: ReliabilityRetentionPolicy,
        oldDir: File,
    ) {
        val okStorage = FileBackedReliabilityStorage(root, policy)
        val newest = requireNotNull(okStorage.readSnapshot("new-finished"))
        val newestEnv = requireNotNull(okStorage.readEnvelope("new-finished"))
        okStorage.saveRun(newest, newestEnv)
        assertEquals(RuntimeRunStatus.COMPLETED, okStorage.readSnapshot("old-finished")?.status)
        assertEquals(RuntimeRunStatus.COMPLETED, okStorage.readSnapshot("new-finished")?.status)
        assertTrue(oldDir.isDirectory)
        val okMarker = File(root, RELIABILITY_CLEANUP_MARKER_FILE).readText()
        assertTrue(okMarker.contains("lastCleanupSuccess=true"))
        assertTrue(okMarker.contains("lastDeleteFailures=0"))
    }

    @Test
    fun productionRetentionCompleteDiskLimitDeletesOldestTerminal() {
        val root = Files.createTempDirectory("ret-disk-complete-").toFile()
        try {
            val policy = ReliabilityRetentionPolicy(
                maxRunDirectories = 32,
                maxStartupScanRunDirectories = 40,
                maxStorageBytes = 400L,
                cleanupEveryMutations = 1,
            )
            val storage = FileBackedReliabilityStorage(root, policy)
            storage.saveRun(snap("old-finished", RuntimeRunStatus.COMPLETED, 1L))
            storage.saveEvidence(
                storageEvidence("ev-bulky", "old-finished").copy(
                    summary = "x".repeat(2_000),
                ),
            )
            val oldDir = requireRunDir(root, "old-finished")
            storage.saveRun(snap("new-finished", RuntimeRunStatus.COMPLETED, 2L))

            assertNull(storage.readSnapshot("old-finished"))
            assertFalse(oldDir.exists())
            assertEquals(RuntimeRunStatus.COMPLETED, storage.readSnapshot("new-finished")?.status)
            val marker = File(root, RELIABILITY_CLEANUP_MARKER_FILE).readText()
            assertTrue(marker.contains("lastCleanupSuccess=true"))
            assertTrue(marker.contains("lastDeleteFailures=0"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun saturatingAddDoesNotOverflow() {
        assertEquals(Long.MAX_VALUE, saturatingAdd(Long.MAX_VALUE, 1L))
        assertEquals(Long.MAX_VALUE, saturatingAdd(Long.MAX_VALUE - 10L, 100L))
        assertEquals(30L, saturatingAdd(10L, 20L))
        val deep = Files.createTempDirectory("ret-sat-nest-").toFile()
        try {
            // Nested files whose sizes would not overflow; measurement still complete.
            File(deep, "a.bin").writeBytes(ByteArray(8))
            File(deep, "b.bin").writeBytes(ByteArray(8))
            val m = deep.measureStorageBytes()
            assertTrue(m.complete)
            assertEquals(16L, m.bytes)
        } finally {
            deep.deleteRecursively()
        }
    }

    private fun tightPolicy(maxRuns: Int): ReliabilityRetentionPolicy =
        ReliabilityRetentionPolicy(
            maxRunDirectories = maxRuns,
            maxStartupScanRunDirectories = 40,
            maxStorageBytes = null,
            cleanupEveryMutations = 1,
        )

    private fun buildEvidenceRuntime(
        root: File,
        policy: ReliabilityRetentionPolicy,
        configure: AgentRuntimeBuilder.() -> Unit = {},
    ): DefaultAgentRuntime {
        val builder = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""),
                            ModelEvent.Completed,
                        ),
                    ),
                ),
            )
            .runtimeEvidence(
                RuntimeEvidenceConfig(
                    storageRoot = root,
                    enabledProbePackIds = setOf("bench_core"),
                    casePackId = "none",
                ),
            )
            .reliabilityRetentionPolicy(policy)
        builder.configure()
        return builder.build() as DefaultAgentRuntime
    }

    private fun reloadStorage(root: File, policy: ReliabilityRetentionPolicy): FileBackedReliabilityStorage =
        FileBackedReliabilityStorage(root, policy)

    private fun req(
        id: String,
        input: String,
        sessionId: String = "sess-ret",
    ): AgentRequest =
        AgentRequest(requestId = id, sessionId = sessionId, input = input)

    private fun snap(
        runId: String,
        status: RuntimeRunStatus,
        updatedAt: Long,
    ): Pair<RuntimeRunSnapshot, RuntimeRunEnvelope> {
        val phase = if (status.isTerminalRunStatus()) {
            RuntimeRunPhase.TERMINAL
        } else {
            RuntimeRunPhase.WAITING_CONFIRMATION
        }
        return RuntimeRunSnapshot(
            runId = runId,
            sessionId = "sess-ret",
            status = status,
            updatedAtEpochMillis = updatedAt,
        ) to RuntimeRunEnvelope(
            runId = runId,
            requestId = "$runId-req",
            sessionId = "sess-ret",
            status = status,
            phase = phase,
            createdAtEpochMillis = updatedAt,
            updatedAtEpochMillis = updatedAt,
        )
    }

    private fun storageEvidence(evidenceId: String, runId: String): RuntimeEvidence =
        RuntimeEvidence(
            evidenceId = evidenceId,
            runId = runId,
            type = RuntimeEvidenceType.RESULT_WRITTEN,
            summary = "retention-test",
            createdAtEpochMillis = 1L,
        )

    private fun storageProtocol(
        protocolRequestId: String,
        runId: String,
        status: RuntimeProtocolRequestStatus = RuntimeProtocolRequestStatus.WAITING,
    ): RuntimeProtocolRequest =
        RuntimeProtocolRequest(
            protocolRequestId = protocolRequestId,
            runId = runId,
            sessionId = "sess-ret",
            status = status,
            kind = "test",
            payloadJson = """{"k":"v"}""",
            createdAtEpochMillis = 1L,
        )

    private fun FileBackedReliabilityStorage.saveRun(
        pair: Pair<RuntimeRunSnapshot, RuntimeRunEnvelope>,
    ) {
        saveRun(pair.first, pair.second)
    }

    private fun requireRunDir(root: File, runId: String): File {
        val dir = File(File(root, RUNS_DIR), encodeFileName(runId))
        assertTrue(
            "expected run dir for $runId under $root; present=${
                File(root, RUNS_DIR).listFiles()?.map { it.name }
            }",
            dir.isDirectory,
        )
        return dir
    }

    private fun assertProbeSidecarSetPresent(runDir: File) {
        assertTrue(File(runDir, PROBES_JSONL).isFile)
        assertTrue(File(runDir, PROBE_COMPLETENESS_JSON).isFile)
        assertTrue(File(runDir, RUN_META_JSON).isFile)
        assertTrue(File(runDir, RUN_FILE).isFile)
    }

    private fun evidenceFile(root: File, evidenceId: String): File =
        File(File(root, EVIDENCE_DIR), "${encodeFileName(evidenceId)}.json")

    private fun protocolRequestFile(root: File, protocolRequestId: String): File =
        File(File(root, PROTOCOL_REQUESTS_DIR), "${encodeFileName(protocolRequestId)}.json")

    private fun protocolResolutionFile(
        root: File,
        protocolRequestId: String,
        requestId: String,
    ): File =
        File(
            File(root, PROTOCOL_RESOLUTIONS_DIR),
            "${encodeFileName(protocolRequestId)}-${encodeFileName(requestId)}.json",
        )
}

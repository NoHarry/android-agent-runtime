package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.reliability.OpenConfusion
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidence
import cc.noharry.agent.kernel.api.reliability.RuntimeHandoff
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequestStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunEnvelope
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeTraceEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeWorkpad
import java.io.File
import cc.noharry.agent.kernel.core.EVIDENCE_DIR
import cc.noharry.agent.kernel.core.EVIDENCE_JSONL_FILE
import cc.noharry.agent.kernel.core.HANDOFF_FILE
import cc.noharry.agent.kernel.core.OPEN_CONFUSION_FILE
import cc.noharry.agent.kernel.core.PROTOCOL_REQUESTS_DIR
import cc.noharry.agent.kernel.core.PROTOCOL_REQUESTS_JSONL_FILE
import cc.noharry.agent.kernel.core.PROTOCOL_RESOLUTIONS_DIR
import cc.noharry.agent.kernel.core.RESULT_FILE
import cc.noharry.agent.kernel.core.RUNS_DIR
import cc.noharry.agent.kernel.core.RUN_FILE
import cc.noharry.agent.kernel.core.ReliabilityRetentionPolicy
import cc.noharry.agent.kernel.core.ReliabilityStorage
import cc.noharry.agent.kernel.core.SharedJsonOwnership
import cc.noharry.agent.kernel.core.TRACE_JSONL_FILE
import cc.noharry.agent.kernel.core.WORKPAD_FILE
import cc.noharry.agent.kernel.core.decodeOrNull
import cc.noharry.agent.kernel.core.encodeFileName
import cc.noharry.agent.kernel.core.DefaultReliabilityDirectoryLister
import cc.noharry.agent.kernel.core.DefaultReliabilityFileDeleter
import cc.noharry.agent.kernel.core.ReliabilityDirectoryLister
import cc.noharry.agent.kernel.core.ReliabilityFileDeleter
import cc.noharry.agent.kernel.core.appendReliabilityLine
import cc.noharry.agent.kernel.core.deleteSharedJsonOwnedByRun
import cc.noharry.agent.kernel.core.deleteTreeWith
import cc.noharry.agent.kernel.core.measureStorageBytes
import cc.noharry.agent.kernel.core.readLinesOrEmpty
import cc.noharry.agent.kernel.core.readReliabilityTextOrNull
import cc.noharry.agent.kernel.core.writeReliabilityText

internal class FileBackedReliabilityStorage(
    private val root: File,
    private val retentionPolicy: ReliabilityRetentionPolicy = ReliabilityRetentionPolicy(),
    private val hardeningPolicy: ReliabilityStorageHardeningPolicy = ReliabilityStorageHardeningPolicy(),
    /**
     * Internal production default [DefaultReliabilityFileDeleter].
     * Tests may inject a failing deleter for deterministic fail-closed coverage (not public API).
     */
    private val fileDeleter: ReliabilityFileDeleter = DefaultReliabilityFileDeleter,
    /** Internal production default [DefaultReliabilityDirectoryLister]; test-injectable. */
    private val directoryLister: ReliabilityDirectoryLister = DefaultReliabilityDirectoryLister,
) : ReliabilityStorage {
    private val storageLock = ReliabilityStorageFileLock(root, hardeningPolicy.fileLockMode)
    private val cleanupScheduler = ReliabilityCleanupScheduler(
        root = root,
        policy = retentionPolicy,
        cleanup = ::enforceRetentionNow,
    )

    init {
        hardeningPolicy.validateRoot(root)
        hardeningPolicy.writeMarker(root)
    }

    override fun saveRun(snapshot: RuntimeRunSnapshot, envelope: RuntimeRunEnvelope) {
        storageLock.withLock {
            runFile(snapshot.runId, RUN_FILE).writeReliabilityText(
                ReliabilityJsonCodecs.encodeRun(snapshot, envelope),
            )
            cleanupScheduler.onStorageMutation()
        }
    }

    override fun readSnapshot(runId: String): RuntimeRunSnapshot? = storageLock.withReadLock {
        runFile(runId, RUN_FILE).decodeOrNull(ReliabilityJsonCodecs::decodeSnapshot)
    }

    override fun readEnvelope(runId: String): RuntimeRunEnvelope? = storageLock.withReadLock {
        runFile(runId, RUN_FILE).decodeOrNull(ReliabilityJsonCodecs::decodeEnvelope)
    }

    override fun latestSnapshot(sessionId: String, status: RuntimeRunStatus): RuntimeRunSnapshot? = storageLock.withReadLock {
        scanRunDirs()
            .mapNotNull { File(it, RUN_FILE).decodeOrNull(ReliabilityJsonCodecs::decodeSnapshot) }
            .filter { it.sessionId == sessionId && it.status == status }
            .maxByOrNull { it.updatedAtEpochMillis }
    }

    override fun recentRunsForSession(sessionId: String, limit: Int): List<RuntimeRunSnapshot> = storageLock.withReadLock {
        scanRunDirs()
            .mapNotNull { File(it, RUN_FILE).decodeOrNull(ReliabilityJsonCodecs::decodeSnapshot) }
            .filter { it.sessionId == sessionId }
            .sortedByDescending { it.updatedAtEpochMillis }
            .take(limit)
    }

    override fun saveWorkpad(workpad: RuntimeWorkpad) {
        storageLock.withLock {
            runFile(workpad.runId, WORKPAD_FILE).writeReliabilityText(
                ReliabilityJsonCodecs.encodeWorkpad(workpad),
            )
            cleanupScheduler.onStorageMutation()
        }
    }

    override fun readWorkpad(runId: String): RuntimeWorkpad? = storageLock.withReadLock {
        runFile(runId, WORKPAD_FILE).decodeOrNull(ReliabilityJsonCodecs::decodeWorkpad)
    }

    override fun saveResult(result: RuntimeResult) {
        storageLock.withLock {
            runFile(result.runId, RESULT_FILE).writeReliabilityText(
                ReliabilityJsonCodecs.encodeResult(result),
            )
            cleanupScheduler.onStorageMutation()
        }
    }

    override fun readResult(runId: String): RuntimeResult? = storageLock.withReadLock {
        runFile(runId, RESULT_FILE).decodeOrNull(ReliabilityJsonCodecs::decodeResult)
    }

    override fun saveHandoff(handoff: RuntimeHandoff) {
        storageLock.withLock {
            runFile(handoff.runId, HANDOFF_FILE).writeReliabilityText(
                ReliabilityJsonCodecs.encodeHandoff(handoff),
            )
            cleanupScheduler.onStorageMutation()
        }
    }

    override fun readHandoff(runId: String): RuntimeHandoff? = storageLock.withReadLock {
        runFile(runId, HANDOFF_FILE).decodeOrNull(ReliabilityJsonCodecs::decodeHandoff)
    }

    override fun saveOpenConfusion(confusion: OpenConfusion) {
        storageLock.withLock {
            runFile(confusion.runId, OPEN_CONFUSION_FILE).writeReliabilityText(
                ReliabilityJsonCodecs.encodeOpenConfusion(confusion),
            )
            cleanupScheduler.onStorageMutation()
        }
    }

    override fun readOpenConfusion(runId: String): OpenConfusion? = storageLock.withReadLock {
        runFile(runId, OPEN_CONFUSION_FILE).decodeOrNull(ReliabilityJsonCodecs::decodeOpenConfusion)
    }

    override fun saveEvidence(evidence: RuntimeEvidence) {
        storageLock.withLock {
            val encoded = ReliabilityJsonCodecs.encodeEvidence(evidence)
            evidenceFile(evidence.evidenceId).writeReliabilityText(encoded)
            runFile(evidence.runId, EVIDENCE_JSONL_FILE).appendReliabilityLine(encoded)
            cleanupScheduler.onStorageMutation()
        }
    }

    override fun readEvidence(evidenceId: String): RuntimeEvidence? = storageLock.withReadLock {
        evidenceFile(evidenceId).decodeOrNull(ReliabilityJsonCodecs::decodeEvidence)
    }

    override fun appendTrace(event: RuntimeTraceEvent) {
        storageLock.withLock {
            runFile(event.runId, TRACE_JSONL_FILE).appendReliabilityLine(
                ReliabilityJsonCodecs.encodeTrace(event),
            )
            cleanupScheduler.onStorageMutation()
        }
    }

    override fun readTrace(runId: String): List<RuntimeTraceEvent> = storageLock.withReadLock {
        runFile(runId, TRACE_JSONL_FILE).readLinesOrEmpty()
            .mapNotNull { line -> decodeOrNull(line, ReliabilityJsonCodecs::decodeTrace) }
    }

    override fun saveProtocolRequest(request: RuntimeProtocolRequest) {
        storageLock.withLock {
            val encoded = ReliabilityJsonCodecs.encodeProtocolRequest(request)
            protocolRequestFile(request.protocolRequestId).writeReliabilityText(encoded)
            runFile(request.runId, PROTOCOL_REQUESTS_JSONL_FILE).appendReliabilityLine(encoded)
            cleanupScheduler.onStorageMutation()
        }
    }

    override fun readProtocolRequest(protocolRequestId: String): RuntimeProtocolRequest? = storageLock.withReadLock {
        protocolRequestFile(protocolRequestId).decodeOrNull(ReliabilityJsonCodecs::decodeProtocolRequest)
    }

    override fun readPendingProtocolRequests(
        sessionId: String?,
        runId: String?,
    ): List<RuntimeProtocolRequest> = storageLock.withReadLock {
        protocolRequestsDir().listFiles().orEmpty()
            .mapNotNull { it.decodeOrNull(ReliabilityJsonCodecs::decodeProtocolRequest) }
            .filter { request ->
                request.status.isProtocolPending() &&
                    (runId == null || request.runId == runId) &&
                    (sessionId == null || request.sessionId == sessionId)
            }
    }

    override fun saveProtocolResolution(
        protocolRequestId: String,
        requestId: String,
        request: RuntimeProtocolRequest,
    ) {
        storageLock.withLock {
            protocolResolutionFile(protocolRequestId, requestId).writeReliabilityText(
                ReliabilityJsonCodecs.encodeProtocolRequest(request),
            )
            cleanupScheduler.onStorageMutation()
        }
    }

    private fun scanRunDirs(): List<File> =
        retentionRecords()
            .take(retentionPolicy.maxStartupScanRunDirectories)
            .map { it.directory }

    private fun enforceRetentionNow(): ReliabilityCleanupResult {
        val failures = mutableListOf<ReliabilityDeleteFailure>()
        // Count retention is independent of disk-size measurement completeness.
        val deletedByCount = enforceMaxRunDirectories(failures)
        val deletedByDisk = enforceDiskLimit(failures)
        val after = root.measureStorageBytes(directoryLister)
        if (!after.complete) {
            // Incomplete post-cleanup measurement must never report cleanup success.
            for (reason in after.failures) {
                failures += ReliabilityDeleteFailure(
                    runId = STORAGE_MEASUREMENT_FAILURE_RUN_ID,
                    reason = reason,
                )
            }
            if (after.failures.isEmpty()) {
                failures += ReliabilityDeleteFailure(
                    runId = STORAGE_MEASUREMENT_FAILURE_RUN_ID,
                    reason = "storage_bytes_incomplete",
                )
            }
        }
        return ReliabilityCleanupResult(
            deletedRunDirectories = deletedByCount + deletedByDisk,
            storageBytesAfter = after.bytes,
            deleteFailures = failures.toList(),
        )
    }

    private fun enforceMaxRunDirectories(failures: MutableList<ReliabilityDeleteFailure>): Int {
        var deleted = 0
        // Bound attempts so a stuck non-deletable terminal cannot spin forever.
        var attempts = 0
        val maxAttempts = retentionRecords().size + 2
        while (retentionRecords().size > retentionPolicy.maxRunDirectories && attempts < maxAttempts) {
            attempts += 1
            val deleteTarget = retentionRecords().asReversed()
                .firstOrNull { it.canDelete }
                ?: return deleted
            if (deleteRunRecord(deleteTarget, failures)) {
                deleted += 1
            } else {
                // Fail-closed: stop further count-based deletes for this pass; marker records failure.
                return deleted
            }
        }
        return deleted
    }

    /**
     * Disk-limit retention. When storage measurement is incomplete, **do not delete any run**
     * based on unknown capacity; record fail-closed failures instead.
     */
    private fun enforceDiskLimit(failures: MutableList<ReliabilityDeleteFailure>): Int {
        val maxBytes = retentionPolicy.maxStorageBytes ?: return 0
        var deleted = 0
        var attempts = 0
        val maxAttempts = retentionRecords().size + 2
        while (attempts < maxAttempts) {
            attempts += 1
            val measurement = root.measureStorageBytes(directoryLister)
            if (!measurement.complete) {
                for (reason in measurement.failures) {
                    failures += ReliabilityDeleteFailure(
                        runId = STORAGE_MEASUREMENT_FAILURE_RUN_ID,
                        reason = reason,
                    )
                }
                if (measurement.failures.isEmpty()) {
                    failures += ReliabilityDeleteFailure(
                        runId = STORAGE_MEASUREMENT_FAILURE_RUN_ID,
                        reason = "storage_bytes_incomplete",
                    )
                }
                // Incomplete capacity must never drive deletes.
                return deleted
            }
            if (measurement.bytes <= maxBytes) {
                return deleted
            }
            val records = retentionRecords()
            val newestRunId = records.firstOrNull()?.runId
            val deleteTarget = records.asReversed()
                .firstOrNull { it.canDelete && it.runId != newestRunId }
                ?: return deleted
            if (deleteRunRecord(deleteTarget, failures)) {
                deleted += 1
            } else {
                return deleted
            }
        }
        return deleted
    }

    private fun retentionRecords(): List<StoredRunRecord> =
        runsDir().listFiles().orEmpty()
            .filter { it.isDirectory }
            .map(::storedRunRecord)
            .sortedByDescending { it.updatedAtEpochMillis }

    private fun storedRunRecord(directory: File): StoredRunRecord {
        val snapshot = File(directory, RUN_FILE).decodeOrNull(ReliabilityJsonCodecs::decodeSnapshot)
        return StoredRunRecord(
            directory = directory,
            runId = snapshot?.runId ?: directory.name,
            status = snapshot?.status,
            updatedAtEpochMillis = snapshot?.updatedAtEpochMillis ?: directory.lastModified(),
        )
    }

    /**
     * Production retention delete:
     * 1) Delete shared evidence/protocol records for the run (checked; fail-closed).
     * 2) Only if shared cleanup fully succeeded, delete the run directory (probe sidecars).
     *
     * If shared cleanup fails, the run directory is **kept** so the next retention pass can
     * rediscover and retry. Never throws into the storage mutation path.
     */
    private fun deleteRunRecord(
        record: StoredRunRecord,
        failures: MutableList<ReliabilityDeleteFailure>,
    ): Boolean {
        return try {
            val sharedReasons = deleteSharedRecordsForRun(record.runId)
            if (sharedReasons.isNotEmpty()) {
                for (reason in sharedReasons) {
                    failures += ReliabilityDeleteFailure(runId = record.runId, reason = reason)
                }
                // Keep run directory so retention can retry after shared files are deletable.
                return false
            }
            val removed = try {
                record.directory.deleteTreeWith(fileDeleter, directoryLister)
            } catch (error: Exception) {
                failures += ReliabilityDeleteFailure(
                    runId = record.runId,
                    reason = "run_dir_delete_exception:${error.javaClass.simpleName}",
                )
                return false
            }
            if (!removed || record.directory.exists()) {
                failures += ReliabilityDeleteFailure(
                    runId = record.runId,
                    reason = "run_directory_still_present_after_delete",
                )
                false
            } else {
                true
            }
        } catch (error: Exception) {
            failures += ReliabilityDeleteFailure(
                runId = record.runId,
                reason = "delete_exception:${error.javaClass.simpleName}",
            )
            false
        }
    }

    private fun deleteSharedRecordsForRun(runId: String): List<String> {
        val reasons = mutableListOf<String>()
        reasons += evidenceDir().deleteSharedJsonOwnedByRun(
            targetRunId = runId,
            classify = { file -> classifyEvidenceOwnership(file) },
            fileDeleter = fileDeleter,
            directoryLister = directoryLister,
        )
        reasons += protocolRequestsDir().deleteSharedJsonOwnedByRun(
            targetRunId = runId,
            classify = { file -> classifyProtocolOwnership(file) },
            fileDeleter = fileDeleter,
            directoryLister = directoryLister,
        )
        reasons += protocolResolutionsDir().deleteSharedJsonOwnedByRun(
            targetRunId = runId,
            classify = { file -> classifyProtocolOwnership(file) },
            fileDeleter = fileDeleter,
            directoryLister = directoryLister,
        )
        return reasons
    }

    private fun classifyEvidenceOwnership(file: File): SharedJsonOwnership {
        val text = file.readReliabilityTextOrNull() ?: return SharedJsonOwnership.Unreadable
        val evidence = try {
            ReliabilityJsonCodecs.decodeEvidence(text)
        } catch (_: Exception) {
            return SharedJsonOwnership.Unreadable
        }
        // OwnedBy always; caller filters by target runId (NotOwnedByTarget not used here).
        return SharedJsonOwnership.OwnedBy(evidence.runId)
    }

    private fun classifyProtocolOwnership(file: File): SharedJsonOwnership {
        val text = file.readReliabilityTextOrNull() ?: return SharedJsonOwnership.Unreadable
        val request = try {
            ReliabilityJsonCodecs.decodeProtocolRequest(text)
        } catch (_: Exception) {
            return SharedJsonOwnership.Unreadable
        }
        return SharedJsonOwnership.OwnedBy(request.runId)
    }

    override fun readProtocolResolution(
        protocolRequestId: String,
        requestId: String,
    ): RuntimeProtocolRequest? = storageLock.withReadLock {
        protocolResolutionFile(protocolRequestId, requestId)
            .decodeOrNull(ReliabilityJsonCodecs::decodeProtocolRequest)
    }

    private fun runFile(runId: String, name: String): File = File(runDir(runId), name)

    private fun runDir(runId: String): File = File(runsDir(), encodeFileName(runId))

    private fun runsDir(): File = File(root, RUNS_DIR)

    private fun evidenceDir(): File = File(root, EVIDENCE_DIR)

    private fun evidenceFile(evidenceId: String): File =
        File(evidenceDir(), "${encodeFileName(evidenceId)}.json")

    private fun protocolRequestsDir(): File = File(root, PROTOCOL_REQUESTS_DIR)

    private fun protocolRequestFile(protocolRequestId: String): File =
        File(protocolRequestsDir(), "${encodeFileName(protocolRequestId)}.json")

    private fun protocolResolutionFile(protocolRequestId: String, requestId: String): File =
        File(
            protocolResolutionsDir(),
            "${encodeFileName(protocolRequestId)}-${encodeFileName(requestId)}.json",
        )

    private fun protocolResolutionsDir(): File = File(root, PROTOCOL_RESOLUTIONS_DIR)
}

/** Synthetic runId for storage measurement fail-closed records (not a real run directory). */
internal const val STORAGE_MEASUREMENT_FAILURE_RUN_ID = "*"

private fun RuntimeProtocolRequestStatus.isProtocolPending(): Boolean =
    this == RuntimeProtocolRequestStatus.CREATED || this == RuntimeProtocolRequestStatus.WAITING

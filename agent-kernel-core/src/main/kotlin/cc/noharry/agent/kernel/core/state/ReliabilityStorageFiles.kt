package cc.noharry.agent.kernel.core.state
import java.io.File
import java.util.Base64

fun File.writeReliabilityText(value: String) {
    parentFile?.mkdirs()
    writeText(value)
}

fun File.appendReliabilityLine(value: String) {
    parentFile?.mkdirs()
    appendText("$value\n")
}

fun <T> File.decodeOrNull(decode: (String) -> T): T? =
    readTextOrNull()?.let { decodeOrNull(it, decode) }

fun File.readLinesOrEmpty(): List<String> =
    if (isFile) readLines().filter { it.isNotBlank() } else emptyList()

/**
 * Production file delete seam (default = [File.delete]).
 * Tests may inject a failing implementation for deterministic fail-closed coverage.
 */
fun interface ReliabilityFileDeleter {
    fun delete(file: File): Boolean
}

val DefaultReliabilityFileDeleter: ReliabilityFileDeleter =
    ReliabilityFileDeleter { file -> file.delete() }

/**
 * Production directory listing seam (default = [File.listFiles]).
 * [File.listFiles] returns null when the path is not a directory or enumeration fails —
 * callers must never treat null as empty without checking path kind.
 */
fun interface ReliabilityDirectoryLister {
    fun listFiles(dir: File): Array<File>?
}

val DefaultReliabilityDirectoryLister: ReliabilityDirectoryLister =
    ReliabilityDirectoryLister { dir -> dir.listFiles() }

/** Classification of a shared JSON record relative to a target run. */
sealed class SharedJsonOwnership {
    data class OwnedBy(val runId: String) : SharedJsonOwnership()
    data object NotOwnedByTarget : SharedJsonOwnership()
    data object Unreadable : SharedJsonOwnership()
}

/**
 * Delete shared JSON files owned by [targetRunId] under this directory.
 *
 * Fail-closed path status (never treat list null as empty success):
 * - path does not exist → success (nothing to delete)
 * - path exists but is not a directory → `shared_path_not_directory`
 * - directory exists but [listFiles] returns null → `shared_dir_list_failed`
 * - list throws → `shared_dir_list_exception`
 * - [File.delete] false / exception / unreadable ownership → failure reasons
 *
 * Returns human-readable failure reasons (empty when fully successful).
 */
fun File.deleteSharedJsonOwnedByRun(
    targetRunId: String,
    classify: (File) -> SharedJsonOwnership,
    fileDeleter: ReliabilityFileDeleter = DefaultReliabilityFileDeleter,
    directoryLister: ReliabilityDirectoryLister = DefaultReliabilityDirectoryLister,
): List<String> {
    if (!exists()) {
        // Absent shared root is normal (no records ever written).
        return emptyList()
    }
    if (!isDirectory) {
        return listOf("shared_path_not_directory")
    }
    val listed = try {
        directoryLister.listFiles(this)
    } catch (error: Exception) {
        return listOf("shared_dir_list_exception:${error.javaClass.simpleName}")
    }
    if (listed == null) {
        // Directory exists but enumeration failed — never treat as empty.
        return listOf("shared_dir_list_failed")
    }
    val failures = mutableListOf<String>()
    val files = listed.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
    for (file in files) {
        val ownership = try {
            classify(file)
        } catch (error: Exception) {
            failures += "shared_json_classify_exception:${file.name}:${error.javaClass.simpleName}"
            continue
        }
        when (ownership) {
            is SharedJsonOwnership.NotOwnedByTarget -> Unit
            is SharedJsonOwnership.Unreadable -> {
                failures += "shared_json_unreadable:${file.name}"
            }
            is SharedJsonOwnership.OwnedBy -> {
                if (ownership.runId != targetRunId) continue
                val deleted = try {
                    fileDeleter.delete(file)
                } catch (error: Exception) {
                    failures += "shared_json_delete_exception:${file.name}:${error.javaClass.simpleName}"
                    continue
                }
                if (!deleted && file.exists()) {
                    failures += "shared_json_delete_returned_false:${file.name}"
                }
            }
        }
    }
    return failures
}

/**
 * Recursively delete a file tree using [fileDeleter] / [directoryLister].
 * Directory exists but [listFiles]==null → false (do not treat as empty and delete parent).
 */
fun File.deleteTreeWith(
    fileDeleter: ReliabilityFileDeleter = DefaultReliabilityFileDeleter,
    directoryLister: ReliabilityDirectoryLister = DefaultReliabilityDirectoryLister,
): Boolean {
    if (!exists()) return true
    if (isDirectory) {
        val children = try {
            directoryLister.listFiles(this)
        } catch (_: Exception) {
            return false
        }
        if (children == null) {
            // Enumeration failure: must not proceed as if the directory were empty.
            return false
        }
        for (child in children) {
            if (!child.deleteTreeWith(fileDeleter, directoryLister)) return false
        }
    }
    return try {
        val deleted = fileDeleter.delete(this)
        deleted || !exists()
    } catch (_: Exception) {
        false
    }
}

/**
 * Legacy helper — retention path uses [deleteSharedJsonOwnedByRun] only.
 * Still checks [File.delete] return value (never silently ignore false).
 */
fun File.deleteMatchingJson(matches: (File) -> Boolean): List<String> {
    val failures = mutableListOf<String>()
    if (!exists()) return emptyList()
    if (!isDirectory) return listOf("shared_path_not_directory")
    val listed = try {
        listFiles()
    } catch (error: Exception) {
        return listOf("shared_dir_list_exception:${error.javaClass.simpleName}")
    } ?: return listOf("shared_dir_list_failed")
    listed
        .filter { it.isFile && it.extension == "json" && matches(it) }
        .forEach { file ->
            val deleted = try {
                file.delete()
            } catch (error: Exception) {
                failures += "delete_exception:${file.name}:${error.javaClass.simpleName}"
                return@forEach
            }
            if (!deleted && file.exists()) {
                failures += "delete_returned_false:${file.name}"
            }
        }
    return failures
}

/**
 * Structured recursive storage measurement for retention decisions.
 *
 * Never encode "unknown capacity" as a huge [Long] that participates in maxStorageBytes
 * comparisons. When [complete] is false, callers must fail-closed and must not delete runs
 * based on [bytes].
 */
data class StorageBytesMeasurement(
    val bytes: Long,
    val complete: Boolean,
    val failures: List<String> = emptyList(),
)

/** Saturating non-negative add (no Long overflow wrap). */
fun saturatingAdd(a: Long, b: Long): Long {
    require(a >= 0L && b >= 0L) { "saturatingAdd expects non-negative operands" }
    val sum = a + b
    return if (sum < a) Long.MAX_VALUE else sum
}

/**
 * Measure recursive size under this path.
 *
 * - complete=true: [bytes] is accurate (saturated on overflow of partial sums)
 * - complete=false: enumeration failed; [failures] includes path + reason; [bytes] may be a
 *   partial lower bound only and must not drive disk-limit deletes
 */
fun File.measureStorageBytes(
    directoryLister: ReliabilityDirectoryLister = DefaultReliabilityDirectoryLister,
): StorageBytesMeasurement {
    if (!exists()) {
        return StorageBytesMeasurement(bytes = 0L, complete = true)
    }
    if (isFile) {
        return StorageBytesMeasurement(bytes = length().coerceAtLeast(0L), complete = true)
    }
    if (!isDirectory) {
        return StorageBytesMeasurement(bytes = 0L, complete = true)
    }
    val children = try {
        directoryLister.listFiles(this)
    } catch (error: Exception) {
        return StorageBytesMeasurement(
            bytes = 0L,
            complete = false,
            failures = listOf(
                "storage_bytes_list_exception:${absolutePath}:${error.javaClass.simpleName}",
            ),
        )
    }
    if (children == null) {
        return StorageBytesMeasurement(
            bytes = 0L,
            complete = false,
            failures = listOf("storage_bytes_list_failed:${absolutePath}"),
        )
    }
    var total = 0L
    val failures = mutableListOf<String>()
    var complete = true
    for (child in children) {
        val childMeasure = child.measureStorageBytes(directoryLister)
        total = saturatingAdd(total, childMeasure.bytes.coerceAtLeast(0L))
        if (!childMeasure.complete) {
            complete = false
            failures += childMeasure.failures
        }
    }
    return StorageBytesMeasurement(
        bytes = total,
        complete = complete,
        failures = failures,
    )
}

/**
 * Legacy numeric size for non-decision diagnostics only.
 * Prefer [measureStorageBytes] for retention. Incomplete measurements return 0 (not a sentinel
 * that can trigger disk deletes); decision paths must use [measureStorageBytes.complete].
 */
fun File.storageBytes(
    directoryLister: ReliabilityDirectoryLister = DefaultReliabilityDirectoryLister,
): Long {
    val m = measureStorageBytes(directoryLister)
    return if (m.complete) m.bytes else 0L
}

fun <T> decodeOrNull(value: String, decode: (String) -> T): T? =
    try {
        decode(value)
    } catch (_: RuntimeException) {
        null
    }

fun encodeFileName(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

/** Fail-closed text read for retention ownership classification (never throws). */
fun File.readReliabilityTextOrNull(): String? =
    try {
        if (isFile) readText() else null
    } catch (_: Exception) {
        null
    }

private fun File.readTextOrNull(): String? = readReliabilityTextOrNull()

const val RUNS_DIR = "runs"
const val EVIDENCE_DIR = "evidence"
const val PROTOCOL_REQUESTS_DIR = "protocol-requests"
const val PROTOCOL_RESOLUTIONS_DIR = "protocol-resolutions"
const val RUN_FILE = "run.json"
const val WORKPAD_FILE = "workpad.json"
const val RESULT_FILE = "result.json"
const val HANDOFF_FILE = "handoff.json"
const val OPEN_CONFUSION_FILE = "open-confusion.json"
const val EVIDENCE_JSONL_FILE = "evidence.jsonl"
const val TRACE_JSONL_FILE = "trace.jsonl"
const val PROTOCOL_REQUESTS_JSONL_FILE = "protocol-requests.jsonl"
const val RELIABILITY_CLEANUP_MARKER_FILE = "cleanup-schedule.properties"

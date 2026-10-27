package cc.noharry.agent.runtime.workspace
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.core.state.encodeFileName
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsReadResult
import cc.noharry.agent.vfs.VfsStatResult
import cc.noharry.agent.vfs.VfsWriteMode
import cc.noharry.agent.vfs.VfsWriteResult
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal class VirtualWorkspaceWriter(
    private val fileSystem: VfsFileSystem,
    private val runId: String,
    private val budget: WorkspaceWriteBudget = WorkspaceWriteBudget(),
    private val readGuard: RuntimeVfsReadGuard = RuntimeVfsReadGuard(),
) {
    suspend fun execute(toolName: String, argumentsJson: String): String =
        when (toolName) {
            "write_file" -> writeFile(argumentsJson)
            "edit_file" -> editFile(argumentsJson)
            else -> errorResult(RuntimeErrorCode.UNSUPPORTED_NATIVE_TOOL.wireCode, "Unsupported write tool.")
        }

    private suspend fun writeFile(argumentsJson: String): String {
        val args = parseJsonObject(argumentsJson) ?: return invalidPath()
        val path = canonicalLogicalPath(args.stringField("path") ?: return invalidPath()) ?: return invalidPath()
        val content = args.stringField("content") ?: ""
        val mode = parseWriteMode(args.stringField("mode") ?: WRITE_MODE_CREATE) ?: return writeDenied()
        val targetPath = writablePath(path) ?: return writeDenied()
        if (isBinaryText(content)) {
            return errorResult(RuntimeErrorCode.BINARY_FILE_UNSUPPORTED.wireCode, "Binary content is not supported.")
        }
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        if (!budget.tryConsume(contentBytes.size.toLong(), contentBytes.size.toLong())) {
            return writeBudgetExceeded()
        }
        val expectedRevision = when (val guard = expectedRevisionFor(path, targetPath, mode)) {
            is ReadGuardDecision.Allowed -> guard.expectedRevision
            is ReadGuardDecision.Denied -> return errorResult(guard.code, guard.message)
        }
        val original = readExistingText(targetPath)
        return when (val result = fileSystem.write(targetPath, content, mode, expectedRevision)) {
            is VfsWriteResult.Written -> {
                val updated = if (mode == VfsWriteMode.APPEND) original.orEmpty() + content else content
                val diff = diffSummary(original.orEmpty(), updated)
                """{"ok":true,"path":"${escape(path)}","bytes":${result.bytes},"mode":"${escape(mode.wireName())}"""" +
                    diff.toJsonFields() +
                    "}"
            }
            is VfsWriteResult.Error -> errorResult(result.code, result.message)
            VfsWriteResult.Conflict ->
                errorResult(RuntimeErrorCode.WORKSPACE_WRITE_CONFLICT.wireCode, "File already exists.")
            VfsWriteResult.BinaryUnsupported ->
                errorResult(RuntimeErrorCode.BINARY_FILE_UNSUPPORTED.wireCode, "Binary content is not supported.")
            VfsWriteResult.InvalidPath,
            VfsWriteResult.NotFound,
            VfsWriteResult.WriteDenied,
            -> writeDenied()
        }
    }

    private suspend fun editFile(argumentsJson: String): String {
        val args = parseJsonObject(argumentsJson) ?: return invalidPath()
        val path = canonicalLogicalPath(args.stringField("path") ?: return invalidPath()) ?: return invalidPath()
        val oldString = args.stringField("old_string") ?: return editNoMatch()
        val newString = args.stringField("new_string") ?: ""
        val replaceAll = args.booleanField("replace_all") ?: false
        val targetPath = writablePath(path) ?: return writeDenied()
        val expectedRevision = when (val guard = expectedRevisionFor(path, targetPath, VfsWriteMode.OVERWRITE)) {
            is ReadGuardDecision.Allowed -> guard.expectedRevision
            is ReadGuardDecision.Denied -> return errorResult(guard.code, guard.message)
        }
        if (isBinaryText(newString)) {
            return errorResult(RuntimeErrorCode.BINARY_FILE_UNSUPPORTED.wireCode, "Binary content is not supported.")
        }
        val original = when (val result = fileSystem.read(targetPath, 0, MAX_WORKSPACE_WRITE_FILE_BYTES.toInt() + 1)) {
            is VfsReadResult.Found -> result.text
            is VfsReadResult.Error -> return errorResult(result.code, result.message)
            VfsReadResult.BinaryUnsupported ->
                return errorResult(RuntimeErrorCode.BINARY_FILE_UNSUPPORTED.wireCode, "Binary content is not supported.")
            VfsReadResult.InvalidPath,
            VfsReadResult.ReadDenied,
            -> return writeDenied()
            VfsReadResult.NotFound -> return errorResult(RuntimeErrorCode.NOT_FOUND.wireCode, "File not found.")
        }
        val matches = countOccurrences(original, oldString)
        if (oldString.isEmpty() || matches == 0 || (!replaceAll && matches > 1)) {
            return editNoMatch()
        }
        val updated = if (replaceAll) original.replace(oldString, newString) else original.replaceFirst(oldString, newString)
        val bytes = updated.toByteArray(Charsets.UTF_8)
        if (!budget.tryConsume(bytes.size.toLong(), bytes.size.toLong())) {
            return writeBudgetExceeded()
        }
        val diff = diffSummary(original, updated)
        return when (val result = fileSystem.write(targetPath, updated, VfsWriteMode.OVERWRITE, expectedRevision)) {
            is VfsWriteResult.Written -> {
                val replacements = matches.takeIf { replaceAll } ?: 1
                """{"ok":true,"path":"${escape(path)}","bytes":${result.bytes},"replacements":$replacements""" +
                    diff.toJsonFields() +
                    "}"
            }
            is VfsWriteResult.Error -> errorResult(result.code, result.message)
            VfsWriteResult.BinaryUnsupported ->
                errorResult(RuntimeErrorCode.BINARY_FILE_UNSUPPORTED.wireCode, "Binary content is not supported.")
            VfsWriteResult.Conflict ->
                errorResult(RuntimeErrorCode.WORKSPACE_WRITE_CONFLICT.wireCode, "File changed after it was read.")
            VfsWriteResult.InvalidPath,
            VfsWriteResult.NotFound,
            VfsWriteResult.WriteDenied,
            -> writeDenied()
        }
    }

    suspend fun cleanupScratch() {
        fileSystem.deleteTree(runScratchPath())
    }

    private fun writablePath(path: String): String? {
        if (!isWritablePath(path)) return null
        return if (path.startsWith(SCRATCH_PREFIX)) {
            runScratchPath(path.removePrefix(SCRATCH_PREFIX))
        } else {
            path
        }
    }

    private suspend fun expectedRevisionFor(path: String, targetPath: String, mode: VfsWriteMode): ReadGuardDecision =
        if (targetPath.startsWith(runScratchPath())) {
            ReadGuardDecision.Allowed(expectedRevision = null)
        } else if (mode == VfsWriteMode.CREATE) {
            ReadGuardDecision.Allowed(expectedRevision = null)
        } else {
            when (val guard = readGuard.revisionForWrite(path)) {
                is ReadGuardDecision.Allowed -> guard
                is ReadGuardDecision.Denied -> {
                    if (guard.code == RuntimeErrorCode.WORKSPACE_READ_REQUIRED.wireCode &&
                        fileSystem.stat(targetPath) is VfsStatResult.NotFound
                    ) {
                        ReadGuardDecision.Allowed(expectedRevision = null)
                    } else {
                        guard
                    }
                }
            }
        }

    private fun runScratchPath(relativePath: String = ""): String =
        "/$SCRATCH_DIR/${encodeFileName(runId)}/${relativePath.trimStart('/')}".trimEnd('/')

    /** Where [path] is stored: the model's /scratch is this run's own folder, which it reads and lists as /scratch too. */
    fun storagePath(path: String): String = when {
        path == SCRATCH_PREFIX.trimEnd('/') -> runScratchPath()
        path.startsWith(SCRATCH_PREFIX) -> runScratchPath(path.removePrefix(SCRATCH_PREFIX))
        else -> path
    }

    /** The path the model knows for stored [path]; null for another run's scratch, which this run does not see. */
    fun modelPath(path: String): String? {
        val own = runScratchPath()
        return when {
            path == own || path.startsWith("$own/") -> SCRATCH_PREFIX.trimEnd('/') + path.removePrefix(own)
            path.startsWith(SCRATCH_PREFIX) -> null
            else -> path
        }
    }

    private suspend fun readExistingText(targetPath: String): String? =
        when (val result = fileSystem.read(targetPath, 0, MAX_WORKSPACE_WRITE_FILE_BYTES.toInt() + 1)) {
            is VfsReadResult.Found -> result.text
            else -> null
        }
}

internal data class WorkspaceDiffSummary(
    val addedLines: Int,
    val removedLines: Int,
    val timedOut: Boolean,
) {
    fun toJsonFields(): String =
        ""","added_lines":$addedLines,"removed_lines":$removedLines,"diff_timed_out":$timedOut"""
}

internal fun diffSummary(
    before: String,
    after: String,
    nowNanos: () -> Long = System::nanoTime,
    timeoutMs: Long = DIFF_TIMEOUT_MS,
): WorkspaceDiffSummary {
    val start = nowNanos()
    val beforeLines = before.diffLineSequence().iterator()
    val afterLines = after.diffLineSequence().iterator()
    var added = 0
    var removed = 0
    while (beforeLines.hasNext() || afterLines.hasNext()) {
        if (elapsedMs(start, nowNanos) > timeoutMs) return WorkspaceDiffSummary(added, removed, timedOut = true)
        val oldLine = if (beforeLines.hasNext()) beforeLines.next() else null
        val newLine = if (afterLines.hasNext()) afterLines.next() else null
        if (oldLine == null && newLine != null) added += 1
        if (oldLine != null && newLine == null) removed += 1
        if (oldLine != null && newLine != null && oldLine != newLine) {
            added += 1
            removed += 1
        }
    }
    return WorkspaceDiffSummary(added, removed, timedOut = false)
}

private fun String.diffLineSequence(): Sequence<String> =
    if (isEmpty()) emptySequence() else lineSequence()

private fun elapsedMs(startNanos: Long, nowNanos: () -> Long): Long =
    TimeUnit.NANOSECONDS.toMillis(nowNanos() - startNanos)

private fun canonicalLogicalPath(path: String): String? {
    val canonical = canonicalRuntimeWorkspacePath(path) ?: return null
    return canonical.takeUnless { it.startsWith(TRACES_PREFIX) }
}

private fun isWritablePath(path: String): Boolean =
    path.startsWith(SCRATCH_PREFIX) ||
        (path.startsWith(MEMORY_PREFIX) && !path.isRuntimeManagedMemoryPath())

private fun String.isRuntimeManagedMemoryPath(): Boolean =
    equals(RUNTIME_MEMORY_LESSONS_ROOT, ignoreCase = true) ||
        startsWith("$RUNTIME_MEMORY_LESSONS_ROOT/", ignoreCase = true)

private fun parseWriteMode(mode: String): VfsWriteMode? =
    when (mode) {
        WRITE_MODE_CREATE,
        WRITE_MODE_CREATE_NEW,
        -> VfsWriteMode.CREATE
        WRITE_MODE_OVERWRITE -> VfsWriteMode.OVERWRITE
        WRITE_MODE_APPEND -> VfsWriteMode.APPEND
        WRITE_MODE_ATOMIC_REPLACE -> VfsWriteMode.ATOMIC_REPLACE
        else -> null
    }

private fun VfsWriteMode.wireName(): String =
    when (this) {
        VfsWriteMode.CREATE -> WRITE_MODE_CREATE
        VfsWriteMode.OVERWRITE -> WRITE_MODE_OVERWRITE
        VfsWriteMode.APPEND -> WRITE_MODE_APPEND
        VfsWriteMode.ATOMIC_REPLACE -> WRITE_MODE_ATOMIC_REPLACE
    }

private fun parseJsonObject(json: String): JsonObject? =
    try {
        Json.parseToJsonElement(json) as? JsonObject
    } catch (_: RuntimeException) {
        null
    }

private fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.booleanField(name: String): Boolean? =
    this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun countOccurrences(text: String, needle: String): Int {
    var count = 0
    var index = 0
    while (needle.isNotEmpty()) {
        val next = text.indexOf(needle, index)
        if (next < 0) return count
        count += 1
        index = next + needle.length
    }
    return count
}

private fun isBinaryText(value: String): Boolean =
    value.toByteArray(Charsets.UTF_8).any { byte ->
        val code = byte.toInt() and BYTE_MASK
        code == 0 || (code < ASCII_SPACE && code !in TEXT_CONTROL_BYTES)
    }

private fun invalidPath(): String =
    errorResult(RuntimeErrorCode.INVALID_WORKSPACE_PATH.wireCode, "Invalid workspace path.")

private fun writeDenied(): String =
    errorResult(RuntimeErrorCode.WORKSPACE_WRITE_DENIED.wireCode, "Workspace write denied.")

private fun writeBudgetExceeded(): String =
    errorResult(RuntimeErrorCode.WORKSPACE_WRITE_BUDGET_EXCEEDED.wireCode, "Workspace write budget exceeded.")

private fun editNoMatch(): String =
    errorResult(RuntimeErrorCode.WORKSPACE_EDIT_NO_MATCH.wireCode, "No unique edit match found.")

private fun errorResult(code: String, message: String): String =
    """{"ok":false,"code":"$code","message":"${escape(message)}"}"""

private fun escape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

private const val WRITE_MODE_CREATE = "create"
private const val WRITE_MODE_CREATE_NEW = "create_new"
private const val WRITE_MODE_OVERWRITE = "overwrite"
private const val WRITE_MODE_APPEND = "append"
private const val WRITE_MODE_ATOMIC_REPLACE = "atomic_replace"
private const val DIFF_TIMEOUT_MS = 100L
private const val SCRATCH_DIR = "scratch"
private const val SCRATCH_PREFIX = "/scratch/"
private const val MEMORY_PREFIX = "/memory/"
private const val RUNTIME_MEMORY_LESSONS_ROOT = "/memory/lessons"
private const val TRACES_PREFIX = "/traces"
private const val ASCII_SPACE = 32
private const val BYTE_MASK = 0xFF
private val TEXT_CONTROL_BYTES = setOf(9, 10, 13)
